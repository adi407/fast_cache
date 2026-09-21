# FastCache — thesis reassessment

**Status: product/architecture review. No new measurements were taken for this document, and the
end-to-end matrix is stopped.** Every number below is carried from an earlier run and is labelled with
its validity classification. Where a number does not exist, this document says so rather than estimating
one.

Sources consolidated here:

| # | Source | Document |
|---|---|---|
| 1 | Python demo workload / soak | [`README.md`](../../README.md) §Measured behaviour, [`examples/soak-samples.csv`](../../examples/soak-samples.csv) |
| 2 | Caffeine vs FastCache, JVM memory & GC | [`docs/benchmarks/JVM_MEMORY_GC_RESULTS.md`](../benchmarks/JVM_MEMORY_GC_RESULTS.md) |
| 3 | Windows / Memurai Redis head-to-head | same document, §Redis head-to-head |
| 4 | Native Linux Redis | [`native-linux-redis-validation.md`](native-linux-redis-validation.md) |
| 5 | End-to-end JVM service matrix | [`end-to-end-jvm-results.md`](end-to-end-jvm-results.md), [`data/e2e-run1/`](data/e2e-run1/), [`data/e2e/`](data/e2e/) |
| 6 | MemoryGuard capacity analysis | [`memoryguard-production-analysis.md`](memoryguard-production-analysis.md) |
| 7 | Architecture / code inspection | this document, §3 and §8; verified against the working tree |

---

## Executive summary

Four rounds of measurement converge on one sentence:

> **FastCache has one differentiator against Redis — sustained bandwidth on multi-megabyte values under
> concurrency — and that differentiator lives in an architecture the product does not currently offer to
> JVM users, in a configuration the product does not ship, and it has never been validated end-to-end
> above 1 MB.**

Each clause is load-bearing:

- **One differentiator.** Off-heap storage, cross-process access, TTL and GET/SET are all matched by
  Redis. Single-flight is matched by ten lines of application code. What Redis does *not* match is
  bandwidth: at 8 concurrent clients Redis falls from 418 MB/s at 1 MB to 142 MB/s at 50 MB, while
  FastCache holds 617–767 MB/s flat. That is the whole product.
- **Not offered to JVM users.** There is no Java client for the sidecar protocol in the product. The
  Spring starter constructs an in-process engine; `fastcache.server.*` exists so **Python can read a JVM's
  cache**, not the reverse. Every favourable FastCache number on file was produced by `WireClient`, a
  ~200-line benchmark class in `fastcache-benchmarks`.
- **Not shipped.** Every favourable latency figure was measured with `--reject-ratio 1.0`. With the
  shipped default, a 1 GB budget yields 64 MB of usable cache on an ordinary host, and one pressure sweep
  can empty a large-value cache entirely.
- **Never validated end-to-end above 1 MB.** The one end-to-end cell that would have tested the sidecar at
  10 MB reported **55 941 corrupt reads out of 55 941** — it was transporting stale 1 MB payloads under
  10 MB labels. Redis was never measured end-to-end at 10 MB or 25 MB at all.

The end-to-end data that *is* valid, at 1 MB, is neither encouraging nor fatal. It shows the sidecar's
per-read allocation converting retained heap into GC pause — 26 MB of heap for 1 463 ms of GC pause,
against Caffeine's 1 056 MB for 174 ms — and it shows **Redis obtaining exactly the same heap relief for
less total GC pause**, losing only on throughput (759 vs 998 req/s, and on a platform where Redis is
known weak at 1 MB).

**Product decision: B — narrow product thesis, conditional.** Detail in §14. One experiment, named in
§13, decides whether it stays B or becomes C.

---

## What we originally believed

The thesis as written, carried unchanged through four rounds:

> FastCache is a high-performance local cache engine for large values, using off-heap storage and
> zero-copy transport to provide lower-overhead cross-process caching than a traditional Redis-style
> cache. One annotation in Java, one decorator in Python. No Redis, no Docker, no connection string.

Decomposed into the claims it actually makes:

| # | Belief | Audience it was pitched to |
|---|---|---|
| B1 | Off-heap storage removes cache payloads from the JVM heap | JVM / Spring |
| B2 | Removing payloads from the heap reduces GC pressure | JVM / Spring |
| B3 | Zero-copy transport makes cross-process reads cheap | both |
| B4 | Cheaper than Redis for cross-process caching | both |
| B5 | Single-flight prevents stampedes without an external lock | both |
| B6 | No Redis, no Docker, no connection string — it starts itself | both |
| B7 | "One annotation in Java" delivers all of the above | JVM / Spring |

---

## What the evidence shows

### B1 — off-heap storage removes payloads from the JVM heap

**True for the sidecar. Structurally false for the Spring path.**

Every Spring entry point calls `putReference`, which parks the caller's object on the Java heap and
reports `footprintBytes() == 0`. Confirmed four independent ways:

| Evidence | Result |
|---|---|
| Code reading (`FastCacheAdapter`, `FastCacheAspect`, `FastCacheManager`) | every put is `putReference` |
| Scenario A heap occupancy, 7 payload sizes | `fastcache-embedded` within **1%** of `caffeine` at every size |
| Spring `CacheManager` phase, 10 cells | heap identical: 2/2, 6/6, 12/12, 26/26, 52/52 MB |
| End-to-end service, 1 MB and 10 MB, valid cells | 1 056 vs 1 057 MB; 638 vs 638 MB |

The sidecar does deliver it: **0 MB attributable application heap** against 510–1 024 MB for Caffeine in
the microbenchmark, and 26 MB against 1 056 MB in the end-to-end service at 1 MB.

### B2 — removing payloads from the heap reduces GC pressure

**Not established, and in the configuration that matters — a real service — it is false against
Caffeine.**

Two measurements, pointing opposite ways, and the disagreement is the finding:

| Measurement | caffeine | fastcache-embedded | fastcache-sidecar | redis |
|---|---:|---:|---:|---:|
| Microbench, 90 s churn @ 1 MB — total GC pause | 8 322 ms | **18 408 ms** | **1 000 ms** | not run |
| Microbench — p99 GC pause | 6 000 µs | 26 000 µs | 16 000 µs | not run |
| **End-to-end service, 1 MB, 45 s — total GC pause** | **174 ms** | 176 ms | **1 463 ms** | **1 023 ms** |
| End-to-end service — GC collections | 14 | 14 | 327 | 253 |
| End-to-end service — bytes allocated | 4.15 GB | 3.60 GB | **96.0 GB** | **73.1 GB** |
| End-to-end service — settled heap | 1 056 MB | 1 057 MB | 26 MB | 26 MB |
| End-to-end service — throughput | 6 073 req/s | 5 401 req/s | 998 req/s | 759 req/s |

In the microbenchmark the sidecar looked 8.5× better on GC because Caffeine was *retaining* 512 MB–1 GB
with nothing else competing for the heap. In a 4 GB service heap that retention is affordable, and what
dominates instead is **allocation rate**: the sidecar allocates **2.14 MB per request** (a 1 MB encoded
`byte[]` plus the decoded object graph) against Caffeine's **15.2 KB** — 140×.

So the off-heap design does not remove heap pressure from a service that reads its values. It **relocates
it from retention to allocation**, and at a 99% hit rate that is a continuous cost rather than a
one-time one.

**One correction that matters for fairness.** Per request, the sidecar and Redis allocate *identically*
(2.14 MB each) and cost near-identical GC (32.6 µs vs 29.9 µs per request). The sidecar's larger *total*
GC pause follows from it serving 31% more requests, not from being less efficient. The honest statement
is not "FastCache has worse GC than Redis" — it is **"the off-heap design converts retained heap into
allocation rate, and Redis performs the same conversion at the same per-request price."**

### B3 — zero-copy transport makes cross-process reads cheap

**Accurate as a description of FastCache's own code. Unproven as the cause of the advantage. Not
end-to-end.** §6E classifies it by layer, which is the only form this claim may take from now on.

### B4 — cheaper than Redis for cross-process caching

**True above roughly 10 MB. False below roughly 1 MB. The crossover moves with concurrency.**

Native Linux, Redis 7.0.15, p50 ratio FastCache ÷ Redis (below 1.00 FastCache faster):

| payload | 1 client | 8 clients | 32 clients |
|---|---:|---:|---:|
| 256 KB | 1.17 Redis | 0.99 tie | **0.72** |
| 1 MB | 1.20 Redis | **0.68** | **0.26** |
| 10 MB | **0.35** | **0.24** | **0.24** |
| 25 MB | **0.40** | **0.22** | not run |
| 50 MB | **0.29** | **0.18** | not run |

p99 ratio, same orientation (above 1.00 FastCache worse):

| payload | 1 client | 8 clients | 32 clients |
|---|---:|---:|---:|
| 256 KB | **2.27** | **2.10** | **1.94** |
| 1 MB | 1.02 | **1.09** | **1.30** |
| 10 MB | 0.61 | **0.20** | **0.23** |
| 50 MB | 0.36 | **0.15** | not run |

