# FastCache vs native Redis on Linux — validation report

Run on native Linux with native Redis 7.0.15, 2026-09-19. Raw CSVs, the Redis config and the environment
record are in [`data/`](data/). Where a section says a number was not measured, it was not measured.

> **The measurements in this document stand (classified VALID, scoped to `--reject-ratio 1.0`). Its
> product conclusions in §21–§25 are superseded by [`THESIS_REASSESSMENT.md`](THESIS_REASSESSMENT.md),**
> which separates the three FastCache architectures, finds the JVM sidecar path does not exist as a
> product, and narrows the surviving differentiator to large-value bandwidth. §24's recommended next
> experiment was attempted and its decisive cell was contaminated; the replacement is
> `THESIS_REASSESSMENT.md` §One next experiment.

---

## 1. Executive summary

**The Windows result was half right, and the half that was wrong was the half that mattered for small
payloads.**

Against native Redis 7.0.15 on Ubuntu 24.04 (epoll, jemalloc 5.3.0, AMD EPYC), FastCache's advantage is
**real, large, and confined to large values**. It also reverses below roughly 1 MB, which the Windows
measurement against Memurai did not show.

| | |
|---|---|
| **Where FastCache wins** | ≥10 MB at any concurrency (p50 **0.18×–0.40×** of Redis); ≥1 MB once concurrency ≥8 |
| **Where Redis wins** | ≤1 MB single-client (p50 **1.17×–1.20×**, i.e. Redis 17–20% faster) |
| **Where FastCache loses even while winning on median** | **tail latency below 10 MB** — p99 is 1.94×–2.27× *worse* at 256 KB at every concurrency level tested |
| **Writes** | same crossover: Redis faster at 1 MB (1.26×), FastCache faster at 10–25 MB (0.39×–0.41×) |
| **Caffeine** | unchanged and untouchable in-process: 1–3 µs p50, 100–1000× faster than either |

Three findings worth more than the headline ratio:

1. **The advantage scales with payload size, exactly as the zero-copy hypothesis predicts.** Effective
   bandwidth at 8 concurrent clients is **flat** for FastCache (617→767 MB/s from 1 MB to 50 MB) and
   **degrades** for Redis (418→142 MB/s). At 256 KB the two are indistinguishable (344 vs 342 MB/s) —
   the per-byte advantage only emerges once payload dominates per-operation overhead.

2. **Redis's I/O threading does not change the outcome, on either platform.** `io-threads 4` with
   `do-reads yes` moved the 10 MB ratio from 0.24 to 0.24 and the 25 MB ratio from 0.22 to 0.21. The
   "Redis was just single-threaded" explanation is dead on Linux as it was on Windows.

3. **Under a read-heavy large-value mix, Redis's write latency collapses.** At 90% reads and 10 MB values,
   Redis SET p50 was **269 ms** and p99 **742 ms** (25 MB: 592 ms / 1 877 ms), against FastCache's 19 ms /
   31 ms. This is head-of-line blocking: writes queue behind large reads on Redis's single event loop.
   FastCache's thread-per-connection model does not have that failure mode. Caveat: at a 90/10 mix only
   ~40 SET samples per run land, so this is directionally strong but thinly sampled.

**Outcome: B — an advantage for certain payload sizes and workloads**, not a general one. The thesis as
written ("lower-overhead cross-process caching than a traditional Redis-style cache") is **supported only
for large values**, and is **contradicted below ~1 MB**, where Redis is faster on median and substantially
better on tail.

---

## 2. Existing hypothesis

The thesis under test, carried forward from the previous round:

> FastCache is a high-performance local cache engine for large values, using off-heap storage and
> zero-copy transport to provide lower-overhead cross-process caching than a traditional Redis-style
> cache.

What the previous round established, and which this round does not re-litigate:

| Finding | Status entering this round |
|---|---|
| Embedded FastCache (the Spring path) stores values **on the heap** via `putReference` | Established by code reading and confirmed three ways by measurement |
| Caffeine is far faster for ordinary in-process hits | Established: ~2–8 µs p50 against ~1–90 ms cross-process |
| FastCache sidecar removes payloads from the application heap | Established: 0 MB attributable heap against 510–1024 MB |
| Sidecar cuts total GC pause but worsens p99 pause and throughput | Established |
| FastCache beat Memurai/Redis 7.2.5 on Windows by 1.4×–3.8× | Established **on Windows only** |
| Mechanism is suspected to be an extra full-payload copy in Redis's reply path | Suspected, not proven |

The single open question this round exists to answer: **does the Redis advantage survive on Linux, where
Redis uses epoll and is at its best?**

---

## 3. Environment

### Primary — native Linux (GitHub Actions `ubuntu-latest`)

| | |
|---|---|
| Distribution | Ubuntu 24.04.5 LTS |
| Kernel | Linux 6.17.0-1022-azure x86_64 |
| CPU | AMD EPYC 7763 64-Core Processor, **4 vCPU** allocated |
| RAM | 15.6 GB total, 14.7 GB available |
| Redis | **redis-server 7.0.15**, jemalloc 5.3.0, native Linux build (epoll) |
| JDK | Temurin OpenJDK 21.0.12.1 LTS |
| Maven | 3.9.16 |
| Loopback MTU | 65536 |
| `net.core.rmem_max` / `wmem_max` | 1048576 / 212992 |
| Transparent hugepages | `[always] madvise never` |
| FastCache commit | `745b2a3` (benchmark harness only; product code unmodified from `535405f`) |

