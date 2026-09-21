# End-to-end JVM reality test — results

> **STOPPED, 2026-09-21. The matrix was halted with 10 cells outstanding and its results are consolidated
> and re-classified in [`THESIS_REASSESSMENT.md`](THESIS_REASSESSMENT.md). Read that first.**
>
> Two runs were attempted. Run 1 is **PARTIAL** — 7 of 8 rows valid, with the decisive
> `fastcache-sidecar-10m-c8` cell **INVALID** (55 941 corrupt reads of 55 941: stale 1 MB payloads under
> 10 MB labels). Run 2 is **INVALID in its entirety** — two matrix processes interleaved on one host.
> Per-row classifications: [`data/e2e-run1/STATUS.md`](data/e2e-run1/STATUS.md) and
> [`data/e2e/STATUS.md`](data/e2e/STATUS.md).
>
> Sections 6–11 and 13–19 below remain unpopulated and should be read as "not measured", not as pending.

Plan and pre-registered hypotheses: [`end-to-end-jvm-plan.md`](end-to-end-jvm-plan.md).
Capacity model: [`memoryguard-production-analysis.md`](memoryguard-production-analysis.md).
Raw data: [`data/e2e/`](data/e2e/).

> **Sections 6–11 and 13–19 are populated from the runs.** Sections 1–5, 3a and 12 are final. Where a
> section says something was not measured, it was not measured.
>
> **Read §3a first.** It establishes that the configuration every favourable FastCache number in this
> document depends on is not currently reachable by a JVM application using the shipped product.

---

## 1. What this experiment had to decide

Every advantage FastCache has shown so far is a *cache-level* advantage. This experiment exists because
one fact sits between that and any product claim:

> `WireClient.get()` returns a `byte[]`. The sidecar keeps the payload off the application heap **at
> rest**, then hands the application a full-sized heap array **on every hit**.

If a service reading 10 MB values allocates a 10 MB humongous array per request, the off-heap design may
simply move heap pressure from *retention* to *allocation*. The correct product claim would then shrink
from *"eliminates JVM heap pressure"* to *"keeps the cache resident off-heap"* — two different claims that
must not be conflated.

---

## 2. Harness

Spring Boot 3.3.4 service, one endpoint, four interchangeable cache backends:

```
HTTP GET /documents/{id} → DocumentController → DocumentService → Backend → LargeResponse → response
```

The service runs in its own JVM; the load generator runs in another. Every heap, GC and allocation figure
belongs to the service. Driving load in-process would mix the generator's allocations into the exact
measurement this experiment is for.

| Arm | Cached representation | What a hit returns |
|---|---|---|
| `caffeine` | live object, app heap | same instance, no copy |
| `fastcache-embedded` | live object, app heap (`putReference`) | same instance, no copy |
| `fastcache-sidecar` | off-heap, second JVM | fresh `byte[]`, then decoded |
| `redis` | server memory, separate process | fresh `byte[]`, then decoded |

`Backend` exposes `getEncoded()` and `getReference()` as separate methods rather than one uniform `get()`,
so the service can time transport and decoding independently. Collapsing them would hide whether the
sidecar spends its transport advantage on decoding what it transported.

---

## 3. Two harness defects found and fixed before collecting results

Both were mine, both were caught by looking at numbers that didn't make sense, and both would have
produced a published result that was wrong.

### 3.1 Service teardown silently failed on Windows

The harness backgrounded the service and killed it by `$!`. Under Git Bash that is an MSYS pid which
Windows process APIs do not recognise, so `Stop-Process` succeeded while the JVM kept running. Caught by
noticing the first cell's service still holding **1.4 GB and burning CPU while the second cell was being
measured**. Every cell would have been contaminated by its predecessors.

Fixed: the service exits in-band via `/admin/shutdown`, and the harness **verifies the port is free**
before starting the next cell rather than assuming the kill worked. Confirmed: exactly three JVMs alive
during a cell (sidecar, service, generator). The first run was discarded.

### 3.2 The embedded arm was given a bound the other arms did not have

`maxEntriesPerShard` was set to `entries / 32 + 1` — which looks correct and is not. At 512 entries over
32 shards the mean is 16, so a ceiling of 17 appears generous; but murmur3 skew of 1.2–1.4× puts the
hottest shard at 19–22, so the ceiling binds *there* while the cache sits half empty.

