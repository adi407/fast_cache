# Benchmark integrity audit

Phase 1 of the native-Linux validation. The previous Redis result favoured FastCache, so the benchmark
that produced it is audited here on the assumption that it is **wrong until shown otherwise** — not
trusted because it already ran.

Audited at commit `535405f` + the uncommitted benchmark harness. Three defects were found and fixed
before any further numbers were collected. They are documented here, separately from the product, because
none of them is a product change.

---

## Integrity checklist

| Area | FastCache path | Redis path | Equivalent? | Concern |
|---|---|---|---|---|
| **Payload** | `Payloads.of(size, seed)` — incompressible random slices from a shared 8 MB pool, fresh `byte[]` per call, 4-byte seed stamp | identical call, identical bytes | **Yes** | None. Same generator, same allocation, invoked from the same scenario code. |
| **Client** | `WireClient` — 22-byte binary header, `readFully` into a sized array | `RespClient` — RESP2 bulk string, `readFully` into a sized array | **Yes, with one noted asymmetry** | `RespClient.readLine()` consumes the `$<len>\r\n` header byte-by-byte (~11 `read()` calls) versus `WireClient`'s ~8 via `DataInputStream`. Three extra synchronised calls, ≈30–60 ns, against a 1 105 µs p50 at 1 MB — 0.005%. Quantified and left alone; changing it would be churn, not rigour. |
| **Client library** | hand-rolled | hand-rolled | **Yes** | Deliberate. Using Lettuce for Redis and a hand-rolled client for FastCache would fold client-library quality into the answer. Bias from a minimal client runs *in Redis's favour*. |
| **TCP** | `new Socket(host, port)`, `setTcpNoDelay(true)`, 64 KiB `BufferedInputStream`/`BufferedOutputStream` | byte-for-byte identical code | **Yes** | Verified by direct comparison of `borrow()` in both classes. Neither sets `SO_SNDBUF`/`SO_RCVBUF`, so both inherit the same OS defaults. |
| **Pooling** | `ArrayDeque` guarded by `synchronized`, borrow/return per call | identical | **Yes** | Same structure, same lock discipline. |
| **Serialization** | none — raw `byte[]` | none — raw `byte[]` | **Yes** | Generous to FastCache's sidecar, which in production would have to encode an object graph. Recorded as a limitation, not corrected — correcting it would penalise Redis equally. |
| **Concurrency** | `Scenarios.runConcurrently`, fixed thread pool | same method, same pool | **Yes** | Both arms driven by the identical harness. |
| **Warmup** | ~~`min(operations/10, 200)`~~ → `max(200, min(operations, keys × 5))` | same | **Now yes — was a defect** | **DEFECT 1**, see below. |
| **Arm order** | ~~fixed: caffeine → sidecar → redis~~ → rotated per run | same | **Now yes — was a defect** | **DEFECT 2**, see below. |
| **Measurement window** | `System.nanoTime()` around `arm.get()` only | identical | **Yes** | Warmup excluded, teardown excluded, fill excluded. |
| **Percentiles** | `Stats.of()` — nearest-rank, no interpolation, no outlier removal | identical | **Yes** | Same code object for both arms. |
| **Throughput** | operations ÷ wall-clock of the measured window | identical | **Yes** | |
| **GC measurement** | `GarbageCollectionNotificationInfo` per collection + `-Xlog:gc` | identical (client-side; both measure the *application* JVM) | **Yes** | Neither arm's *server* GC is measured. FastCache's sidecar is a JVM and has one; Redis has none. Recorded as a limitation. |
| **RSS measurement** | `/proc/<pid>/status` on Linux, PowerShell `WorkingSet64` on Windows | identical function, different pid | **Yes** | |
| **Payload validation** | ~~4-byte seed stamp only~~ → length **and** seed | same | **Now yes — was a defect** | **DEFECT 3**, see below. |
| **Server lifecycle** | fresh sidecar JVM per cell | one long-lived server, `FLUSHALL` per scenario | **No — asymmetric** | Tested empirically rather than assumed; see below. Direction favours Redis. |
| **Server configuration** | budget 2 GB, `reject-ratio` per run | `maxmemory 2gb`, `allkeys-lru`, persistence off | **Approximately** | Both bounded at 2 GB; working set 512 MB, so neither evicts. Persistence off on both (FastCache has none). |