### Secondary — Windows (retained as a platform comparison only)

| | |
|---|---|
| CPU | Intel Core i7-10810U, 6 cores / 12 threads |
| RAM | 15.8 GB, 1.1–3.2 GB free during runs |
| OS | Windows 11 build 10.0.26200.9457 |
| Redis | **Memurai 4.1.2**, `redis_version:7.2.5`, jemalloc 3.6.0, WinSock IOCP |
| JDK | Temurin OpenJDK 21.0.11+10 LTS |

The two platforms differ in CPU vendor, core count, Redis build, allocator version and free memory. They
are **not** directly comparable to each other; each is internally consistent, and only within-platform
FastCache-vs-Redis ratios should be read across them.

---

## 4. Benchmark methodology

All four arms run through **identical scenario code**. The only thing that differs between arms is the
`CacheArm` implementation behind `put`/`get`.

| Arm | Payload lives | Process |
|---|---|---|
| `caffeine` | JVM heap | in-process |
| `fastcache-embedded` | JVM heap (`putReference`) | in-process |
| `fastcache-sidecar` | off-heap | second JVM |
| `redis` | server memory | separate server |

- **Payloads**: incompressible random slices from a shared 8 MB pool, fresh `byte[]` per call, with a
  4-byte seed stamp. Identical generator for every arm.
- **Clients**: `WireClient` (FastCache binary protocol) and `RespClient` (RESP2) are written to be
  structurally identical — same socket options, same 64 KiB buffered streams, same pooling. Deliberately
  **not** Jedis or Lettuce; see §5.
- **Warmup**: `max(200, min(operations, keys × 5))`, measured and reported, never skipped.
- **Percentiles**: nearest-rank, no interpolation, no outlier removal, same code for every arm.
- **Repeats**: 3 independent runs per cell; all runs reported, spread shown.
- **Arm order**: rotated per run so no arm permanently occupies the hottest-JIT position.
- **Validation**: every read checks payload length *and* seed stamp; `CrossProcessBench` additionally does
  full `Arrays.equals` outside the timed path.

Redis server configuration is committed at `docs/validation/data/redis-linux.conf` and generated by the
run script, so the exact settings travel with the numbers.

---

## 5. Benchmark integrity

A full audit is in [`BENCHMARK_INTEGRITY.md`](BENCHMARK_INTEGRITY.md). Summary of what it found and fixed
**before** these numbers were collected:

| Defect | Effect | Direction of bias | Fixed |
|---|---|---|---|
| Warmup capped at 200 ops | run 1 up to 47% slower than run 3 for every arm | random | yes |
| Fixed arm order | last arm got the hottest JIT — Redis was always last | **favoured Redis** | yes |
| Read validation checked a 4-byte stamp but not length | a truncated payload would read as *fast*, not broken | would have favoured whichever arm truncated | yes |
| `Probe` leaked a GC listener per cell | accumulating overhead on later cells — Redis | **favoured Redis** | yes |

One asymmetry could not be removed and was instead measured: FastCache gets a fresh sidecar JVM per cell
while Redis is one long-lived server. If the shared Redis were degrading, its p50 would drift upward
across runs. It drifts *downward* (12–47% faster from run 1 to run 3). **The asymmetry favours Redis.**

Why this matters for reading the result: the two surviving biases and the one unavoidable asymmetry all
point the same way. They make any FastCache advantage look **smaller** than it is, not larger.

**Not resolved by the audit:** the client is a hand-rolled RESP2 implementation rather than a production
Redis client. This is deliberate — using a mature client for one side and a hand-rolled one for the other
would fold client-library quality into the answer — and the bias again runs in Redis's favour, since a
minimal client has less abstraction overhead than netty's pipeline. It does mean these numbers are not a
claim about what a Lettuce-based application would see.

---

## 6. Native Redis configuration

Generated by `fastcache-benchmarks/linux-redis-validation.sh` and archived with the results.

| Setting | Value | Why |
|---|---|---|
| `save` | *(disabled)* | FastCache is non-durable and writes nothing to disk. Leaving RDB snapshotting on would compare a cache against a database. |
| `appendonly` | `no` | Same reason. |
| `maxmemory` | `2gb` | Bounded cache, matching the budget given to every other arm. |
| `maxmemory-policy` | `allkeys-lru` | LRU cache semantics, matching Caffeine's `maximumWeight`. |
| `proto-max-bulk-len` | `512mb` | Covers the 50 MB payload case; stated explicitly rather than relied on as a default. |
| `bind` | `127.0.0.1` | Same loopback path the FastCache sidecar uses. No container, no VM, no virtual NIC. |
| `io-threads` | `1` (primary), `4` (secondary) | **Left at the default for the primary result.** Varied in a second configuration to answer whether the outcome depends on Redis's threading model. |
| `protected-mode` | `no` | Loopback only; removes an auth round trip that FastCache also does not perform. |