The shape that matters more than any single ratio — **effective bandwidth at 8 concurrent clients**:

| payload | FastCache MB/s | Redis MB/s |
|---|---:|---:|
| 256 KB | 344 | 342 |
| 1 MB | 617 | 418 |
| 10 MB | 741 | 174 |
| 25 MB | 725 | 162 |
| 50 MB | 767 | 142 |

FastCache is flat; Redis degrades 2.9× from 1 MB to 50 MB. **This is the differentiator, and it is a
bandwidth story, not a latency story.** Redis's large-value ceiling under concurrency is ~150–175 MB/s.
FastCache's is ~750 MB/s. Framing it as latency invites the reply "270 ms is fine"; framing it as
bandwidth identifies the workloads where Redis simply cannot keep up.

Two further Redis failure modes, both reproduced:

- **`io-threads` does not rescue it.** `io-threads 4, do-reads yes` moved the 10 MB ratio 0.24 → 0.24 and
  25 MB 0.22 → 0.21. Redis's I/O threads parallelise socket syscalls, not the reply-buffer copy.
- **Head-of-line blocking on writes.** 90% reads / 10% writes at 10 MB: Redis SET p50 **270 ms**, p99
  **742 ms** (25 MB: 592 ms / 1 877 ms) against FastCache's 19 ms / 31 ms. Thinly sampled (~120 SET
  samples across 3 runs); the 50/50 mix corroborates the direction with ~600 samples at 0.23–0.24×.

### B5 — single-flight prevents stampedes without an external lock

**True, correct, and not a differentiator.** 60 of 60 Spring `CacheManager` cells collapsed 100 and 500
concurrent callers to exactly one loader execution, for both Caffeine and FastCache, at 1/5/10/25/50 MB,
with every caller receiving a correct value. FastCache's p99 was lower than Caffeine's in 9 of 10 cells
(5 MB/500: 66.90 ms vs 86.75 ms; 10 MB/100: 75.27 ms vs 107.03 ms). See §6D for why this does not
survive as a product claim.

### B6 — no Redis, no Docker, no connection string

**True, and the only claim never contradicted by any round.** Cold JVM boot on first Python cache call:
1.85 s, fully automatic. `kill -9` the parent and the JVM exits and releases its memory. This is an
operational property, not a performance property, and §6 weighs it as such.

### B7 — "one annotation in Java" delivers all of the above

**False, and this is the finding that reframes the whole programme.** See §3 and §8.

---

## What was wrong

Ordered by how much each error cost.

**1. We conflated three architectures under one name.** "FastCache" has been discussed as though the
Spring annotation and the sidecar were two configurations of one product. They are not. They are two
products for two audiences, and only one of them is off-heap. §3 separates them.

**2. We treated "off-heap" as the differentiator.** Redis is also off the JVM heap. Measured end-to-end at
1 MB, Redis delivered *identical* heap relief (26 MB vs 26 MB) for *less* total GC pause than the
FastCache sidecar. "Off-heap" distinguishes FastCache from Caffeine, not from Redis — and against
Caffeine it costs three orders of magnitude of hit latency.

**3. We generalised "zero-copy" across four different layers.** Storage and transport are genuinely
copy-free; client access and application response are not, and on a servlet stack cannot be. §6E.

**4. We read a microbenchmark GC result as a service GC result.** The microbenchmark said the sidecar cut
total GC pause 8.5× versus Caffeine. The service said the opposite, by 8.4×, at the same payload size.
Both are correct measurements of different things; only one describes a product.

**5. We kept benchmarking after the decisive question had changed.** The matrix still had 10 cells left
when §3a of the end-to-end document established that no Java client exists. Those 10 cells measure an
architecture nobody can adopt.

**6. Harness errors twice produced numbers that would have been published.** Catalogued in §11. The more
dangerous of the two is the stale-payload case, because it made FastCache look *faster*.

---

## What survived

Stated as narrowly as the evidence permits. Everything here is safe to repeat.

| Surviving claim | Evidence | Confidence |
|---|---|---|
| FastCache sustains 617–767 MB/s flat from 1 MB to 50 MB where Redis falls 418 → 142 MB/s | Linux, 3 runs, non-overlapping ranges, 2 io-thread configs | **High** |
| FastCache p50 is 0.18×–0.40× of Redis for values ≥10 MB | same | **High** |
| Redis's write latency collapses under read-heavy large-value load (270 ms vs 19 ms at 10 MB) | Linux, 90/10 mix | Medium (thin SET sampling) |
| The sidecar removes cache payloads from the application heap | 0 MB vs 510–1 024 MB microbench; 26 MB vs 1 056 MB end-to-end | **High** |
| Redis removes them equally well, at equal per-request GC cost | 26 MB vs 26 MB; 29.9 vs 32.6 µs GC/req, same cell | **High** |
| The Spring path stores on the heap and is not off-heap in any sense | 4 independent confirmations | **High** |
| Storage and transport are copy-free inside the engine | `ClientSession.java:177-218`, code-verified | **High** as design, not as cause |
| Single-flight collapses stampedes to exactly one loader execution | 60/60 cells | **High** |
| No native memory leak on per-operation paths | 8/8 probes, 2 GB churn each, 6.77M raced leased reads | High for per-op only |
| Caffeine is 360×–65 000× faster than any cross-process option | Linux, all payload sizes | **High** |
| `MemoryGuard` delivers 5–6% of a configured budget on an ordinary host | 4 budget configurations, gate floor hit in every one | **High** |

---

## Three FastCache architectures

The single most important correction in this document. These are not three configurations of one product.
They have different audiences, different storage, different performance and different evidence.

### Product A — embedded JVM FastCache

```
JVM application
    ↓
FastCache engine
    ↓
same JVM heap          ← putReference, footprintBytes() == 0
```

What ships today as `fastcache-spring-boot-starter`: `@FastCache`, `@FastCachePut`, `@FastCacheEvict`,
`FastCacheManager` (Spring `Cache`/`CacheManager`), `@FastCacheScan`.

**Evidence, all valid cells:**

| Metric | caffeine | fastcache-embedded | verdict |
|---|---:|---:|---|
| Heap, 7 payload sizes (microbench) | baseline | within 1% | parity |
| Heap, Spring `CacheManager`, 10 cells | 2/6/12/26/52 MB | identical | parity |
| Heap, end-to-end 1 MB / 10 MB | 1 056 / 638 MB | 1 057 / 638 MB | parity |
| GC total pause, end-to-end 1 MB | 174 ms | 176 ms | parity |
| GC total pause, end-to-end 10 MB | 182 ms | 158 ms | parity (slightly better) |
| GC total pause, microbench churn 1 MB | 8 322 ms | 18 408 ms | **2.2× worse** |
| GC p99 pause, microbench churn | 6 000 µs | 26 000 µs | **4.3× worse** |
| Throughput, end-to-end 1 MB | 6 073 req/s | 5 401 req/s | **11% worse** |
| Throughput, end-to-end 10 MB | 5 192 req/s | 5 351 req/s | 3% better |
| Hit p50, end-to-end 1 MB | 7.9 µs | 10.4 µs | 32% worse |
| Stampede p99, 10 Spring cells | baseline | **lower in 9 of 10** | **better** |

**Does this product have a reason to exist?**

As a *cache*: **no.** It is Caffeine with equal heap, equal-or-worse GC, worse hit latency, an extra
dependency, and a capacity bound (`maxEntriesPerShard`) that must be sized against murmur3 hash skew
across 32 shards or it degrades silently into a miss storm. Nothing in the evidence would persuade a team
already using Caffeine to switch, and nothing would persuade a team using neither to pick A over Caffeine.

As *infrastructure*: **yes, one reason.** With `fastcache.server.enabled=true`, Product A is the **server
side of Product C** — the thing that lets a Python process read a JVM's cache. That is its actual job.
Its two genuine features over Caffeine, better stampede tail latency and `@FastCacheScan`, are real but
neither is worth a dependency on its own.

**Recommendation: stop positioning A as a performance product.** Position it as "the embeddable engine
that also serves your Python workers", or retire it as a standalone story.

### Product B — JVM application → FastCache sidecar

```
JVM application
      ↓
    socket            ← no Java client exists in the product
      ↓
FastCache process
      ↓
off-heap storage
```

**This is the architecture producing every interesting benchmark number in the file, and it is not a JVM
product capability today.**

Stated without hedging, and verified against the working tree for this document:

1. The only file under any `src/main` that opens a socket to the engine: **none**.
   `fastcache-engine/.../net/FastCacheServer.java` *accepts* connections (`ServerSocketChannel.accept`).
   Nothing in the engine or the starter calls `new Socket(...)` or `SocketChannel.open(...)`.
