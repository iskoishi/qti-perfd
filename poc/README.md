# PoC: unprivileged app drives the root perf HAL

## What it shows

A single Android application, with **no declared permissions**, makes the root-owned
perf HAL write a privileged value into a *target process's* kernel node — while the
application itself cannot even open that node.

This is the §5.2 verification of the main report.

## Files

| File | What it is |
|---|---|
| `PerfservicePrivilegePoC.java` | Standalone single-file PoC. Raw binder only — **no Qualcomm client library**, no `QPerformance.jar`. |
| `Makefile` | `make javac-check` compiles against `android-34` and reports ok. |

## Verification status

- **Compile-verified** against `android-34` with `javac`, clean output.
- **The logic is the verified path**: the §5 results were produced with this sequence
  embedded in a long-standing in-house probe.
- **Not packaged or signed** as part of this release. Build and sign it yourself.

## Build

```sh
export ANDROID_HOME=/path/to/sdk
make javac-check
```

## Run

```sh
adb install poc.apk

# pick a victim pid: any process other than the PoC itself
adb shell 'nohup sleep 300 >/dev/null 2>&1 & echo $!'        # -> VICTIM_PID
adb shell 'cat /proc/VICTIM_PID/sched_boost'                  # -> 0   (baseline)

adb shell am start -n com.example.perfpoc/.MainActivity --ei tgt VICTIM_PID

adb shell 'cat /proc/VICTIM_PID/sched_boost'                  # -> 3   (after)
```

All steps print to logcat tag `PERFPOC`.

Read the victim node from a **shell-side** process — the calling application cannot
open it.

## Two things that will make it look like it failed

1. **Parcel argument order.** `BnPerfManager::onTransact` reads `duration` before the
   array length. If `duration` is placed after, the server reads a small value and the
   lock expires within milliseconds — the write happens and is undone before you look.
2. **`--ei tgt` not passed.** Without an explicit victim pid the PoC has nothing to
   target and stops at the baseline read.

## What it does not do

- It does not raise the CPU frequency floor (§5.3 of the main report); that request needs
  the opcode and duration for that node, which is deliberately not scripted here.
- It does not attempt the `perfUXEngine_events` path (§5.4), which is gated off on the
  tested build.
- It contains no heap work. Nothing here is oriented toward code execution.