FastCache's side is configured symmetrically: 2 GB budget, and `--reject-ratio 1.0` so that admission
control cannot confound a latency comparison. The admission behaviour is a separate finding, measured in
§15 rather than folded into these numbers.

---

## 7. GET results

Native Redis 7.0.15, `io-threads 1` (its default), 400 operations per cell, 3 runs, means shown. Every
cell reported **zero corrupt reads**, with payload length and seed stamp verified on every read.

### Single client

| payload | arm | ops/s | p50 µs | p95 µs | p99 µs | MB/s |
|---|---|---:|---:|---:|---:|---:|
| 256 KB | caffeine | 66 666 | **1** | 3 | 11 | — |
| 256 KB | fastcache-sidecar | 5 133 | 171 | 256 | 538 | 1 461 |
| 256 KB | redis | 6 691 | **146** | 170 | **237** | 1 709 |
| 1 MB | fastcache-sidecar | 2 655 | 361 | 445 | 679 | 2 769 |
| 1 MB | redis | 3 059 | **301** | 403 | 666 | 3 324 |
| 10 MB | fastcache-sidecar | 378 | **2 432** | 4 858 | **5 949** | 4 111 |
| 10 MB | redis | 142 | 6 935 | 7 626 | 9 807 | 1 442 |
| 25 MB | fastcache-sidecar | 105 | **8 818** | 14 650 | **17 116** | 2 835 |
| 25 MB | redis | 42 | 22 322 | 32 720 | 36 072 | 1 120 |
| 50 MB | fastcache-sidecar | 69 | **13 251** | 20 858 | **25 822** | 3 773 |
| 50 MB | redis | 20 | 46 036 | 66 962 | 72 566 | 1 086 |

### 8 concurrent clients

| payload | arm | ops/s | p50 µs | p95 µs | p99 µs | MB/s |
|---|---|---:|---:|---:|---:|---:|
| 256 KB | fastcache-sidecar | 8 482 | 727 | 2 427 | 3 356 | 344 |
| 256 KB | redis | 9 919 | 731 | **1 064** | **1 597** | 342 |
| 1 MB | fastcache-sidecar | 4 124 | **1 620** | 3 974 | 5 443 | **617** |
| 1 MB | redis | 3 134 | 2 392 | **3 058** | **4 975** | 418 |
| 10 MB | fastcache-sidecar | 578 | **13 501** | 21 472 | **25 707** | **741** |
| 10 MB | redis | 128 | 57 405 | 97 145 | 131 695 | 174 |
| 25 MB | fastcache-sidecar | 227 | **34 481** | 49 986 | **57 463** | **725** |
| 25 MB | redis | 44 | 154 305 | 295 626 | 316 054 | 162 |
| 50 MB | fastcache-sidecar | 122 | **65 160** | 87 512 | **98 029** | **767** |
| 50 MB | redis | 22 | 352 597 | 563 522 | 641 979 | 142 |

Run-to-run spread is tight and non-overlapping wherever a winner is declared — e.g. 50 MB at 8 clients:
FastCache [64 072–66 891] µs against Redis [351 674–353 094] µs.

### Secondary configuration — does the result depend on Redis's threading model?

`io-threads 4`, `io-threads-do-reads yes`, 8 concurrent clients:

| payload | ratio with `io-threads 1` | ratio with `io-threads 4` |
|---|---:|---:|
| 1 MB | 0.68 | 0.74 |
| 10 MB | 0.24 | 0.24 |
| 25 MB | 0.22 | 0.21 |

**No material change.** Consistent with the Windows finding, where enabling `io-threads 8` made Redis
marginally *slower*. Redis's I/O threading parallelises socket syscalls, not the reply-buffer copy, which
is where the large-value cost sits.

---

## 8. SET results

Pure-write workload (`--read-ratio 0.0`), 8 concurrent clients, 400 operations, 3 runs.

| payload | arm | SET p50 µs | SET p99 µs | SET MB/s | ratio (fc/redis) |
|---|---|---:|---:|---:|---:|
| 1 MB | fastcache-sidecar | 2 192 | 6 797 | 457 | **1.26 — Redis faster** |
| 1 MB | redis | **1 743** | 7 411 | **589** | |
| 10 MB | fastcache-sidecar | **19 426** | **35 533** | **515** | **0.39 — FastCache faster** |
| 10 MB | redis | 50 336 | 167 986 | 199 | |
| 25 MB | fastcache-sidecar | **51 292** | **81 794** | **488** | **0.41 — FastCache faster** |
| 25 MB | redis | 125 102 | 447 136 | 200 | |

**Writes show the same crossover as reads.** Redis is faster at 1 MB; FastCache is ~2.5× faster at 10–25 MB.
The advantage is therefore not a read-path artefact, which is what the write-path code reading predicted
(§11) — though see the caveat there about FastCache also paying a `memset` per write that Redis does not.

---

## 9. Mixed workload results

8 concurrent clients, GET and SET timed as separate distributions rather than blended — a single
percentile over two populations with different means describes neither.