2. `FastCacheAutoConfiguration` constructs a `ShardedStorageEngine` directly. The Spring integration is
   in-process by construction; it has no remote mode and no place to put one.
3. `FastCacheProperties.Server` does expose host and port — and its own field comment says what for:
   *"Off by default: an in-process cache should not open a port unless asked."* The Javadoc on the class
   is explicit that the listener exists so Python processes can share the JVM's cache. **The arrow points
   outward, not inward.**
4. `WireClient` — the class every sidecar measurement in this repository used — lives in
   `fastcache-benchmarks/src/main/java/io/fastcache/bench/WireClient.java`. It is benchmark code. It is
   not published, not tested as a product surface, has no pooling policy, no reconnection, no failure
   semantics and no Spring integration.

**Do not pretend the current Spring integration provides this.** It does not. A JVM team adopting
FastCache today gets Product A and its heap, not Product B and its bandwidth.

Evidence for B is therefore entirely *microbenchmark* evidence plus **one valid end-to-end cell at 1 MB**
and **zero valid end-to-end cells above 1 MB**. §8 sets out what building it would require; §9 asks
whether it should be built.

### Product C — Python → FastCache

```
Python application
      ↓
FastCache client       ← ships, tested, 70 integration tests
      ↓
FastCache JVM engine
      ↓
off-heap storage
```

**This is the only architecture where the sidecar capability is actually reachable by a user today**, and
it is the one that has received the least measurement.

What it genuinely has:

- A shipped, tested client (`python/fastcache_ai/client.py`, 70 integration tests across 3.10–3.13).
- **The only reachable single-flight**: `OP_REFRESH_LEASE` / `OP_REFRESH_DONE` are imported and used by
  the Python client and by no Java code.
- Self-starting sidecar: 1.85 s cold boot, no Docker, no connection string, orphan-isolated
  (`kill -9` the parent → JVM exits and frees its memory).
- A real structural problem it solves that Caffeine cannot: a Gunicorn/uvicorn deployment with N workers
  otherwise caches large values **N times**, once per worker process. A sidecar caches once.

What weakens it:

- **CPython has no stop-the-world collector**, so the "heap relief / GC pause" argument — the entire
  motivation for off-heap on the JVM — does not transfer. The Python case rests on *duplication across
  workers* and *process-shared capacity*, not on GC.
- The client still materialises: `recv_into` over a preallocated `memoryview`, then `bytes(buffer)`, then
  deserialisation. One copy plus a full object graph. Not zero-copy at the client layer (§6E).
- **It has never been benchmarked against Redis.** The only published Python figures are a 20 MB round
  trip (put 36 ms, get 33 ms) and a synthetic, deliberately cache-friendly demo workload with a 99.3% hit
  rate. `redis-py` under the same workload is unmeasured. Product C's competitive position is *unknown*,
  not favourable.
- Requires a JDK 21+ on the host of a Python application. For many Python teams this is a hard
  no regardless of the numbers, and it is not a cost Redis imposes.

**Verdict on C: the most reachable product and the least evidenced one.** Its differentiator is not the
same as B's. B's is bandwidth; C's is "one shared large-value cache across N worker processes with no
Redis to operate" — an operational claim that has never been tested against the obvious alternative.

---

## Competitive comparison

> **What does FastCache provide that Redis cannot provide with ordinary application code?**

| Capability | FastCache | Redis | Caffeine | Material advantage? |
|---|---|---|---|---|
| **Off-heap / out-of-JVM-heap storage** | Yes (sidecar only) | Yes | No | **No.** Measured identical end-to-end: 26 MB vs 26 MB application heap, at equal per-request GC cost. |
| **Cross-process sharing** | Yes (sidecar only) | Yes | No | **No.** Redis has done this for 15 years with better tooling. |
| **TTL** | Yes | Yes | Yes | **No.** |
| **GET / SET** | Yes | Yes | Yes | **No.** Redis is *faster* below ~1 MB and better on p99 below ~10 MB. |
| **Single-flight / stampede collapse** | Yes, host-wide, in protocol | No — needs ~10 lines of app locking | Yes, in-process | **No.** See §6D. Real, but not worth a migration, and unreachable from Java. |
| **Sustained bandwidth on values ≥10 MB** | 725–767 MB/s flat | 142–174 MB/s, degrading | n/a (no transfer) | **YES.** 4–5×, reproduced on two platforms, two io-thread configs, 3 runs each, non-overlapping. This is the only yes. |
| **Write latency under read-heavy large-value load** | 19 ms p50 @ 10 MB | 270 ms p50 @ 10 MB | n/a | **Yes, same mechanism as above** — Redis's single event loop head-of-line-blocks. Thinly sampled; counts as one finding with the row above, not two. |
| **Zero-install / self-managing process** | Yes (Python path) | No (Docker/apt/managed service) | n/a (no process) | **Situational.** Material for a Python dev on one machine; immaterial to any team that already runs Redis. Never tested against `redis-py` + a container. |
| **In-process hit latency** | 10.4 µs (embedded) | n/a | 7.9 µs | **No** — Caffeine wins. |
| **Durability / replication / failover / auth / cluster** | None | All | n/a | **Redis advantage, unmeasured and real.** |
| **Client ecosystem, tooling, ops knowledge** | Two clients, one of them benchmark code | Universal | Universal | **Redis advantage.** |
| **Key scanning** | `OP_SCAN`, `@FastCacheScan` | `SCAN` | `asMap()` | **No.** |
| **Pipelining / multi-get** | **No** — strict request/response per connection | Yes (`MGET`, pipelining) | n/a | **Redis advantage.** Concurrency must be bought with sockets. |

**The answer to the section's question is: one thing.** Sustained bandwidth on multi-megabyte values
under concurrency, and the write-latency behaviour that shares its mechanism. Everything else on the list
is either matched by Redis, matched by ten lines of application code, or a Redis advantage.

### A. Transport efficiency

**Likely, not proven — unchanged.**

Code-verified: the read path is a single gathering `channel.write` from the off-heap slot to the socket
(`ClientSession.java:177-189`); the write path reads the socket directly into the destination slot
(`:201-218`). Redis's `addReplyBulk` stages every reply through `c->buf` or the reply list before
`writeToClient` — one full-payload copy per GET.

The scaling shape matches the hypothesis exactly: indistinguishable at 256 KB (344 vs 342 MB/s), widening
monotonically with payload size. A counter-cost is identified and not yet quantified: FastCache's
`ByteBuffer.allocateDirect` **zeroes** each slot, a full-payload `memset` per write that Redis does not
pay.

**No profiler has attributed time to `memcpy` in either server.** The claim stays "likely". Do not
upgrade it.

### B. Large-value throughput

**Proven, within the measured range. Record the crossover; do not extrapolate below it.**

Validated crossover, native Linux, Redis 7.0.15, 4 vCPU:

| Concurrency | FastCache faster on p50 from | FastCache faster on p99 from |
|---|---|---|
| 1 client | **≥10 MB** | ≥10 MB |
| 8 clients | **≥1 MB** | **≥10 MB only** |
| 32 clients | ≥256 KB (lowest tested) | **≥10 MB only** |

Below 10 MB, FastCache's p99 is worse at every concurrency level tested — 1.94×–2.27× worse at 256 KB,
and 1.30× worse at 1 MB / 32 clients *while its median is 3.8× better*. A service that prices p99 above
median, on values under a few megabytes, should read this as a reason **not** to adopt FastCache.

25 MB and 50 MB at 32 clients were not run and must not be inferred. The 4-vCPU runner constrains exactly
the axis on which the two architectures differ most, and in FastCache's disfavour: thread-per-connection
benefits from cores, a single event loop does not.

### C. Python + JVM architecture

**Technically interesting. Value unproven, and one structural argument is strong enough to test.**

The strong argument is not performance, it is **duplication**. A Python service deployed as N worker
processes (the normal deployment) caching 25 MB values in-process holds N × 25 MB. A sidecar holds one
copy. At 8 workers that is 200 MB against 25 MB, and it gets worse with every worker.

The weak arguments, named so they stop being used:

- *"Off-heap relieves GC"* — CPython refcounts. There is no stop-the-world pause to relieve. This
  argument does not transfer from the JVM and should not be repeated for the Python path.
- *"A JVM cache engine for Python is novel"* — novelty is not value. It also imposes a JDK 21+
  installation on a Python deployment, which is a real adoption cost Redis does not charge.

The duplication argument is **exactly as true of Redis**, which also holds one copy for N workers. So
Product C's differentiator reduces, again, to the same two things as B: bandwidth on large values, and
not having to run Redis. Neither has been measured on the Python path. `redis-py` versus
`fastcache_ai` at 1/10/25 MB is a one-day experiment that has never been run, and Product C's entire case
currently rests on it.

**Verdict: technically interesting; value not yet demonstrated; one cheap experiment away from an
answer.** Ranked second behind §13 because it tests a smaller product than B does.

### D. Single-flight