Measured with the wrong bound: **204 of 512 entries resident**, throughput collapsed to 141 req/s —
essentially the miss-bound rate of 8 threads ÷ 50 ms. Meanwhile Caffeine's 2 GB weight bound never binds
for a 512 MB working set. Arm B was thrashing; arm A was not.

Fixed by raising the ceiling so it cannot bind. That run was discarded too.

**The underlying constraint is a real product finding and is reported as one in §18**, separately from the
harness error: the embedded path can *only* be bounded by entries-per-shard, because `putReference`
reports a zero off-heap footprint and the byte budget does not apply to it. Sizing that bound requires
reasoning about hash skew across 32 shards, and getting it wrong degrades silently into a miss storm
rather than an error.

---

---

## 3a. A structural finding that precedes every measurement here

Found while checking whether FastCache's cross-process single-flight was reachable from the benchmark.

**There is no Java client for the FastCache sidecar protocol in the product.**

Verified three ways:

1. The only product file under `src/main` containing socket-connection code is
   `fastcache-engine/.../net/FastCacheServer.java` — which *accepts* connections. Nothing in the engine or
   the Spring starter *opens* one.
2. `FastCacheAutoConfiguration` constructs a `ShardedStorageEngine` directly. The Spring integration is
   in-process by construction; it has no remote mode.
3. `FastCacheProperties.Server` does expose a host and port, but its own Javadoc says what for:
   *"Embedded sidecar listener: lets Python processes on the same host share this JVM's cache."*

The arrow points the other way. The socket exists so **Python can consume a JVM's cache**, not so a JVM
can consume a sidecar's.

### What that means for everything measured in this document

A JVM application today has exactly two supported options:

| Option | Where payloads live | Off-heap benefit |
|---|---|---|
| Spring starter (`@FastCache`, `@Cacheable`) | **application heap**, via `putReference` | **none** |
| Embed the engine and serve it on a port | application heap; the port is for Python clients | **none** |

The third configuration — JVM application → socket → off-heap sidecar — **is not something the product
offers**. `WireClient`, used by arm C throughout this validation, was written for the benchmark. It is
about 200 lines and the protocol is well documented, so this is a gap rather than an impossibility; but
as the product stands, **a JVM team cannot obtain the configuration that produces every favourable number
FastCache has**.

This does not invalidate the measurements. It relocates them: they describe what the architecture *could*
deliver to JVM users, not what it *does*. Every sidecar result below should be read with that prefix.

It also explains an otherwise odd pattern in the earlier rounds — embedded FastCache measuring at parity
with or worse than Caffeine on heap and GC, while the sidecar measured far better. Those are not two
configurations of one product for one audience. They are the JVM product and the Python product, and only
the second one is off-heap.

### Consequence for the stampede comparison (§15)

`OP_REFRESH_LEASE` / `OP_REFRESH_DONE` implement host-wide single-flight in the protocol, and
`SidecarProtocolTest` covers them. `python/fastcache_ai/client.py` imports and uses both. No Java code
does. So FastCache's cross-process stampede collapse — the capability that genuinely distinguishes it
from "Redis plus ten lines of application locking" — is **available to Python callers and not to JVM
callers**, and §15 measures it accordingly.

---

## 4. Configuration

| | |
|---|---|
| JDK | Temurin 21.0.11, G1 (default) |
| Service heap | `-Xmx4g` (`-Xmx1g` / `-Xmx512m` in §14) |
| Direct memory | `-XX:MaxDirectMemorySize=2g` |
| Sidecar | separate JVM, `-Xmx512m`, 2 GB off-heap budget |
| Redis | Memurai 4.1.2 (`redis_version:7.2.5`), loopback, `save ""`, `appendonly no`, `allkeys-lru` |
| Working set | ~512 MB per payload size (512 × 1 MB, 51 × 10 MB, 20 × 25 MB) |
| Miss cost | 50 ms simulated processing |
| Warmup | 15 s, discarded; GC/allocation baseline reset after it |
| Measurement | 45 s per cell |
| JFR | `settings=profile`, per cell, for §13 |