---

## Defect 1 — warmup was insufficient, and it leaked into the results

Warmup was capped at 200 operations regardless of key-set size. Measured drift of p50 from run 1 to run 3,
same process, same code:

| payload | caffeine | fastcache-sidecar | redis |
|---|---:|---:|---:|
| 256 KB | **−93.5%** | −35.5% | −46.9% |
| 1 MB | −84.5% | −10.4% | −25.1% |
| 10 MB | −72.3% | +2.6% | −12.3% |
| 25 MB | −82.2% | −14.0% | −16.3% |

Run 1 was slower than run 3 for essentially every cell. Averaging three runs was therefore averaging
warmup with steady state.

**Fix:** `warmup = max(200, min(operations, keys × 5))`, so every key is touched at least five times and
the client path is compiled before measurement begins.

## Defect 2 — fixed arm order handed the last arm a hotter JIT

Arms always ran in the order `caffeine, fastcache-sidecar, redis` within each cell, in one shared JVM.
Given the drift above, the arm running last benefits systematically.

Note the direction: **Redis always ran last, so this bias favoured Redis — and Redis still lost.** The
previous result is therefore conservative with respect to this defect, not inflated by it. It is fixed
anyway, because "the bias happened to point the other way" is not a methodology.

**Fix:** the arm list is left-rotated by the run index, so each arm occupies each position across runs.
The chosen position is recorded per row as `armOrder` so the effect can be checked rather than assumed.

## Defect 3 — payload validation could not detect truncation

`Scenarios.read` verified only the 4-byte seed stamp at the head of each payload. A transport returning a
short read would have passed that check and appeared **faster**, which is the most dangerous possible
failure mode for this particular comparison.

**Fix:** length is now checked alongside the stamp. Full-array equality is deliberately not used inside
the timed loop — a payload-sized `memcmp` per iteration would measure the check rather than the cache —
but `CrossProcessBench` does perform full `Arrays.equals` verification outside any timed path.

*Retrospective check on the already-collected data:* allocation volume matched 1000 full payloads to
within 1% in every cell (25 002 MB observed against 25 000 MB expected at 25 MB), with zero corrupt reads.
The previous numbers were not affected by this defect; the check was simply not strong enough to have
proven that at the time.

## Defect 4 (harness hygiene) — leaked GC listeners

`Probe` attached a `NotificationListener` to every garbage collector and never removed it. The matrix
builds one `Probe` per cell, so by the sixtieth cell sixty listeners fired on every collection. The
accumulating overhead lands on whichever cells run last.

**Fix:** `Probe` is now `AutoCloseable` and detaches its listeners; `Bench` acquires it in
try-with-resources.

---

## The one asymmetry left standing, and why

**FastCache gets a fresh sidecar JVM per cell; Redis is one long-lived server.**

This is a genuine asymmetry and it cannot be removed without either restarting Redis per cell (which is
not how anyone runs Redis) or reusing one sidecar (which is not how the harness builds arms). Rather than
argue about it, it was tested: if the shared Redis process were degrading across cells — fragmenting
jemalloc arenas, say — its p50 would drift *upward* across runs.

It does the opposite. Redis's p50 improved by 12–47% from run 1 to run 3 in every cell. Redis benefits
from being warm; FastCache pays JVM cold-start in each cell. **The asymmetry favours Redis.**

Combined with Defect 2 (Redis always last, therefore hottest JIT), both surviving asymmetries point the
same way: they make the FastCache advantage look *smaller* than it is, not larger.

---

## What this audit does not resolve

- **Platform.** Everything above concerns methodology. The dominant open question is that the Redis under
  test was Memurai 4.1.2 on Windows (WinSock IOCP), not native Redis on Linux (epoll). No amount of
  harness correctness fixes that. It is the entire reason for the Linux phase.
- **Server-side GC.** The FastCache sidecar is a JVM with its own collector; Redis has none. Only the
  application JVM is instrumented.
- **Object graphs.** Both arms move `byte[]`. A real Spring value would need encoding for either.