**Implementation capability, not a product differentiator.** Three reasons, in order:

1. **Java cannot reach it.** `OP_REFRESH_LEASE`/`OP_REFRESH_DONE` are exercised by
   `SidecarProtocolTest` and used by `python/fastcache_ai/client.py`. No Java code in the product opens a
   socket at all (§3B), so the cross-process coalescing that distinguishes FastCache from "Redis plus a
   lock" is available to Python callers only.
2. **In-process, Caffeine already has it.** `Cache.get(key, Callable)` collapsed 60/60 cells identically.
   FastCache's only edge is p99 in 9 of 10 cells — a genuine but small tail improvement, plausibly from
   `RefreshCoordinator` releasing parked followers together.
3. **Against Redis it is convenience, not capability.** `SET key token NX PX ttl` plus a poll loop is
   roughly ten lines and is what every team already does. FastCache's version has better failure
   semantics (a lease tied to a connection, released when the connection drops, versus a TTL a crashed
   holder leaves behind) — but "better-behaved advisory locking" does not move a purchasing decision.

**It is a good feature. It is not a reason to exist.** If Product B is ever built, single-flight should be
in it because it is cheap and correct, not because it sells anything.

### E. Zero-copy — classified by layer

The phrase is banned from unqualified use. Each layer is answered separately, from code:

| Layer | Status | Evidence |
|---|---|---|
| **Zero-copy storage** | **Yes** | Payload lands in the off-heap slot that will hold it; `readFully(slot)` writes socket → slot with no intermediate (`ClientSession.java:201-218`). Caveat: `allocateDirect` zeroes the slot first — a full-payload `memset` per write. |
| **Zero-copy transport** | **Yes, server-side** | Single gathering `channel.write(new ByteBuffer[]{header, payload})` from the slot to the socket; never staged, never on the Java heap (`ClientSession.java:177-189`). |
| **Zero-copy client access** | **No** | Java: `WireClient.get` uses `DataInputStream.readFully(byte[])` — a full heap array per read. Python: `recv_into` over a preallocated `memoryview`, then `bytes(buffer)` — one copy, then deserialisation into a full object graph. |
| **Zero-copy application response** | **No, and impossible on a servlet stack** | `ServletOutputStream` inherits only `write(int)`, `write(byte[])`, `write(byte[], int, int)`. There is no `ByteBuffer` overload. Spring MVC on Tomcat **cannot** write a response body from off-heap memory. Achievable only on WebFlux/Netty via `DataBufferFactory.wrap(ByteBuffer)` with direct buffers. |

**The consequence, stated plainly:** FastCache is zero-copy up to the socket and copy-bound after it. The
2.14 MB allocated per 1 MB request in the end-to-end service is the measurement of that boundary. Closing
it would require a `get(String, ByteBuffer)` client read, a lease whose lifetime outlives the call (the
existing `Lease` is explicitly single-virtual-thread and an async response breaks exactly that property),
and a direct-buffer pool sized `max-concurrency × payload`. And it would pay off **only for services that
never look at the value** — proxies, blob passthroughs, CDN edges. The moment an application deserialises,
the object graph is on the heap and the saving collapses to the transient encoded array.

---

## Large-value use cases

The question is not "can FastCache cache a 25 MB value" — obviously it can. The question is:

> **Why would a developer intentionally choose FastCache over Redis or Caffeine for a 10–25 MB value?**

Given §6, there is exactly one honest answer available: *because Redis's ~150–175 MB/s large-value
ceiling is the binding constraint on my read path, and the value is too big or too shared for Caffeine.*
Every candidate workload is scored against that, not against whether it sounds modern.

Five filters a workload must pass:

1. Value genuinely ≥10 MB **as a single cache entry**.
2. Shared across processes — otherwise Caffeine wins by 3–4 orders of magnitude.
3. Fetched **whole**, not sliced or ranged — otherwise the big value should be N small ones.
4. Read rate high enough that **>175 MB/s** of cache reads is actually required — otherwise Redis suffices.
5. Recompute cost high enough to justify a cache at all, and not better served by object storage or a
   local file.

| Workload | Value size | Access pattern | Caffeine | Redis | FastCache | Why choose FastCache? |
|---|---:|---|---|---|---|---|
| **LLM context / prompt cache** | 0.1–5 MB | Read whole, per request | fine | fine | — | **No.** 25 MB of text is ~6M tokens, beyond every model's window. Real prompt caching is provider-side. Fails filter 1. The README's "50 MB context window" framing is not a real workload. |
| **Embedding batches** | 5–50 MB | Sliced by query, ANN-indexed | poor (size) | fine | — | **No.** Fails filter 3 — embeddings are queried by similarity, not fetched whole. A vector index, not a cache. |
| **OCR / document extraction results** | 10–80 MB | Read whole by 3–6 downstream stages | fails (size × N) | works, slow | ✔ | **Plausible.** Recompute is seconds-to-minutes; layout + text + region images are consumed whole; a pipeline fans one document out to several worker processes. Passes 1, 2, 3, 5. **Filter 4 is the question** — most pipelines are not reading >175 MB/s of documents. |
| **Whisper / ASR transcription** | output ~0.5 MB | Read whole | fine | fine | — | **No.** Fails filter 1: a one-hour transcript is well under 1 MB. The *audio* is large, but nobody caches inputs. |
| **Image / video metadata** | KB | — | fine | fine | — | **No.** Fails filter 1 by three orders of magnitude. |
| **Generated media / segmentation masks / depth maps** | 10–60 MB | Read whole, re-served many times | fails (size × N) | works, slow | ✔ | **Plausible, and the best filter-4 candidate.** A generated frame or mask re-served at high request rates does approach the bandwidth ceiling. But it is also the workload most likely to belong in a CDN or object store, which fails filter 5. |
| **Compiled artifacts / build cache** | 10–500 MB | Read whole, once per build | fails | works | — | **No.** Fails filter 4 decisively: against a 30 s build, 57 ms versus 13 ms is noise. Build caches are disk- and S3-backed for good reasons. |
| **Large API responses** | 0.1–5 MB | Read whole | fine | fine | — | **No.** Fails filter 1; anything genuinely 10 MB+ is paginated in practice. |
| **ML inference results (classification, detection, ranking)** | KB–MB | Read whole | fine | fine | — | **No.** Fails filter 1. |
| **Expensive serialisation results — Arrow/Parquet batches, materialised pivots, rendered reports** | 10–100 MB | Read whole, fanned out to many readers | fails (size × N) | works, slow | ✔ | **The strongest candidate.** Recompute = seconds of SQL; the batch is consumed whole by a columnar reader; an analytics service fans one batch to many concurrent readers, which is precisely where Redis's 162 MB/s at 25 MB binds and FastCache's 725 MB/s does not. Passes all five filters. |
| **Session / state snapshots** | KB–MB | Per-user, read whole | fine | fine | — | **No.** Fails filter 1, and fails filter 2's spirit — per-user values have near-zero reuse. |

**Result: 3 of 11 candidates survive, and only one survives all five filters.**

The surviving shape, stated as a sentence a customer could recognise:

> A service that fans a multi-megabyte, expensively-computed, whole-consumed artifact out to many
> concurrent reader processes at an aggregate rate above roughly 175 MB/s.

Columnar analytics batches fit it. Document-extraction pipelines fit it if their read rate is high
enough, which is unverified. Everything else on the list does not.

**This is a narrow market, and the narrowness is the finding.** It is also honest in a way the current
README is not: *none of these is a good fit because it is AI-adjacent.* They are a fit or not on payload
size, sharing, whole-consumption and bandwidth — four properties that have nothing to do with what
produced the value.

---

## JVM architecture gap

**Architecture proposal only. Nothing here is implemented, and §9 argues about whether it should be.**

To deliver the measured advantage to a JVM team, this path must exist and does not:

```
Spring Boot application
        ↓  @Cacheable / FastCacheManager
FastCache Java client        ← does not exist in the product
        ↓  socket, length-prefixed binary
FastCache sidecar            ← exists; only Python can reach it
        ↓
off-heap storage
```

### API surface

A `FastCacheClient` with the shape the protocol already supports, and nothing beyond it:

| Method | Backed by | Notes |
|---|---|---|
| `byte[] get(String)` | `OP_GET` | Allocating. The honest default. |
| `void put(String, byte[], Duration)` | `OP_PUT` | TTL is already on the wire. |
| `void delete(String)` | `OP_DELETE` | |
| `ScanResult scan(String pattern, long cursor, int limit)` | `OP_SCAN` | Cursor semantics already defined. |
| `Stats stats()` | `OP_STATS` | For metrics binding. |
| `Lease refreshLease(String)` / `void refreshDone(String)` | `OP_REFRESH_LEASE` / `OP_REFRESH_DONE` | The only way single-flight reaches Java. |
| `int get(String, ByteBuffer dst)` | *(protocol-compatible, unimplemented)* | The non-allocating read. Deliberately **out of scope for v1** — see §6E on why the lease lifetime, not the I/O, is the hard part. |