### 50% read / 50% write

| payload | GET p50 ratio | GET p99 ratio | SET p50 ratio | SET p99 ratio |
|---|---:|---:|---:|---:|
| 1 MB | 1.17 (Redis) | 1.79 (Redis) | 1.07 (tie) | 0.74 (FastCache) |
| 10 MB | **0.53** | **0.49** | **0.24** | **0.19** |
| 25 MB | **0.42** | **0.40** | **0.23** | **0.14** |

### 90% read / 10% write

| payload | arm | GET p50 µs | SET p50 µs | SET p99 µs |
|---|---|---:|---:|---:|
| 1 MB | fastcache-sidecar | 1 911 | 2 764 | 8 371 |
| 1 MB | redis | 2 210 | 5 761 | 14 122 |
| 10 MB | fastcache-sidecar | **13 875** | **18 678** | **30 822** |
| 10 MB | redis | 30 918 | **269 915** | **742 428** |
| 25 MB | fastcache-sidecar | **34 921** | **46 564** | **71 229** |
| 25 MB | redis | 107 815 | **591 801** | **1 876 578** |

**The read-heavy mix is where Redis degrades worst, and it is not a small effect.** A 10 MB write behind
90% large reads took Redis 270 ms at the median and 742 ms at p99; at 25 MB, 592 ms and 1.88 s. FastCache
stayed at 19 ms / 31 ms and 47 ms / 71 ms.

The mechanism is head-of-line blocking: Redis processes commands on one event loop, so a write queues
behind whatever large reads are in front of it. FastCache runs one virtual thread per connection and does
not serialise unrelated operations.

**Sampling caveat, stated because it bounds the claim:** at a 90/10 mix only ~40 SET samples land per run
(400 operations × 10%). Three runs give ~120 samples, enough to establish the direction and order of
magnitude but not a reliable p99. The 50/50 mix, with ~600 SET samples, shows the same direction more
modestly (0.23–0.24 at 10–25 MB).

---

## 10. Concurrency results

The crossover is not a single payload size — it moves with concurrency.

### Median (p50), FastCache ÷ Redis. Below 1.00 FastCache is faster.

| payload | 1 client | 8 clients | 32 clients |
|---|---:|---:|---:|
| 256 KB | 1.17 **Redis** | 0.99 tie | **0.72** |
| 1 MB | 1.20 **Redis** | **0.68** | **0.26** |
| 10 MB | **0.35** | **0.24** | **0.24** |
| 25 MB | **0.40** | **0.22** | not run |
| 50 MB | **0.29** | **0.18** | not run |

### Tail (p99), same ratio. **Above 1.00 FastCache is worse.**

| payload | 1 client | 8 clients | 32 clients |
|---|---:|---:|---:|
| 256 KB | **2.27** | **2.10** | **1.94** |
| 1 MB | 1.02 tie | **1.09** | **1.30** |
| 10 MB | 0.61 | **0.20** | **0.23** |
| 25 MB | 0.47 | **0.18** | not run |
| 50 MB | 0.36 | **0.15** | not run |

Two separate effects, and they do not agree:

- **Concurrency moves the median crossover downward.** Single-client, FastCache needs ≥10 MB to win. At 8
  clients it wins from 1 MB. At 32 clients it wins everywhere tested, including 256 KB.
- **Concurrency does not fix the tail.** At 256 KB FastCache's p99 is roughly twice Redis's at *every*
  concurrency level. At 1 MB / 32 clients FastCache is 3.8× faster on median and **30% worse on p99**.

A service that cares about p99 more than median, on payloads under a few MB, should read this table as a
reason not to adopt FastCache — regardless of how good the median looks.

*32 clients was not run at 25 MB and 50 MB: 32 × 50 MB in flight is ~1.6 GB of transient arrays on a 3 GB
benchmark heap, which measures an allocation cliff rather than either cache. The omission is deliberate
and applies identically to all arms.*

---

## 11. Zero-copy analysis

### What the code does

**FastCache read path** — `ClientSession.java:177-189`:

```java
ByteBuffer payload = lease.readOnlyView();
responseHeader.clear();
// ... 8-byte header ...
writeFully(new ByteBuffer[]{responseHeader, payload});   // gathering write
```

The payload goes from the off-heap slot to the socket in a single gathering `channel.write`. It is never
copied onto the Java heap and never staged in an intermediate buffer.

**FastCache write path** — `ClientSession.java:201-218`:

```java
ShardedStorageEngine.WriteTicket ticket = engine.beginWrite(valueLength);
ByteBuffer slot = ticket.slot();
slot.clear();
readFully(slot);                       // socket -> off-heap slot, directly
engine.commitWrite(key, ticket, flags, ttlMillis, sourceCharacters);
```

The payload is read from the socket **directly into the off-heap slot** that will hold it. Again no
intermediate heap buffer.

So FastCache's "zero-copy both directions" claim is accurate as a description of its own code. That is a
statement about FastCache, not yet a statement about why it is faster than Redis.

**Redis read path**: `addReplyBulk` appends the value into the client's output buffer (`c->buf`, or the
reply list for large values) before `writeToClient` sends it — one full-payload copy per GET.

