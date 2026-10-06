# Proposed patches

## Licensing

The perf HAL and the `perfservice` daemon are **not** open source. They ship as
proprietary Qualcomm components (`android-perf/perf-hal`, `android-perf/mp-ctl`,
`commonsys/perf-core/perfservice`). The patches here are proposed changes against
those proprietary files and are licensed separately from the driver code they touch.

## Verification status

| Patch | Applies | Compiled | Run on device |
|---|---|---|---|
| `01-getnode-bound-fix.patch` | ✓ `git apply --check` against the vendor source | ✗ no build environment for the HAL | ✗ |
| `02-perfservice-caller-uid-gate.patch` | ✓ `git apply --check` against the vendor source | ✗ would need a vendor build | ✗ |

Both were verified to apply cleanly against the exact source they target
(`git apply --check` passed), and **nothing more**. Neither was compiled, and neither
was run on any device. Treat them as a description of the intended change, not as a
drop-in fix.

Paths were rewritten for readability (`mp-ctl/…`, `perfservice/…`); remap them to
`android-perf/mp-ctl/` and `commonsys/perf-core/perfservice/` before applying.

## 1. `01-getnode-bound-fix.patch` — the one-line fix, worth applying regardless

`ResourceQueue::GetNode()` rejects indices with `resObj.core > tc.getTotalNumCores()`,
but the per-core array is allocated with `calloc(tc.getTotalNumCores(), sizeof(q_node))`.
When `core == TotalNumCores` the check passes and the function indexes one element past
the end of the allocation, then writes into it.

The fix is the missing `=`. Nothing else changes.

Why this one is worth taking on its own: it is one character, it cannot regress any
behaviour that is currently valid, and it removes an out-of-bounds write in a root
process. On 8-core parts it is also a no-op in practice (the index cannot reach 8),
which makes it a cheap, safe hardening change rather than a risk.

## 2. `02-perfservice-caller-uid-gate.patch` — apply only after checking hardware

Adds the server-side `getCallingUid()` check that is missing today. Two things the vendor
has to decide, which this patch deliberately does not decide:

- **How wide the allow-list is.** The patch allows only `AID_ROOT` and `AID_SYSTEM`. That
  will break legitimate Qualcomm clients that run as unprivileged uids — including,
  potentially, Qualcomm's own Java wrapper — so the vendor must enumerate the real caller
  set first. An empty or too-narrow list takes out the boost path for real apps.
- **Whether to gate or to bind to self.** An alternative to an allow-list is to never accept
  a caller-supplied target pid at all and resolve the target to the caller's own pid/tid
  server-side. That preserves the API for legitimate single-process callers and removes the
  cross-process capability entirely. The patch implements the first option because it is
  the smaller change.

Apply this only after confirming which uids legitimately call the service on the target
build, or the perf boost path will be broken for real users.

## What I am not fixing, and why

- **`perfLockRelease*` has no ownership check** on the handle. A real fix needs a
  handle→uid mapping in the release path; the current `HandleInfo` set stores the pid but
  not the uid. Left out of this series.
- **The `pkg_name` string interpolation in the IOP sink** (`dblayer.cpp`) is a separate
  component with a separate owner. Reported in the main report §5.4, not patched here.
- **The config-table node paths themselves** are only readable on the read-only vendor
  partition, so there is no path-injection surface to close; clamping the `%d` values to the
  caller's own pid/tid would be the equivalent of option (b) above.