**Protocol gaps that constrain the design and must be acknowledged up front:**

- **No pipelining.** `ClientSession` is strict request→response per connection. Concurrency is bought with
  sockets, one per in-flight operation. There is no `MGET`.
- **No auth.** The protocol is unauthenticated by design and documented as loopback-only. That is a
  deployment constraint (same host, sidecar pattern) not a config option.
- **No CAS, no atomic counters, no TTL-update op.**

### Serialization model

The measured advantage is a *byte-transport* advantage. Interposing a slow serialiser between the
application and the socket spends it before the application sees it. Therefore:

- `byte[]`-native surface as the primitive, with a pluggable `FastCacheSerializer<T>` above it.
- **Default must not be Java serialization.** At 10 MB, JDK serialisation would plausibly cost more than
  the 44 ms FastCache saves over Redis on the same value. Ship Jackson Smile or Kryo, benchmark the
  default, and publish the serialisation cost *beside* the transport cost so a team can see which one it
  is paying.
- Spring `Cache` returns `Object`; the adapter must round-trip through the serialiser, which means
  **the end-to-end number a Spring user sees is transport + serialisation**, not the transport number in
  this repository. Every published figure must say which it is.

### Connection pooling

- Fixed-size pool, `maxTotal` defaulting to the servlet container's thread count — because no pipelining
  means in-flight operations and sockets are 1:1.
- Borrow with timeout; **exhaustion must fail fast**, not queue unboundedly, since a queued 25 MB read
  behind a full pool is indistinguishable to a caller from a hung service.
- Per-connection 64 KiB buffered streams, `TCP_NODELAY`, as `WireClient` already does.
- Validate on borrow with `OP_PING` only after an idle threshold — a ping before every 13 ms read is 8%
  overhead.

### Failure semantics

The single most important design decision, and the one a benchmark cannot answer:

- **Default: fail-open.** A cache that takes the service down when it is unavailable is worse than no
  cache. A read failure returns a miss; a write failure is dropped and counted.
- **Circuit breaker** on consecutive failures, so a dead sidecar costs one connect timeout per probe
  interval rather than one per request.
- **Bounded read timeout that scales with payload.** A fixed 100 ms timeout would abort every legitimate
  25 MB read (34 ms p50, 57 ms p99 at 8 clients) under load. This needs a size-aware policy, which is a
  design problem the protocol's length prefix makes tractable but does not solve.
- **`MemoryGuard` write rejections are a normal, expected response** (§10), not an error. The client must
  surface them as a distinct outcome or operators will read a healthy shedding cache as a broken one.

### Lifecycle management

- Spring Boot `@ConfigurationProperties` for host/port/pool/timeouts, and an auto-configuration that backs
  off entirely when a `FastCacheClient` bean is already defined.
- **Sidecar startup: do not auto-start it from Spring.** The Python client auto-starts a JVM because a
  Python process has no JVM; a Spring app *is* a JVM, and a Spring app spawning a second JVM it must then
  supervise, restart and reap is an operational liability. Ship the sidecar as a container image and a
  systemd unit, documented as a sidecar in the Kubernetes sense. Auto-start is a demo feature; it would
  become the most-reported bug.
- Graceful shutdown: drain the pool, `OP_CLOSE` each connection.

### Spring Cache integration

- A `FastCacheRemoteCacheManager` implementing `CacheManager`, parallel to the existing in-process
  `FastCacheManager`, selected by property. **Both must not be active at once** — silently getting the
  in-process one is how a team would benchmark Product A and believe it was Product B.
- **`@Cacheable`**: `get` → miss → invoke → `put`. Straightforward.
- **`@Cacheable(sync = true)`**: this is where `OP_REFRESH_LEASE` earns its keep, and where the
  implementation is genuinely hard. Spring's contract is `Cache.get(key, Callable)` blocking the caller
  until a value exists. Mapping that onto a remote lease requires: acquire lease → on success invoke the
  `Callable` and `put` → on failure **poll** for the leader's value with a bounded backoff, and decide
  what to do when the leader dies mid-load. A local `ConcurrentHashMap` guard should sit in front of the
  remote lease so N callers *in one JVM* make one lease request, not N.
- **`@CacheEvict`** → `OP_DELETE`; `allEntries = true` → `OP_SCAN` + delete, because there is no
  prefix-delete op. At scale this is a loop over the wire and must be documented as such.
- **TTL**: per-cache `Duration` in properties, passed on `OP_PUT`. There is no TTL-update op, so
  `@CachePut` semantics rewrite the value to refresh a TTL.
- **Eviction**: entirely server-side. The application has no control and no visibility beyond `OP_STATS`.
  This is a real difference from Caffeine that teams will trip over.

### Metrics

Micrometer binding: `fastcache.client.{get,put}.{latency,bytes}`, pool `{active,idle,borrow.wait}`,
`{timeouts,circuit.open,write.rejections}`, and the sidecar's own `OP_STATS` (entries, offheap reserved,
slots, hit ratio, shed writes) exported so that a sawtooth like the original soak is visible from the
application's dashboard rather than only from the sidecar's log.

### Reconnection

Per-connection: on `IOException`, discard the socket rather than return it to the pool; reconnect lazily
on next borrow with jittered backoff; trip the breaker on N consecutive connect failures. Crucially,
**a reconnect after a sidecar restart returns an empty cache** — a cold-start stampede across every key
at once. This is exactly what single-flight is for, and it is the strongest *internal* argument for
shipping single-flight with the client rather than after it.

### What this is not

No replication, no persistence, no failover, no cluster, no auth, no TLS. A JVM team evaluating this
against Redis is comparing a loopback sidecar with none of those against a system with all of them. That
comparison belongs in the documentation, not in a footnote.

---

## Should we even build it

### Option A — improve embedded FastCache

Make the Spring path genuinely off-heap: serialise on `put`, store bytes in the off-heap engine,
deserialise on `get`.

| Axis | Assessment |
|---|---|
| **Engineering complexity** | **Medium.** Replace `putReference` with encode/store/decode; the engine already does the hard part. But it changes the semantics teams have today — cached objects stop being shared references and become copies, which breaks any code mutating a cached object, and changes `@Cacheable` identity semantics. |
| **Latency** | **Worse than today, by a lot.** Today: 10.4 µs (a reference return). After: a serialise on write and a deserialise on read — plausibly 1–15 ms at 1–25 MB. Against Caffeine's 7.9 µs that is a 100–1000× regression in exchange for heap. |
| **Memory behaviour** | Genuinely better: payloads leave the heap. G1 humongous-region waste (up to 100% at 1 MB in a 4 GB heap) disappears. |
| **GC behaviour** | **Same trap as the sidecar, minus the socket.** Retention becomes allocation: a payload-sized array plus an object graph per hit. The end-to-end 1 MB cell already measured what that costs — 1 463 ms of GC against 174 ms. |
| **Operational complexity** | **Lowest of any option.** No second process. This is its one genuine strength. |
| **Differentiation** | **Near zero.** It would be "Caffeine, but off-heap and 100× slower per hit". Ehcache, Chronicle Map and Apache Ignite already occupy that space, and have for a decade. |
| **Competitive position** | Weak. Competes with Caffeine on Caffeine's axis and loses; competes with Ehcache on Ehcache's axis with none of its maturity. |

**Assessment: cheap, low-risk, and it does not produce a product.** It converts a parity product into a
slower product with better heap numbers. The evidence says a 4 GB service heap can afford the retention.

### Option B — build the JVM → sidecar architecture

| Axis | Assessment |
|---|---|
| **Engineering complexity** | **High, and larger than "a 200-line client".** §8 enumerates it: pooling, timeouts scaling with payload, circuit breaking, reconnection, serialiser selection and benchmarking, two `CacheManager`s that must not both activate, remote `sync = true` with leader-death handling, metrics, lifecycle. Realistically **4–8 weeks** to something a team could run, plus the `MemoryGuard` work in §10 which is a hard prerequisite. |
| **Latency** | Best measured cross-process numbers available — **but every published figure excludes serialisation**, which a Spring user cannot avoid. The real end-to-end number is unmeasured. |
| **Memory behaviour** | Payloads off the heap. Demonstrated: 26 MB vs 1 056 MB. **Equalled by Redis in the same cell.** |
| **GC behaviour** | **Worse than Caffeine in the only valid end-to-end measurement** (1 463 ms vs 174 ms), and equal to Redis per request. Unmeasured at 10 MB and 25 MB, which is the whole point of §13. |
| **Operational complexity** | **Highest.** A second process, with no replication, no persistence, no auth, no failover, and no operational literature — placed next to a team's existing Redis, which has all of those. |
| **Differentiation** | **Real but singular**: 4–5× bandwidth on values ≥10 MB, and the write-latency behaviour that shares its mechanism. §6 finds no second differentiator. |
| **Competitive position** | Defensible in a narrow band (§7: roughly one workload shape out of eleven examined) and indefensible outside it, where Redis is better on p99, better below 1 MB, and better on everything unmeasured. |