**Redis write path**: Redis 7 has a large-argument optimisation (`PROTO_MBULK_BIG_ARG`) that reads a big
bulk argument into a right-sized buffer to avoid a second copy, so the write path is *closer* to
zero-copy than the read path.

### What that predicts, and how it is tested

If an extra per-byte copy is the mechanism, the FastCache/Redis latency ratio should **fall as payload
size rises** and the advantage should **shrink or vanish for small payloads**. The GET tables in §7 are
the test. A counter-prediction worth noting: FastCache allocates its slot with
`ByteBuffer.allocateDirect`, which **zeroes** the memory — a full-payload `memset` per write that Redis
does not pay. On the write path FastCache therefore carries an extra per-byte cost of its own.

Distinguishing, as required:

- **Observed** — the measured ratios in §7–§9.
- **Likely mechanism** — for GET, an extra full-payload copy in Redis's reply path. Supported by code and
  by the scaling shape.
- **Proven mechanism** — none. Proving it would require profiling both servers (`perf` on Redis,
  async-profiler on the sidecar) and attributing time to `memcpy`. That was not done and is named as the
  next experiment in §24.

---

## 12. End-to-end JVM results

**Not run as a dedicated request-serving application in this round.** The nearest available evidence is
the previous round's Spring `CacheManager` benchmark (§14), which drives the real
`Cache.get(key, Callable)` path with a 50 ms simulated computation — but that measured Caffeine against
embedded FastCache, not against the sidecar or Redis.

What can be said from the microbenchmarks, and what cannot:

- A 10 MB cache hit costs **13.5 ms** through the FastCache sidecar and **57.4 ms** through Redis at 8
  concurrent clients. Against a loader that costs *L*, end-to-end request latency is roughly `hit` on a
  hit and `L + write` on a miss.
- For the sidecar to be within 10% of Caffeine end-to-end, *L* must exceed roughly 135 ms at 10 MB. For it
  to beat Redis end-to-end, no threshold is needed — it is faster at every *L*.
- **What is missing** is the interaction: request concurrency against a real thread pool, connection-pool
  contention under burst, and the GC behaviour of an application allocating a 10 MB array per hit. The
  sidecar allocates a fresh `byte[]` on every read, so a high-hit-rate service pays continuous
  young-generation churn that a microbenchmark under-represents.

This is the most important gap in the current evidence and is named first in §24.

---

## 13. GC / memory results

Carried forward from the previous round (Windows, `-Xmx4g`, G1). Not re-measured on Linux.

| Arm | App heap at 512 MB resident | Total GC pause, 90 s churn @ 1 MB | p99 GC pause |
|---|---:|---:|---:|
| caffeine | 515–1024 MB | 8 322 / 8 453 ms | 6 000 / 8 000 µs |
| fastcache-embedded | 515–1025 MB | **18 408 / 20 200 ms** | **26 000 / 27 000 µs** |
| fastcache-sidecar | **0 MB** | **1 000 / 976 ms** | 16 000 / 20 000 µs |

Two things in that table matter more than the headline:

- **Embedded FastCache is worse than Caffeine on GC** — 2.2–2.4× more total pause, 3–4× worse tail — while
  allocating less. On the Spring path FastCache degrades GC rather than improving it.
- The sidecar's total pause is 8.5× lower but its **individual** pauses are longer: ~15× fewer
  collections, each with more to do.

An incidental result that favours off-heap storage generally: G1 humongous-region rounding wastes up to
**100%** of the heap for payloads just above half a region. At 1 MB payloads in a 4 GB heap, 512 MB of
cached data occupied 1024 MB of heap. Predicted-versus-measured agreed at all seven payload sizes. This
penalises Caffeine and embedded FastCache identically and is avoidable for free with
`-XX:G1HeapRegionSize`.

---

## 14. Stampede results

From the previous round, through Spring's real `CacheManager` (`Cache.get(key, Callable)` — the exact
call `@Cacheable(sync = true)` makes), loader cost 50 ms, 3 repeats:

**60 of 60 cells collapsed the stampede to exactly one loader execution**, for both Caffeine and
FastCache, at 100 and 500 concurrent callers, at 1/5/10/25/50 MB, with every caller receiving a correct
value.

FastCache's p99 was **lower than Caffeine's in 9 of 10 cells** (e.g. 5 MB/500: 66.90 ms against 86.75 ms;
10 MB/100: 75.27 ms against 107.03 ms). Median latency was a wash — mean p50 ratio 0.990.

**Redis was not measured in this test, and the reason is not an oversight.** Redis has no built-in request
coalescing: N concurrent misses produce N loader executions unless the application adds its own lock. The
honest comparison is therefore "Redis plus about ten lines of application locking", which is not a
capability gap so much as a convenience. Reporting a stampede table with Redis at N executions would
overstate the difference.

---

## 15. MemoryGuard results

From the previous round, on a host at 89.7% physical memory:

| Configured budget | Usable before first refusal | % of budget honoured |
|---:|---:|---:|
| 128 MB | 64 MB | 50.0% |
| 512 MB | 64 MB | 12.5% |
| 1024 MB | 64 MB | **6.3%** |
| 4096 MB | 208 MB | **5.1%** |

