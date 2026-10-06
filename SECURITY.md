# Security

This repository publishes a vulnerability research disclosure.

## What is disclosed here

Two findings in Qualcomm's performance stack:

1. **Missing server-side authorization** — `vendor.perfservice` and the perf HAL perform no
   caller identity check, so any unprivileged installed application can drive a **root** process
   to write kernel nodes for an arbitrary target pid. Device-verified.
2. **Heap out-of-bounds write** — `ResourceQueue::GetNode` checks
   `resObj.core > getTotalNumCores()` where `>=` is required, allowing one element past the end of
   a `calloc`-allocated array. Confirmed present in the shipping binary.

## Explicitly out of scope of this writeup

- **No privilege escalation is claimed.** Neither finding escalates privileges.
- **No heap exploitation is attempted or claimed.** The out-of-bounds write is one ~40-byte
  partially caller-influenced structure; reaching code execution requires heap grooming, which was
  not done.
- **The out-of-bounds write is part-dependent.** It is only reachable on parts whose perf target
  configuration declares `TotalNumCores <= 7`. On 8-core parts — including the device tested here
  (`TotalNumCores = 8`) — it is **not reachable** via this opcode path. A failed reproduction on an
  8-core device is expected, not a counter-example.
- **The SQL sink in §5.4 is not verified.** Only the missing authorization and the accepted
  request were confirmed live; the sink was gated off on the tested build.
- No system crash, reboot, or denial of service was observed at any point.

## Responsible use

- Run the PoC only on hardware you own and have the right to test.
- The demonstrated effects are scheduling-state changes and a device-wide CPU frequency floor.
  They are user-invisible and reversible (the floor restores when the lock expires, reboot clears
  everything). Still, do not run this on shared, rented, or production devices.
- The cross-application variant targets another application's process. On a real device this is
  observable to that application and its user.

## Reporting a finding

- Open an issue marked `security`, **not** a pull request. Do not attach exploit code to a public
  issue.
- A contact for coordinated reporting is `3849639991 at qq.com`.
- For Qualcomm parts, reports are routed through Qualcomm's product-security team. For Linux
  kernel parts, the kernel's own process is documented in `REPORTING-BUGS` at the root of the
  kernel tree. Use the channel that applies to the part you are reporting rather than this
  repository.

## Attribution

Please attribute findings from this repository as `qti-perfd`. The report in `README.md` is
published under the license in `LICENSE`.