**Assessment: the only option that can produce a product, at 4–8 weeks plus a prerequisite, for a market
§7 sizes at one workload shape.**

### Which architecture deserves another month of engineering?

**Neither — not yet. One week of measurement deserves it first.**

The comparison above turns almost entirely on numbers that do not exist. Option B's case rests on the
≥10 MB advantage surviving inside a real service, and **the end-to-end cell that would have shown that
was 100% corrupt** (§11). The valid end-to-end evidence stops at 1 MB, and at 1 MB the picture is:
FastCache beats Redis on throughput by 1.31× (on a platform where Redis is weak at that size), matches it
on heap, and loses to Caffeine on GC by 8.4×.

Committing a month to Option B on that basis would be committing it on a microbenchmark. Committing it to
Option A would be committing it to a known non-differentiator.

**If forced to choose today: Option B, and only Option B** — Option A improves a product that §3A shows
has no independent reason to exist. But the honest answer is that §13's experiment costs about a day and
can flip Option B from "build it" to "do not", which makes spending a month before running it
indefensible.

---

## MemoryGuard problem

Kept deliberately separate from the product thesis: this is a capacity-planning defect, not evidence
about FastCache versus anything. Code re-verified against the working tree for this document.

### What is measured

Host at 89.7% physical memory, `GuardProbe` filling with 4 MB payloads until first refusal:

| Configured budget | Gate floor | First refusal at | Budget honoured |
|---:|---:|---:|---:|
| 128 MB | 64 MB | 64 MB | 50.0% |
| 512 MB | 64 MB | 64 MB | 12.5% |
| 1024 MB | 64 MB | 64 MB | **6.3%** |
| 4096 MB | 204 MB | 208 MB | **5.1%** |

Plus: in a drain test with a 10-minute TTL and no writer, **25 of 52 entries disappeared within 5
seconds**; and at 25 MB and 50 MB payloads, writes were accepted and `entries` read **0** moments later.

### Classification

| Finding | Code | Class | Reasoning |
|---|---|---|---|
| **`free` vs `available`** | `PhysicalMemory.java:42` uses `getFreeMemorySize` | **Design flaw** | The API's own documentation says reclaimable page cache counts as used, so a healthy host reads 80–90%. The code's comment acknowledges this and then uses the number as a threshold input anyway. Not a bug — it does what it says — and not tuning, because no threshold value makes a wrong variable right. |
| **5% / 64 MiB gate floor** | `MemoryGuard.java:139` | **Tuning issue, with a design flaw inside it** | `max(64 MiB, budget × 0.05)`. The *constant* is a tuning issue: 64 MiB is three payloads for the target workload. The *shape* is a design flaw: a floor that does not scale with the budget means a larger budget buys proportionally less protection from the gate. |
| **Latching relief** | `MemoryGuard.java:231-238`, release at 75% of floor | **Design flaw** | A controller whose actuator does not move its measured variable cannot stabilise. Shedding 300 MB on a 16 GB host moves the physical ratio by under two points, and the reclaimable pages that inflated it are not FastCache's. It can only oscillate or latch — and it latches until its own hysteresis releases it. This is the sawtooth in the original soak (off-heap 183–442 MB, 59 741 shed writes, 56% hit rate). |
| **Proportional eviction degenerating to a wipe** | `EvictionSweeper.java:152`, `Math.max(1, size × 0.10)` | **Bug** | The stated intent is "shed 10%". At fewer than 10 entries per shard the `max(1, …)` makes it 33% or 100%. Code that does the opposite of its documented intent under a reachable input is a bug, not a design choice. |
| **Large values amplify all of the above** | — | **Product requirement** | 32 shards and 20 × 25 MB entries means most shards hold ≤1 entry, so one pressure pass empties the cache. The failure mode is worst exactly where the product claims to be strongest. A large-value cache needs a byte-proportional policy; an entry-proportional one is a small-value assumption baked into the design. |
| **Refuse rather than evict-to-fit** | — | **Product requirement** | Redis with `allkeys-lru` accepted **0** refused writes across every comparison run; FastCache refused tens of thousands. A byte-bounded cache that responds to fullness by rejecting new writes and retaining old entries preserves cold data and discards hot data — the inverse of what an LRU cache is for. This is the change most likely to matter to a real user and the least visible in a latency benchmark. |

### Minimum conceptual correction

Stated as the smallest set that makes the capacity model plannable. **No implementation, and deliberately
smaller than the full proposal in `memoryguard-production-analysis.md` §5.**

1. **The budget must be the only thing that refuses an ordinary write.** Machine pressure becomes a
   separate, *observable* state — "shedding under host pressure" — rather than silently masquerading as
   the cache being full. An operator who configured 4 GB and got 208 MB must be able to see why without a
   profiler. *This one change makes the capacity model plannable; the rest are corrections to a mechanism
   that would then be advisory rather than load-bearing.*
2. **Read `available`, not `free`.** `MemAvailable` from `/proc/meminfo` on Linux; a platform probe with
   the current reading as fallback. Until then the gate is measuring the page cache.
3. **Delete the `max(1, …)` floor.** `(int) Math.floor(size × 0.10)` — shedding nothing from a shard
   holding fewer than ten entries is correct behaviour, not a gap to patch. For a byte-bounded cache the
   proportional unit should be bytes.
4. **Evict to fit rather than refuse**, at least as an option, so a full cache behaves like an LRU cache.

Ordering matters: (1) alone converts an unplannable system into a planable one. (4) is the one a user
would notice. (2) and (3) are correctness repairs to a mechanism that should stop being load-bearing
after (1).

**Blocking status: every FastCache latency number in this repository was measured with
`--reject-ratio 1.0`.** They are valid as measurements of the transport and storage paths and invalid as
a description of what a user experiences today.

---

## Benchmark integrity

### Frozen evidence — classification of every run

Nothing is deleted. Data directories carry a `STATUS.md` recording the same classifications.

#### End-to-end JVM matrix, run 1 — [`data/e2e-run1/steady-partial.csv`](data/e2e-run1/steady-partial.csv)