The first refusal landed at exactly the machine-pressure gate floor — `max(64 MiB, 5% of budget)` — in
every configuration. The configured budget never participated in the decision.

A second, sharper defect sits behind it: `EvictionSweeper.sweepShard` sheds `max(1, size × 0.10)` entries
**per shard per pass** while the guard is rejecting. The `max(1, …)` is a floor, so with 32 shards and a
large-value cache holding fewer entries than shards, **a single pressure pass can evict essentially the
whole cache**. Observed directly (writes accepted, `entries` reading 0 moments later at 25 MB and 50 MB)
and reproduced in a controlled drain test with a 10-minute TTL and no writer: 25 of 52 entries gone within
5 seconds.

Classification: **design flaw, not a bug.** The code does what it intends; the intent produces a capacity
model that cannot be planned against. It is acceptable only where the host has generous free memory and
the operator does not rely on the configured budget meaning anything.

---

## 16. Long-run results

**Not run at 1–4 hours.** The strongest available evidence remains the previous round's leak probe: eight
targeted probes, one per release path, 4 MB payloads × 500 cycles each (2 GB of allocate/free churn per
probe against a 64 MB threshold). **8 of 8 clean** — slots returned to baseline, RSS flat within 3 MB.
The concurrent probe raced 6 769 627 reference-counted leased reads against an evictor, touching the
buffer on every read, with no crash and no outstanding slots.

That rules out per-operation leaks with high confidence. It does **not** rule out slow native-arena
fragmentation over hours, which is precisely what a 1–4 hour run exists to find. Recorded as a limitation,
not as a pass.

---

## 17. Failure / recovery results

**Not run.** No failure-injection testing was performed in this round. The previous round's leak probe
covers two adjacent cases as a side effect — failed loads (500 loader exceptions, leases released, no slot
leak) and rejected writes (493 of 500 refused, no slot leak) — but crash, restart, stale-lock, stale-socket,
malformed-request, oversized-payload and reconnect behaviour are all unmeasured. Listed in §24.

---

## 18. FastCache vs Caffeine

Not close, and not close in a way no amount of protocol work will change.

| payload | caffeine p50 | fastcache-sidecar p50 (8 clients) | factor |
|---|---:|---:|---:|
| 256 KB | 2 µs | 727 µs | ~360× |
| 1 MB | 1 µs | 1 620 µs | ~1 600× |
| 10 MB | 1 µs | 13 501 µs | ~13 500× |
| 50 MB | 1 µs | 65 160 µs | ~65 000× |

Caffeine returns a reference; nothing is copied and nothing crosses a socket. Its "MB/s" is not a transfer
rate and is omitted rather than reported as a misleading number.

**Caffeine wins on every latency axis and loses on exactly two:** it cannot share a cache between
processes, and it holds payloads on the JVM heap (previous round: 515–1024 MB of heap for 512 MB of
cached data, against 0 MB for the sidecar). Choosing FastCache over Caffeine is a decision to trade three
orders of magnitude of hit latency for cross-process sharing and heap relief. That trade only makes sense
where the cached computation is expensive enough to dwarf a 13 ms read — which is the §21 argument.

---

## 19. FastCache vs Redis

### Where FastCache wins

- **Large-value throughput and latency.** At 8 clients: 0.24× at 10 MB, 0.22× at 25 MB, 0.18× at 50 MB on
  median; 4.5×, 5.2× and 5.6× the operations per second. Non-overlapping ranges across 3 runs.
- **Bandwidth stability.** 617–767 MB/s flat from 1 MB to 50 MB, against Redis falling 418 → 142 MB/s.
- **Write latency under read-heavy load.** 19 ms against 270 ms at 10 MB, 90/10 mix.
- **Large-value tail.** p99 0.15×–0.23× at 10–50 MB.
- **Single-flight**, which Redis has no equivalent for — though an application lock is ten lines (§14).

### Where Redis wins

- **Small payloads.** 256 KB and 1 MB single-client: Redis is 17–20% faster on median.
- **Tail latency below 10 MB, at every concurrency level.** p99 1.94×–2.27× better at 256 KB, and better
  at 1 MB under concurrency.
- **Small writes.** 1 MB SET: Redis 1 743 µs against FastCache 2 192 µs.
- **Everything not measured here**: durability, replication, failover, auth, cluster, tooling, operational
  knowledge, and a client ecosystem. None of it was tested; all of it is real.

### Platform comparison — what Linux changed

| payload | Windows (Memurai 7.2.5) | Linux (native 7.0.15) | change |
|---|---:|---:|---|
| 256 KB | 0.47 | 0.99 | **advantage disappeared** |
| 1 MB | 0.38 | 0.68 | **advantage roughly halved** |
| 10 MB | 0.26 | 0.24 | unchanged |
| 25 MB | 0.27 | 0.22 | unchanged |

*(8 concurrent clients, both platforms. Hardware differs, so only within-platform ratios are compared.)*