**`--reject-ratio 1.0` throughout.** With shipped defaults the guard would refuse to hold the working set
at all (see the capacity analysis). Every FastCache number here therefore describes a configuration the
product does not ship, and is labelled as such wherever it appears.

**JFR profiling was enabled for every arm**, so its overhead is charged uniformly and comparisons hold.
Absolute latencies are inflated relative to a no-JFR run by an amount not separately measured.

---

## 5. Platform note

Run on Windows against Memurai, not on Linux against native Redis. The previous round established that
this distinction matters **below ~1 MB** (where native Redis is materially faster) and **not at 10 MB and
above** (0.26× on Windows against 0.24× on Linux). Since this experiment's decisive payload sizes are
10 MB and 25 MB, the platform is adequate for the FastCache-vs-Redis comparison at those sizes, and the
1 MB Redis figures here should be read as pessimistic for Redis.

---

## 12. Response mode — can the `byte[]` be avoided at all?

Investigated, not implemented, as instructed. Two API facts, both verified from the compiled classes
rather than from memory.

### Is it technically possible?

**On a servlet stack: no.**

```
public abstract class jakarta.servlet.ServletOutputStream extends java.io.OutputStream
```

`ServletOutputStream` inherits only `write(int)`, `write(byte[])` and `write(byte[], int, int)`. There is
no `ByteBuffer` overload. **Spring MVC on Tomcat cannot write a response body from off-heap memory** —
the bytes must become a heap array before they reach the socket. This experiment's service is a servlet
application, so the `byte[]` in the measurement below is not an artefact of how the harness was written;
it is the only thing the stack permits.

**On a reactive stack: yes.** `spring-core` provides:

```
DataBuffer DataBufferFactory.wrap(java.nio.ByteBuffer)
boolean    DataBufferFactory.isDirect()
```

A `NettyDataBufferFactory` producing direct buffers can carry a wrapped off-heap `ByteBuffer` to the
socket without a heap copy. So the path `off-heap slot → direct ByteBuffer → HTTP response` is achievable
on WebFlux/Netty and unachievable on Spring MVC/Tomcat.

### What API changes would be required?

1. **A client read that does not allocate.** `WireClient.get` uses `DataInputStream.readFully(byte[])`,
   which cannot target off-heap memory. It would need a `get(String, ByteBuffer)` reading via
   `SocketChannel.read(ByteBuffer)` into a caller-supplied direct buffer. The wire protocol already
   supports this — it is length-prefixed raw bytes — so no protocol change is needed.
2. **A lease whose lifetime outlives the call.** The buffer must not be reused until the response has been
   written, which on a reactive stack is after the handler returns. The existing in-process `Lease` is
   explicitly *"not thread-safe by design. A lease belongs to the virtual thread that took it"* — exactly
   the property an async response breaks. This is the hard part of the design, not the I/O.
3. **A direct-buffer pool.** Allocating a direct buffer per request would be *worse* than a heap array:
   `allocateDirect` zeroes the memory and is not GC-managed. A pool sized to
   `max-concurrency × payload` would be required, which reintroduces a capacity-planning problem of its
   own.

### Would it eliminate the application's heap allocation?

**Only for a service that never looks at the value.** A proxy, a CDN edge, a blob passthrough — yes,
entirely. But the moment the application deserializes the bytes into an object it can act on, the object
graph is on the heap again and the saving is limited to the transient encoded array.

That materially narrows where the idea pays: it is an optimisation for **byte-pipe services**, not for
services that process what they cache. Worth knowing before anyone builds it.

---

## 6. Steady-state results

*(populated from the run)*

## 7. Churn results

*(populated from the run)*

## 8. Concurrency results

*(populated from the run)*

## 9. Representation A vs B

*(populated from the run)*

## 10. FastCache sidecar vs Redis, end to end

*(populated from the run)*

## 11. FastCache sidecar vs Caffeine — the crossover

*(populated from the run)*

## 13. Allocation analysis (JFR)

*(populated from the run)*

## 14. Constrained heap

*(populated from the run)*

## 15. Stampede

*(populated from the run)*

## 16. Long run

*(populated from the run)*

## 17. Failure and recovery

*(populated from the run)*

## 18. Decision matrix

*(populated from the run)*

## 19. Product thesis

*(populated from the run)*