| Cell | Classification | Reason |
|---|---|---|
| `steady-caffeine-1m-c8` | **VALID** | entries 512, corrupt 0, misses 512 = loaders 512 (one cold fill per key) |
| `steady-fastcache-embedded-1m-c8` | **VALID** | as above |
| `steady-fastcache-sidecar-1m-c8` | **VALID** | as above |
| `steady-redis-1m-c8` | **VALID** | as above |
| `steady-caffeine-10m-c8` | **VALID** | entries 51, corrupt 0 |
| `steady-fastcache-embedded-10m-c8` | **VALID** | entries 51, corrupt 0 |
| `steady-fastcache-sidecar-10m-c8` | **INVALID — harness contamination** | **55 941 corrupt reads of 55 941.** `entries` read 512 (the 1 MB tier's key count) and `lookupP50` 3 194 µs — statistically indistinguishable from the same arm's 1 MB cell at 3 108 µs. The sidecar was not flushed between payload tiers, so this cell transported **stale 1 MB payloads labelled as 10 MB**. |
| `steady-caffeine-25m-c8` | **VALID** | entries 20, corrupt 0 |
| `steady-fastcache-embedded-25m-c8` | **INVALID — host resource exhaustion** | 608 × `OutOfMemoryError: Java heap space`; no CSV row produced |
| `steady-redis-10m-c8`, `-25m-c8`, all `c1`/`c32` cells | **NEEDS RE-RUN** | never executed; run aborted |

**Run 1 overall: PARTIAL — 7 of 8 rows valid**, and the one invalid row is the single most important cell
in the matrix.

#### End-to-end JVM matrix, run 2 — [`data/e2e/steady.csv`](data/e2e/steady.csv)

**Entire run: INVALID.** Reconstructed from applog timestamps and the CSV:

Run 1 was aborted at 02:13:08. Its `EXIT` trap removed `.matrix.lock`, but the service JVM it had launched
— the 25 MB embedded cell, by then throwing `OutOfMemoryError` — **survived and kept port 8090**. Run 2
started at 02:13:44 into the same output directory. `run_cell`'s pre-flight check probes `/admin/pid` with
a 2-second timeout; the OOM-thrashing JVM could not answer inside it, so the port read as free.

| Row | Classification | Reason |
|---|---|---|
| `steady-caffeine-1m-c8` | **INVALID — host resource exhaustion + harness contamination** | Its own service logged `APPLICATION FAILED TO START — Port 8090 was already in use`. The load generator then measured **run 1's abandoned, OOM-ing 25 MB embedded service**. The row proves it: `arm=fastcache-embedded`, `payloadBytes=26214400`, 156 of 512 entries resident, 266 errors. **This is the 2 req/s, 4 090 MB heap, 36 170 ms-of-GC-in-40 s row. It is an environment failure and says nothing whatsoever about Caffeine, or about FastCache versus Caffeine.** |
| `steady-fastcache-embedded-1m-c8` | **INVALID — host resource exhaustion** | Started cleanly, but ran against surviving JVMs from run 1. 3 205 req/s against run 1's 5 401 for the identical cell (**−41%**). 1 022 misses for 512 entries: entries were evicted and reloaded mid-run. |
| `steady-fastcache-sidecar-1m-c8` | **INVALID — host resource exhaustion** | 519 req/s against 998 (**−48%**). `heapUsed` 466 MB where run 1 measured 26 MB for the same arm and payload — impossible for an arm that stores nothing on the heap. |
| `steady-redis-1m-c8` | **INVALID — host resource exhaustion** | 547 req/s against 759 (**−28%**). |
| 3 further cells (`caffeine-10m-c1`, `fastcache-sidecar-25m-c8`, `redis-25m-c8`) | **INVALID — harness contamination** | `APPLICATION FAILED TO START — port in use`; no rows produced |
| `steady-caffeine-10m-c8` | **INVALID** | zero-byte applog; process never wrote |

#### Earlier rounds

| Run | Classification | Note |
|---|---|---|
| Native Linux Redis microbenchmarks (all CSVs in `data/`) | **VALID**, scoped | Post-fix harness; 3 runs each; corrupt-read count zero in every cell; `--reject-ratio 1.0` throughout |
| Windows / Memurai Redis head-to-head | **VALID as a platform comparison only** | Superseded below ~1 MB by the Linux round, which reversed the result there |
| Caffeine vs FastCache heap/GC, Scenarios A–H | **VALID** | |
| Spring `CacheManager` / stampede, 60 cells | **VALID** | |
| Leak probes, 8 paths | **VALID** for per-operation paths only | Does not address long-horizon fragmentation |
| `GuardProbe` capacity measurements | **VALID** | |
| Original soak | **INVALID — abstained** | Guard sawtooth (off-heap 183–442 MB, 59 741 shed writes, 56% hit rate) meant it certified nothing. Correctly abstained at the time. |
| Python demo workload | **VALID but not comparative** | Synthetic, deliberately cache-friendly, 99.3% hit rate by construction; no Redis arm |

### Discarded runs and whether they could affect the conclusion

| Run | Reason discarded | Could it affect the conclusion? |
|---|---|---|
| End-to-end attempt 1 — **Windows PID teardown** | `$!` under Git Bash is an MSYS pid; `Stop-Process` succeeded while the JVM kept running. A finished cell's service held 1.4 GB and burned CPU during the next cell. | **No.** Caught before any number was published; every cell would have been contaminated, so all were discarded. Fixed by in-band `/admin/shutdown` plus a port-free check. **But it is the direct cause of run 2's contamination** — the fix handles a *responsive* service and does not handle an unresponsive one. |
| End-to-end attempt 2 — **embedded shard-bound mistake** | `maxEntriesPerShard = entries/32 + 1`; murmur3 skew of 1.2–1.4× made the ceiling bind on the hottest shard while the cache sat half empty. 204 of 512 entries resident, 141 req/s. | **No** for the comparison — discarded. **Yes** as a product finding: the embedded path can *only* be bounded by entries-per-shard, because `putReference` reports zero footprint, so sizing it requires reasoning about hash skew and getting it wrong degrades silently into a miss storm rather than an error. Reported as a product finding, not a harness note. |
| Microbench — **warmup capped at 200 ops** | p50 drifted up to −93.5% from run 1 to run 3 in the same process. Averaging three runs averaged warmup with steady state. | **Yes, and it was corrected.** All published Linux numbers are post-fix (`warmup = max(200, min(ops, keys × 5))`). Pre-fix numbers are not cited anywhere in this document. |
| Microbench — **fixed arm order** | Arms always ran `caffeine → sidecar → redis` in one JVM, so the last arm had the hottest JIT. | **Yes in principle, no in direction.** Redis always ran last, so the bias favoured Redis — and Redis still lost at ≥10 MB. The surviving result is conservative with respect to this defect. Fixed by rotation anyway. |
| Microbench — **seed-only payload validation** | A truncating transport would have passed and appeared *faster* — the most dangerous possible failure for this comparison. | **No, verified retrospectively.** Allocation volume matched 1 000 full payloads to within 1% in every cell (25 002 MB observed vs 25 000 MB expected at 25 MB), zero corrupt reads. The check was not strong enough to have *proven* that at the time; length is now checked alongside the stamp. **This is the defect class that later caught the run-1 sidecar cell** — the fix worked. |
| Microbench — **leaked GC listeners** | `Probe` attached a `NotificationListener` per collector and never removed it; by the 60th cell, 60 listeners fired per collection, loading whichever cells ran last. | **Possible but small, and it is not quantified.** Fixed (`Probe` is `AutoCloseable`). Direction: it penalises late cells. Combined with rotation, no arm is systematically late. |
| End-to-end run 2 — **concurrent matrix processes** | Run 1's aborted shell left JVMs alive; run 2 started into the same directory and measured them. | **No, because all four rows are discarded.** But it destroyed the data that would have answered §13, which is why this whole review exists. |
| End-to-end run 1 — **stale payload across tiers** | The sidecar was not flushed between the 1 MB and 10 MB tiers. | **Yes — this is the most consequential single defect on the list.** It removed the only end-to-end measurement of the sidecar at 10 MB, which is the payload size where the entire product thesis lives. The corrupt-read counter caught it, which is the one piece of good news. |
| End-to-end run 2 — **host memory exhaustion** | Four JVMs plus a sidecar plus a load generator on a 16 GB host with 25 MB payloads and a 4 GB service heap. | **No** — discarded. It must not be cited as evidence about Caffeine's constrained-heap behaviour, which remains unmeasured. |

### Is the remaining evidence trustworthy?

**Yes, with one exception and one qualifier.**

- The **microbenchmark evidence is trustworthy.** Four defects were found by audit, fixed before the Linux
  round, and the two surviving asymmetries (fresh sidecar JVM per cell; Redis warm from running last) both
  point *against* FastCache. The headline result is therefore conservative.
- The **end-to-end evidence is trustworthy only at 1 MB**, where 4 of 4 cells are clean, plus the
  Caffeine/embedded cells at 10 MB and Caffeine at 25 MB.
- **The exception:** there is **no valid end-to-end measurement of the FastCache sidecar or of Redis above
  1 MB.** That is precisely the region where the product's only differentiator lives.
- **The qualifier:** all end-to-end runs were on Windows against Memurai, where Redis is known to be weak
  below ~1 MB and equivalent at ≥10 MB. The 1 MB end-to-end comparison is therefore **pessimistic for
  Redis**, which makes FastCache's 1.31× throughput edge at 1 MB an upper bound rather than a measurement.

### Harness fixes required before any further run

Three, and none is optional — the matrix produced unusable data twice for these reasons:

1. **Flush the sidecar and Redis between payload tiers**, and abort the cell if the post-flush entry count
   is non-zero. The existing corrupt-read counter should **fail the run**, not print a warning that
   survives into a committed CSV.
2. **Make the port pre-flight check handle an unresponsive process**, not just a responsive one. A 2-second
   `/admin/pid` timeout reads an OOM-thrashing JVM as absent. Bind-test the port, or fail the cell.
3. **Hold the lock for the process tree, not the shell.** A trap that releases the lock while child JVMs
   survive is worse than no lock, because it makes the next run look safe.

---

## Product differentiation

Consolidated from §6, stated as the two sentences that should replace the current thesis:

> **What FastCache provides that Redis does not:** sustained read bandwidth on multi-megabyte values under
> concurrency — ~750 MB/s flat from 1 MB to 50 MB where Redis degrades to ~150 MB/s — and, by the same
> mechanism, write latency that does not head-of-line-block behind large reads.
>
> **What FastCache does not provide that its documentation implies it does:** heap relief that Redis does
> not already give (measured identical); GC relief for a service that reads its values (measured worse
> than Caffeine); general-purpose caching (Redis is faster below 1 MB and better on p99 below 10 MB); or
> any of this from a JVM application, because the client does not exist.

Positioning consequences:

- **The product is a large-value cache or it is nothing.** At 256 KB it is at best a tie and at worst a
  2.3× p99 regression.
- **Lead with bandwidth, not latency.** "4× faster" invites "270 ms is fine". "Redis tops out at 150 MB/s
  on 25 MB values and we hold 725" identifies the workload.
- **Stop claiming AI workloads.** §7 finds that the AI-adjacent candidates mostly fail on payload size or
  access pattern, and that the strongest fit — columnar analytics batches — is not an AI workload at all.
- **Stop using "zero-copy" unqualified.** §6E gives the four layers; two are yes and two are no.

---

## Remaining unknowns

Ordered by how much each could change the decision.

| # | Unknown | Could it flip the decision? |
|---|---|---|
| 1 | **Does the ≥10 MB advantage survive inside a real service?** No valid end-to-end cell exists for the sidecar or Redis above 1 MB. | **Yes, decisively.** §13. |
| 2 | **What does serialisation cost at 10–25 MB?** Every published figure moves raw `byte[]`. A Spring user cannot. If a serialiser costs more than the 44 ms FastCache saves over Redis at 10 MB, the advantage is spent before the application sees it. | **Yes.** Could eliminate the differentiator for Product B specifically, while leaving it intact for byte-native callers. |
| 3 | **Is Product C actually better than `redis-py`?** Never measured. Product C's entire case is untested. | **Yes, for Product C.** |
| 4 | **Does the advantage hold on 16+ cores?** 4 vCPU constrains exactly the axis where thread-per-connection and a single event loop differ, and it constrains it in FastCache's disfavour. | Could **widen** the advantage; unlikely to eliminate it. |
| 5 | **Does `MemoryGuard` behave in a container?** `getTotalMemorySize` is container-aware on modern JDKs; `getFreeMemorySize` was **not tested** and must not be assumed. The largest untested surface in §10. | Not for the thesis; **yes for shippability**. |
| 6 | **Long-horizon native-arena fragmentation.** 8/8 per-operation leak probes clean; nothing runs longer than minutes. | Not for the thesis; yes for production readiness. |
| 7 | **Is the zero-copy mechanism causal?** Code-verified, scaling shape matches, no profiler has attributed time to `memcpy` in either server. | **No.** The advantage is measured whatever causes it. Affects how it may be *described*, not whether it exists. |
| 8 | **Failure and recovery behaviour.** Crash, restart, stale lock, stale socket, malformed request, oversized payload, reconnect: all unmeasured. | Not for the thesis; yes for shippability. |
| 9 | **Constrained-heap behaviour.** The cells that would have measured it are the ones §11 classifies as host-exhaustion failures. **Genuinely unmeasured** — do not cite the 4 090 MB row. | Would refine, not flip. |

---

## One next experiment

**Re-run exactly one cell family: steady-state 10 MB, 8 concurrent clients, all four arms, end to end.**

Nothing else. Not the matrix, not 25 MB, not the constrained heap, not the long run.

**Why this one.** It is the largest remaining uncertainty (§12 #1) and the only one that can *overturn*
the product conclusion rather than refine it. Everything favourable rests on a microbenchmark claim that a
10 MB cache hit is ~4× cheaper through FastCache than through Redis. The one end-to-end cell that would
have tested it returned 55 941 corrupt reads out of 55 941, and Redis was never run at 10 MB end-to-end at
all. At 1 MB — the only size with valid end-to-end data — the sidecar's per-read allocation already cost
8.4× Caffeine's GC pause while giving heap relief Redis matched exactly. Whether that pattern gets better
or worse at 10 MB is the entire question.

**Shape**

| | |
|---|---|
| Cells | 4: `caffeine`, `fastcache-embedded`, `fastcache-sidecar`, `redis` |
| Payload | 10 MB, 51 entries (~512 MB working set) |
| Concurrency | 8 |
| Duration | 15 s warmup discarded, 45 s measured |
| Repeats | **3**, with arm order rotated |
| Heap | `-Xmx4g`, G1 |
| Preconditions | Sidecar and Redis **flushed between cells**, post-flush entry count asserted zero, corrupt-read count asserted zero or the cell **fails** |
| Isolation | One matrix process; port bind-tested, not `/admin/pid`-probed; no other JVM on the host |
| Runtime | ~20 minutes |

**Pre-registered decision rule**, fixed before the run so no threshold can be chosen to fit the result:

| Outcome at 10 MB | Reading | Product decision |
|---|---|---|
| Sidecar throughput ≥2× Redis **and** sidecar GC pause ≤1.5× Redis | The microbenchmark advantage survives contact with a service | **B confirmed** → §9 Option B becomes fundable |
| Sidecar throughput ≥2× Redis **but** sidecar GC pause >1.5× Redis | The advantage is real but the application pays for it in GC | **B, narrowed further** to byte-native callers who never deserialise; Spring integration is not the shape |
| Sidecar throughput <1.5× Redis | The transport advantage does not survive the service | **C — interesting technology, weak product.** Stop. |
| Any cell reports a corrupt read | Harness, not product | Discard, fix, re-run. No conclusion drawn. |

**Do not run anything else until this has returned.** The remaining matrix cells measure an architecture
no user can adopt (§3B), in a configuration the product does not ship (§10), at sizes whose decisive cell
is this one.

### Outcome — run 2026-09-21

**The experiment was run. Full report: [`final-10mib-e2e-validation.md`](final-10mib-e2e-validation.md).**
12 of 12 cells valid, zero corrupt reads, zero errors, hit ratio 1.0, maximum run-to-run spread 6.5%.

| Measured | Result |
|---|---|
| Sidecar throughput ÷ Redis | **2.981×** (353.0 vs 118.4 req/s, non-overlapping ranges) |
| Sidecar GC pause ÷ Redis, **total** | 3.61× |
| Sidecar GC pause ÷ Redis, **per request** | 1.21× |

The decision rule above said "sidecar GC pause ≤1.5× Redis" without specifying total or per request, and
the two readings select different rows. Stated plainly rather than resolved in the favourable direction:

- **Per request** (1.21×) selects row 1 — **B confirmed**. This is the comparison the evidence supports,
  because the arms served different request counts: the sidecar's larger total pause follows from doing
  3× the work, and per request the two arms allocate *identically* (20.04 MiB each).
- **Total** (3.61×) selects row 2 — **B, narrowed to byte-native callers**. Defensible only if one reads
  total pause as the operating cost of running the arm at its own throughput.

The rule should have said which. It did not, so both are recorded.

Either row leaves the thesis at **B**, and the brief governing that run set its own unambiguous threshold
— ≥1.5× end-to-end throughput — which 2.981× passes. Row 3, the collapse to **C**, did not occur.

What this does **not** change: §10 (the configuration is unshippable), §3B (JVM applications cannot reach
the architecture) and §7 (roughly one workload shape in eleven needs the bandwidth) are all untouched by
this run. The thesis is B for the reasons it was already B, now with the end-to-end gap closed.

---

## Product decision

### **B — Narrow product thesis, conditional.**

> A specific workload justifies FastCache: a service fanning multi-megabyte, expensively-computed,
> whole-consumed artifacts out to many concurrent reader processes at an aggregate read rate above
> roughly 175 MB/s, where Redis's large-value bandwidth ceiling is the binding constraint. Outside that
> band the evidence recommends Caffeine (in-process) or Redis (everything else).

**Why B rather than A.** The ≥10 MB advantage is not marginal and not fragile: 4–5× on median, 5–6× on
throughput, reproduced on two platforms, two Redis io-thread configurations, three runs each, with
non-overlapping ranges, and with both surviving harness asymmetries pointing against FastCache. It has a
code-verified mechanism and a scaling shape that matches it. That is a real technical finding. But §6
finds exactly **one** differentiator and §7 finds roughly **one workload shape in eleven** that needs it —
which is the definition of narrow, not strong.

**Why B rather than C, today.** The differentiator is measured, large, and reproducible. That is enough to
keep the thesis alive.

**Why it is conditional, and what would make it C.** Three things must be true for B to remain B, and
none of them is currently established:

1. The ≥10 MB advantage survives end-to-end in a real service (§13). **Unmeasured — the cell was corrupt.**
2. Serialisation does not consume the advantage for the JVM audience (§12 #2). **Unmeasured.**
3. `MemoryGuard` can be made to honour a configured budget (§10). **Currently it delivers 5–6%.**

If §13 returns the third row of its decision table, this becomes **C — interesting technology, weak
product**, and that is a perfectly good outcome: it would mean roughly a day of measurement saved a month
of engineering on a JVM client for an advantage that does not survive contact with an application.

### What follows immediately, whatever §13 returns

Independent of the experiment, three conclusions are already firm enough to act on:

- **Product A has no reason to exist as a standalone performance product.** Parity heap, parity-or-worse
  GC, worse hit latency, extra dependency. Reposition it as the embeddable engine that serves Python
  workers, or retire the standalone story. Do not invest in Option A.
- **The documentation overstates the product in four specific ways** (§6E, §7, §3B, §10): unqualified
  "zero-copy", AI-workload fit, an implied JVM sidecar capability, and performance numbers from an
  unshipped configuration. These are correctable today at no engineering cost.
- **Three harness fixes are mandatory before any further benchmark run** (§11). The matrix has produced
  unusable data twice for reasons that are now precisely understood.

### What is explicitly not decided

Whether to build Product B. That decision is deferred to §13's result by design, and deferring it costs
about a day.