**Native Redis on Linux is substantially better than the Windows port at small payloads, and
indistinguishable at large ones.** The earlier Windows measurement overstated FastCache's advantage below
~1 MB and was accurate above ~10 MB. Running this on Linux was necessary, and it changed the answer.

---

## 20. Limitations

Ordered by how much each one should restrain a conclusion.

1. **No end-to-end application benchmark.** Everything here is a microbenchmark of `get`/`put`. The
   product claim is about what happens to a real JVM service, and that was not measured (§12).
2. **The sidecar allocates a payload-sized `byte[]` on every read.** At 10 MB and a high hit rate that is
   continuous young-gen churn charged to the application. The microbenchmark measures the allocation but
   not its downstream GC cost in a service with a real object graph.
3. **4 vCPU runner.** GitHub's `ubuntu-latest` is 4 cores. FastCache's thread-per-connection model and
   Redis's single event loop will both behave differently at 16 or 64 cores, and in opposite directions:
   more cores help FastCache's model and do nothing for Redis's. The concurrency-32 results in particular
   are run on 4 cores and should not be extrapolated.
4. **Shared CI hardware.** Azure-hosted runners have noisy neighbours. Run-to-run spread was tight
   (e.g. 50 MB: [64 072–66 891] µs), which argues against significant interference, but it is not a
   dedicated machine.
5. **Redis 7.0.15 versus Memurai's 7.2.5.** The Linux Redis is two minor versions older than the Windows
   comparison. Reply-path changes between 7.0 and 7.2 are not accounted for.
6. **Hand-rolled RESP2 client.** Not Jedis or Lettuce. Deliberate (§5), and biased in Redis's favour, but
   it means these are not numbers a Lettuce-based application would necessarily see.
7. **Payloads are `byte[]`.** A real Spring value is an object graph needing serialization for either
   system. Generous to both, equally.
8. **90/10 mix has ~40 SET samples per run.** The head-of-line-blocking result is directionally strong and
   thinly sampled; the 50/50 mix corroborates the direction with far more samples.
9. **Server-side GC not instrumented.** The FastCache sidecar is a JVM with a collector; Redis has none.
   Only the application JVM was measured.
10. **No long run, no failure injection** (§16, §17).
11. **`--reject-ratio 1.0` for the sidecar.** Admission control was disabled so it could not confound
    latency. With the shipped default, §15 shows usable capacity collapsing to `max(64 MiB, 5% of budget)`
    on a loaded host. **The latency numbers here describe a configuration that the product does not ship.**

---

## 21. Product implications

**The thesis survives, but only in a narrower form than written**, and the narrowing is specific enough
to be actionable.

The thesis says "lower-overhead cross-process caching than a traditional Redis-style cache", without
qualification. The evidence supports: *lower-overhead cross-process caching for values of roughly 1 MB and
above, widening to 4–6× by 10 MB, provided the consumer cares about median and throughput more than tail
latency below 10 MB.* Below ~1 MB single-client, Redis is faster, and FastCache's p99 is about twice
Redis's at 256 KB at every concurrency level tested.

Three implications follow.

**The product is a large-value cache or it is nothing.** At 256 KB it is at best a tie and at worst a
2.3× tail regression. Every piece of positioning, documentation and default configuration should say
"multi-megabyte values" and should stop implying general-purpose caching. The README's current framing
around 50 MB context windows is, for once, aimed at the right place.

**The competitor is Redis, not Caffeine, and only for a specific shape of workload.** Caffeine is 360×
to 65 000× faster in-process and is the correct choice whenever a single process can hold the data. The
sidecar's reason to exist is cross-process sharing plus heap relief — and against Redis, which also
provides both, FastCache's case rests entirely on payload size.

**The capacity model currently disqualifies the configuration these numbers describe.** Every latency
figure was measured with admission control disabled. Shipped, `MemoryGuard` delivers 5–6% of a configured
budget on a loaded host and a single pressure sweep can empty a large-value cache (§15). A large-value
cache that cannot hold large values on a busy machine has no product, whatever its latency.

---

## 22. Claims we can make

| Claim | Evidence | Confidence | Safe to publish? |
|---|---|---|---|
| **Faster than Redis for values ≥10 MB** | p50 0.18×–0.40×, 3 runs, non-overlapping ranges, both platforms, both io-threads configs | **High** | **Yes**, with the size qualifier stated in the same sentence |
| **Faster than Redis for ≥1 MB at concurrency ≥8** | 0.68× at 1 MB / 8 clients, 0.26× at 32 | Medium-high | Yes, with both qualifiers |
| **Sustains bandwidth as payloads grow** | 617→767 MB/s flat vs Redis 418→142 | High | Yes |
| **Lower write latency under read-heavy large-value load** | 19 ms vs 270 ms at 10 MB, 90/10 | Medium (thin SET sampling) | Yes, with the sampling caveat |
| **Zero-copy transport in both directions** | `ClientSession:189` gathering write; `:213` socket→slot read | High (code-verified) | **Yes as a design description** — not as a performance claim on its own |
| **Off-heap: removes payloads from the application heap** | 0 MB attributable heap vs 510–1024 MB | High | Yes |
| **Cross-process sharing** | byte-exact reads from a separate JVM, both providers | High | Yes — but Redis does this too, so it is not a differentiator |
| **Single-flight collapses stampedes** | 60/60 cells, 1 loader execution at 100 and 500 callers | High | Yes — noting Redis needs ~10 lines of app locking to match |
| **No native memory leak on per-operation paths** | 8/8 probes clean, 2 GB churn each, 6.77M raced leased reads | High for per-op; **not** for long-horizon | Yes, scoped to "per-operation paths, 500-cycle probes" |

