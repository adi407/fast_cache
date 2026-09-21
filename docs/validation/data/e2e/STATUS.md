# STATUS — end-to-end JVM matrix, run 2

## ENTIRE RUN: INVALID. Do not cite any row in `steady.csv`.

Frozen, not deleted, so the failure stays inspectable. Full analysis:
[`../../THESIS_REASSESSMENT.md`](../../THESIS_REASSESSMENT.md) §Benchmark integrity.

## What happened

Run 1 was aborted at 02:13:08. Its `EXIT` trap removed `.matrix.lock`, but the service JVM it had
launched — the 25 MB `fastcache-embedded` cell, by then throwing `OutOfMemoryError` — **survived and kept
port 8090**. Run 2 started at 02:13:44 into this same directory. `run_cell`'s pre-flight probes
`/admin/pid` with a 2-second timeout; an OOM-thrashing JVM cannot answer inside it, so the port read as
free.

Result: two matrix runs interleaved on one 16 GB host, with cells measuring each other's processes.

## Row classification

| Row in `steady.csv` | Classification | Reason |
|---|---|---|
| `steady-caffeine-1m-c8` | **INVALID — host resource exhaustion + harness contamination** | Its own service logged `APPLICATION FAILED TO START — Port 8090 was already in use`. The load generator measured **run 1's abandoned, OOM-ing 25 MB embedded service** instead. The row proves it: `arm=fastcache-embedded`, `payloadBytes=26214400`, 156/512 entries, 266 errors. |
| `steady-fastcache-embedded-1m-c8` | **INVALID — host resource exhaustion** | 3 205 req/s vs run 1's 5 401 for the identical cell (−41%). 1 022 misses for 512 entries: evicted and reloaded mid-run. |
| `steady-fastcache-sidecar-1m-c8` | **INVALID — host resource exhaustion** | 519 req/s vs 998 (−48%). `heapUsed` 466 MB where run 1 measured 26 MB — impossible for an arm that stores nothing on the heap. |
| `steady-redis-1m-c8` | **INVALID — host resource exhaustion** | 547 req/s vs 759 (−28%). |
| `steady-caffeine-10m-c1`, `steady-fastcache-sidecar-25m-c8`, `steady-redis-25m-c8` | **INVALID — harness contamination** | `APPLICATION FAILED TO START — port in use`; no rows produced |
| `steady-caffeine-10m-c8` | **INVALID** | zero-byte applog; the process never wrote |

## The row that must not be misread

`steady-caffeine-1m-c8` — **2 req/s, 4 090 MB heap, 36 170 ms of GC pause in a 40-second window, 266
errors** — is labelled `caffeine` and is **not a Caffeine measurement**. It is run 1's orphaned 25 MB
embedded-FastCache service dying of heap exhaustion while a second matrix competed for the host.

**It is an environment failure. It is not evidence about FastCache vs Caffeine, and it is not evidence
about constrained-heap behaviour, which remains unmeasured.**

## The `.jfr` recordings are deliberately not committed

This run was taken with `-XX:StartFlightRecording=settings=profile`, which records the JVM's initial
environment variables. Every one of the eight recordings therefore embedded the running shell's
`OPENAI_API_KEY`. GitHub's push protection blocked the commit; the key never reached the remote.

`*.jfr` is now in `.gitignore`. The files remain on disk for anyone who wants them locally, and the
CSV, applogs and this classification are the parts worth keeping anyway — the run is invalid in its
entirety, so its profiles support no conclusion.

## Required before re-running

1. Flush the sidecar and Redis between payload tiers; assert post-flush entry count is zero; make a
   non-zero corrupt-read count **fail** the cell rather than print a warning.
2. Bind-test the port instead of probing `/admin/pid` — a 2-second timeout reads an unresponsive JVM as
   absent.
3. Hold `.matrix.lock` for the process tree, not the shell. A trap that releases the lock while child
   JVMs survive is worse than no lock: it makes the next run look safe.