---

## 23. Claims we cannot make

| Claim | Why not |
|---|---|
| **"Faster than Redis"** *(unqualified)* | False below ~1 MB: Redis is 17–20% faster single-client, and FastCache's p99 is 1.94×–2.27× worse at 256 KB at every concurrency. |
| **"Lower tail latency than Redis"** | Only above 10 MB. Below it, FastCache's p99 is consistently worse — including where its median is 3.8× better. |
| **"Lower JVM GC pressure than Caffeine"** *(for the Spring integration)* | The opposite is measured: embedded FastCache produced 2.2–2.4× more total GC pause and 3–4× worse p95/p99 than Caffeine at 1 MB. Only the **sidecar** reduces heap. |
| **"Better for AI workloads"** | No AI workload was benchmarked. Payload size is not a workload. |
| **"No Redis required"** | True as a statement of fact, but the comparison shows Redis is the better choice below ~1 MB, so this reads as a recommendation the evidence does not support. |
| **"Production ready"** | §15: usable capacity is 5–6% of the configured budget on a loaded host and one pressure sweep can empty a large-value cache. No long-run, no failure-injection, no container/cgroup testing. |
| **"Zero-copy makes it faster"** *(as proven causation)* | The mechanism is code-verified and the scaling shape matches, but no profiler attributed time to `memcpy` in either server. Likely, not proven (§11). |
| **Any claim from the microbenchmarks about application behaviour** | No end-to-end service was measured (§12). |

---

## 24. Recommended next experiment

**The end-to-end JVM application benchmark (§12), at 1 MB and 10 MB, against all three backends.**

It is the highest-value remaining unknown because it is the only one that can overturn the product
conclusion rather than refine it. Everything measured so far says a 10 MB cache hit is 4× cheaper through
FastCache than through Redis. What is not known is whether that survives contact with a real service —
specifically whether the payload-sized `byte[]` the sidecar allocates on every read reintroduces, as GC
cost inside the application, the heap pressure the off-heap design was adopted to remove. If it does, the
central argument for the sidecar is circular and the product thesis fails on its own terms.

Shape: a JVM HTTP service, `@Cacheable`-equivalent path, 1 MB and 10 MB responses, realistic hit rates
(80–95%), sustained load, measuring request p50/p95/p99, throughput, application heap, RSS, GC count and
total pause — for Caffeine, FastCache sidecar and Redis. One day of work on the existing harness.

Ranked after it:

2. **Fix and re-measure `MemoryGuard`** (§15). The latency numbers describe a configuration the product
   does not ship. Until the shipped default can hold a large-value working set, none of this is usable.
3. **Profile both servers** to convert the zero-copy mechanism from likely to proven (§11): `perf` on
   Redis, async-profiler on the sidecar, attributing time to payload copying.
4. **Long run (1–4 h) and failure injection** (§16, §17).
5. **Repeat on a dedicated 16+ core machine.** The 4-vCPU runner constrains exactly the axis — concurrency
   scaling — on which FastCache's model differs most from Redis's.

---

## 25. Go / No-Go

**Conditional Go, scoped to large values, and gated on the capacity model.**

Stated as the evidence requires rather than as a verdict:

> Under the tested Linux environment (Ubuntu 24.04, native Redis 7.0.15, 4 vCPU), FastCache demonstrated
> a **1.5×–5.6× throughput and 0.18×–0.40× median-latency advantage over Redis for values of 10 MB and
> above**, holding bandwidth flat at 617–767 MB/s where Redis degraded to 142 MB/s, and reducing write
> latency under read-heavy load from 270 ms to 19 ms — **while losing to Redis on median below 1 MB
> single-client, losing on p99 below 10 MB at every concurrency level tested, and remaining 360×–65 000×
> slower than Caffeine for anything a single process can hold.**

What that supports:

- **Go** on continued investment in the **sidecar** as a large-value cross-process cache tier, positioned
  explicitly at multi-megabyte payloads.
- **No-Go** on any general-purpose caching positioning. Below ~1 MB the evidence actively recommends
  Redis.
- **No-Go** on the embedded/Spring path as a performance story. It stores on the heap, and it is *worse*
  than Caffeine on GC.
- **Blocking condition:** the capacity model (§15) must be fixed before any of this is usable in
  production, because every latency number here was measured with admission control disabled.
- **Blocking unknown:** the end-to-end application benchmark (§24). If per-read allocation reintroduces
  the GC cost off-heap storage was meant to remove, the thesis fails on its own terms and this becomes a
  No-Go.

The honest summary is that FastCache has found a real and defensible technical niche that is considerably
narrower than its documentation claims, and that it is not yet production-ready in the configuration that
produces its good numbers.
