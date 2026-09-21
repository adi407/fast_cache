# FastCache V1 — product architecture

**Design document. No code was written, no product source was modified, no benchmark was run.**

Every component below is traced to evidence. Where a component is not supported by evidence it is marked
**NOT JUSTIFIED BY CURRENT EVIDENCE** and excluded from V1 rather than argued for.

Evidence base:

| Source | What it establishes |
|---|---|
| [`final-10mib-e2e-validation.md`](../validation/final-10mib-e2e-validation.md) | The validated thesis. 10 MiB, 8 concurrent readers, end-to-end, 2.981× Redis. |
| [`native-linux-redis-validation.md`](../validation/native-linux-redis-validation.md) | Payload-size crossover, tail behaviour, write-path behaviour. |
| [`THESIS_REASSESSMENT.md`](../validation/THESIS_REASSESSMENT.md) | What was disproven; the three-architecture separation. |
| [`memoryguard-production-analysis.md`](../validation/memoryguard-production-analysis.md) | Why the shipped capacity model is unusable. |
| [`JVM_MEMORY_GC_RESULTS.md`](../benchmarks/JVM_MEMORY_GC_RESULTS.md) | Heap/GC, cross-process correctness, leak probes. |
| Source inspection | Protocol, `Lease`, `SidecarMain`, `MemoryGuard`, Python bootstrap. |

---

## 1. Product definition

### The candidate wording, tested against the evidence

> *"FastCache is a local high-throughput cache tier for large values, optimized for workloads where
> multiple consumers need to read multi-megabyte data at high aggregate bandwidth."*

Clause by clause:

| Clause | Supported? | Evidence |
|---|---|---|
| "local" | **Yes** | Loopback only. No clustering, no replication, no remote transport was ever built or measured. |
| "high-throughput" | **Yes** | 353.0 vs 118.4 req/s; 3 530 vs 1 184 MiB/s application bandwidth. |
| "cache tier" | **Yes** | Non-durable, recomputable, TTL-bounded. |
| "for large values" | **Yes, with a floor** | Validated at 10 MiB. **Contradicted below ~1 MiB**, where Redis is 17–20% faster single-client and FastCache's p99 is 1.94×–2.27× worse at 256 KiB. |
| "**multiple consumers**" | **NO — not validated** | See below. This is the one clause that must change. |
| "high aggregate bandwidth" | **Yes** | This is the mechanism: Redis's large-value ceiling is ~170 MiB/s per stream and ~1 184 MiB/s aggregate; FastCache's is ~757 and ~3 530. |

**Why "multiple consumers" fails.** In both the microbenchmark and the end-to-end run, the "8 concurrent
clients" were **8 threads inside one application JVM**, sharing one `WireClient` connection pool
(`LoadClient` drives a fixed thread pool of 8 against one Spring service; `WireClient` borrows from a
single `ArrayDeque` pool). No measurement in this repository has ever driven the sidecar from more than
one consumer *process* under load.

Cross-process *correctness* is established — a second JVM reads byte-exact values. Cross-process
*bandwidth under fan-out* is not. Writing "multiple consumers" into the product definition would be
asserting the exact thing the experiment did not test.

### The definition V1 is designed against

> **FastCache is a single-host, off-heap cache tier for multi-megabyte values. It serves JVM services
> that read the same large objects repeatedly and concurrently, and whose constraint is cache read
> bandwidth rather than cache read latency.**

The last clause is the qualifying question a prospective user should be able to answer. If a team is
comfortable with Redis's 61 ms median at 10 MiB and is not saturating ~1 GiB/s of cache reads, FastCache
offers them nothing and costs them replication, persistence, auth, failover and a client ecosystem.

---

## 2. Target user

A JVM team that:

- runs a service on a host it controls (VM, bare metal, or a pod where a sidecar container is acceptable),
- caches objects of **10 MiB and above**,
- reads them far more often than it writes them,
- serves them to concurrent request threads,
- can recompute or reload any value if the cache is empty, and
- has already found that their cache read path is bandwidth-limited.

Explicitly **not** the target user: anyone caching values under 1 MiB, anyone who needs the cache to
survive a restart, anyone who needs more than one host, and anyone whose working set fits comfortably in
the application heap (Caffeine is 30.3× faster end-to-end and ~66 000× faster per lookup).

---

## 3. Target workload

### Required characteristics — are the six sufficient?

The brief proposes: local machine, large values, multi-megabyte, repeated reads, concurrent readers,
bandwidth-sensitive, recomputable, non-durable.

**They are necessary but not sufficient.** Three more are required, each from a measurement:

7. **Values are consumed whole.** The advantage is a per-byte transport advantage. A consumer that reads
   a slice should store slices. There is no range read and §14 argues there should not be.
8. **Values are immutable once written.** Nothing in the measured workload updates a cached value in
   place, and the write path has its own behaviour (Redis is *faster* at 1 MiB SET; FastCache pays a
   `memset` per write from `allocateDirect` zeroing).
9. **The consumer tolerates ~20 MiB of allocation per read.** Measured: 20.04 MiB and 47.03 µs of GC
   pause per request, in the sidecar arm *and identically in the Redis arm*. Any cross-process cache of
   10 MiB values costs this. A service that cannot afford it needs Caffeine, not a different sidecar.

### Classification

**Primary workload — validated**

> A JVM service holding a working set of multi-megabyte, expensively-produced, whole-consumed artifacts,
> read concurrently by its own request threads at an aggregate rate above roughly 1 GiB/s.

The concrete shape that passed all filters in the thesis review: **columnar analytics batches**
(Arrow/Parquet record batches, materialised pivots, rendered report data) fanned from one producer to
many concurrent readers. Recompute cost is seconds of SQL; the batch is consumed whole by a columnar
reader; the read rate is where Redis's ceiling binds.

**Secondary workloads — plausible, not validated**

| Workload | Why plausible | What is missing |
|---|---|---|
| Document-extraction pipeline results (OCR/layout, 10–80 MiB) | Whole-consumed, expensive to recompute, fanned to several pipeline stages | No evidence these pipelines reach 1 GiB/s of cache reads. If they do not, Redis suffices. |
| Generated media, segmentation masks, depth maps (10–60 MiB) | Re-served many times, whole-consumed | Most likely belongs in a CDN or object store, which fails the "why cache at all" test. |

**Explicitly unsupported**

| Workload | Reason |
|---|---|
| Anything under 1 MiB | Redis is faster on median and 1.94×–2.27× better on p99 at 256 KiB. Measured. |
| General-purpose application caching | Disproven. Mixed-size traffic sends small values down the path where FastCache loses. |
| Replacing Caffeine in-process | Disproven four ways: heap parity to within 1 MiB, GC parity, 11% lower throughput at 1 MiB. |
| Anything needing durability, replication, failover or multi-host | Never built, never measured, and not in V1 or any planned version. |
| Session state, per-user data | Near-zero reuse; fails the "read repeatedly" characteristic. |
| Build/artifact caches | Latency-insensitive. Against a 30 s build, 61 ms vs 22 ms is noise. |
| LLM prompt/context caching | Fails on size — 25 MiB of text exceeds every model's context window. Real prompt caching is provider-side. |
| Embedding/vector stores | Fails on access pattern — queried by similarity, not fetched whole. |

---

## 4. Non-goals

Stated so they cannot quietly return:

1. **Not a general-purpose cache.** The evidence actively recommends Redis below 1 MiB.
2. **Not a Caffeine replacement.** Embedded FastCache is Caffeine with extra steps.
3. **Not a Redis replacement.** It replaces Redis *for one workload shape on one host*, and gives up
   everything else Redis does.
4. **Not an "AI cache".** No AI workload was ever benchmarked. Payload size is not a workload.
5. **Not distributed.** No clustering, no replication, no cross-host transport.
6. **Not durable.** No persistence, no AOF, no snapshot. A restart is an empty cache, by design.
7. **Not a lower-GC story.** Off-heap relocates heap pressure from retention to allocation; it does not
   remove it, and Redis performs the identical conversion at the identical per-request price.
8. **Not Redis-wire-compatible.** See §14.

---

## 5. Architecture

```
┌─ application JVM ─────────────────────────┐
│  @Cacheable / FastCacheClient             │
│        ↓                                  │
│  FastCacheClient        ← V1: TO BUILD    │
│   ├─ connection pool                      │
│   ├─ codec (byte[] ⟷ T)                   │
│   ├─ failure policy (fail-open)           │
│   └─ bootstrap (attach | spawn)           │
└───────────┬───────────────────────────────┘
            │  loopback TCP, binary framing
            │  22-byte request / 8-byte response header
┌───────────▼───────────────────────────────┐
│  FastCache sidecar JVM   ← EXISTS         │
│   ├─ FastCacheServer (accept loop)        │
│   ├─ ClientSession (gathering write)      │
│   ├─ ShardedStorageEngine (32 shards)     │
│   ├─ MemoryGuard        ← V1: REDESIGN    │
│   └─ off-heap slots (direct ByteBuffer)   │
└───────────────────────────────────────────┘
```

### What already exists and must not be rebuilt

Inspection of the working tree, not memory:

| Component | State |
|---|---|
| Wire protocol v2 | **Complete.** 11 opcodes, 9 response statuses, fixed-width framing. `Protocol.java`. |
| Server accept loop, thread-per-connection | **Complete.** `FastCacheServer`, virtual threads. |
| Zero-copy server read path | **Complete.** Single gathering `channel.write` from the off-heap slot. `ClientSession:177-189`. |
| Zero-copy server write path | **Complete.** Socket read directly into the destination slot. `ClientSession:201-218`. |
| Sharded storage, LRU, TTL, stale-while-revalidate | **Complete.** |
| Reference-counted `Lease` | **Complete**, and in-process only — see §10. |
| Sidecar CLI, `FASTCACHE_READY` handshake, atomic discovery file, idle watchdog, orphan watchdog (`--parent-pid`), ephemeral port | **Complete.** `SidecarMain`. |
| Cross-process single-flight (`OP_REFRESH_LEASE`/`OP_REFRESH_DONE`) | **Complete** on the wire, **used only by Python**. |
| Python client and bootstrap | **Complete**, 70 integration tests. |
| **Java client** | **Does not exist.** `WireClient` is ~200 lines of benchmark code in `fastcache-benchmarks`. |
| **Usable capacity model** | **Does not exist.** Honours 5.1%–6.3% of a configured budget on an ordinary host. |

**The V1 gap is two components, not a system.** That is the single most important architectural fact in
this document: the sidecar lifecycle problem the brief asks to design in §5 has already been solved once,
in Python, and the Java client should implement the *same three-step contract* rather than invent one.

---

## 6. Java client

### Design principle

The validated advantage is **per-byte transport**. Every API decision is judged by whether it preserves
bytes-per-second from socket to consumer. This is why the API is not a Redis API: Redis's surface is
shaped by many small operations, and this product has one operation that matters — move one large value.

### V1 surface

```java
public interface FastCacheClient extends AutoCloseable {

    byte[] get(String key);                       // null on miss
    boolean exists(String key);
    void put(String key, byte[] value, Duration ttl);
    void evict(String key);
    void clear();

    Stats stats();
}
```

Five operations plus stats. Every one maps to an opcode that already exists (`OP_GET`, `OP_PUT`,
`OP_DELETE`, `OP_FLUSH`, `OP_STATS`); `exists` is a `get` whose result is discarded until the protocol
gains a metadata-only read (see §16 of the deferred list).

`get` returning `byte[]` is deliberate: **it is exactly what was measured.** The 2.981× advantage was
produced by a client that allocates a payload-sized array per read. Shipping the measured shape first
means V1's behaviour is the behaviour in the report.

### The read-shape options, evaluated

| Option | Allocation per read | GC | Lifetime safety | Spring compatible | Throughput | Verdict |
|---|---|---|---|---|---|---|
| **A — `byte[]`** | 1 payload-sized array | 20.04 MiB/req, 47.03 µs pause/req (**measured**) | trivial — the array is the caller's | yes | **the measured 3 530 MiB/s** | **V1** |
| **B — `int get(key, ByteBuffer dst)`** | zero, with a caller-supplied pooled buffer | eliminates the per-read array; the decoded object graph remains | caller owns the buffer; needs a pool sized `maxConcurrency × payload` | yes, but Spring's `Cache` returns `Object` so the adapter re-materialises | untested | **Later** |
| **C — `void get(key, WritableByteChannel sink)`** | zero heap for a pure pipe | none on the payload | scoped to the call | no — Spring has no streaming cache contract | untested | **Later** |
| **D — direct `ByteBuffer` pool** | zero steady-state | `allocateDirect` zeroes on allocation and is not GC-managed | pool exhaustion becomes a capacity problem | n/a (infrastructure for B/C) | untested | **Later**, required by B and C |
| **E — codec against leased off-heap memory** | zero | none | — | — | — | **IMPOSSIBLE across a process boundary** |

**Option E does not exist for this product, and the reason matters.** `Lease` is a reference count over
a native slot *in the engine's own address space* — its Javadoc says it "belongs to the virtual thread
that took it". A client in a different process cannot hold one. Bytes must cross the socket into the
client's address space, so there is no path to zero-copy client access without shared memory (`mmap`),
which is a different architecture and is listed in §22.

Option E is available to the **embedded** engine, which is the configuration the evidence says has no
reason to exist.

### Serialization

`byte[]`-native at the wire, with a pluggable codec above it:

```java
public interface FastCacheCodec<T> {
    byte[] encode(T value);
    T decode(byte[] bytes);
}
```

Three constraints, all evidence-driven:

1. **No default that costs more than the advantage.** FastCache saves 39.6 ms per 10 MiB read against
   Redis. A serializer that costs more than that has spent the product. JDK serialization must not be
   the default and should not be offered.
2. **V1 ships no codec at all.** The client surface is `byte[]`; the Spring adapter (§7) requires one and
   the user supplies it. This is not laziness — the benchmark moved raw `byte[]` through a hand-rolled
   binary codec, so **the cost of any real serializer at 10 MiB is unmeasured**, and picking a default
   would be picking one blind.
3. **Every published figure must state whether it includes serialization.** The 2.981× does not.

### Async API

**NOT JUSTIFIED BY CURRENT EVIDENCE.** The validated workload is a blocking servlet stack; no async path
was built or measured. Adding one would also multiply the connection-pool and lifetime design. Deferred.

### Protocol constraints the client must live within

Read from `Protocol.java`, and they shape the pool design:

- **No pipelining.** `ClientSession` is strictly request→response per connection, so in-flight operations
  and sockets are 1:1. Concurrency is bought with sockets.
- **No multi-get.** See §14 for why this is correct for this workload.
- **Max key 65 535 UTF-8 bytes.**
- **`flags` is an opaque client codec byte, echoed back on GET** — the right place for a codec identifier.
- **No authentication.** See §18.

---

## 7. Spring integration

Two beans, selected by configuration, never both active:

```
fastcache.mode = embedded   → FastCacheManager       (exists today, in-process, on-heap)
fastcache.mode = sidecar    → FastCacheRemoteManager (V1, this document)
```

**They must be mutually exclusive and the choice must be loud.** Silently getting the embedded manager
while believing you have the sidecar is how a team would benchmark Product A and publish Product B's
numbers — which is, precisely, the confusion this whole validation programme existed to untangle.

| Annotation | Mapping | Notes |
|---|---|---|
| `@Cacheable` | `get` → miss → invoke → `put` | Straightforward. |
| `@CachePut` | invoke → `put` | Also the only way to refresh a TTL: the protocol has no TTL-update opcode. |
| `@CacheEvict(key)` | `OP_DELETE` | |
| `@CacheEvict(allEntries=true)` | `OP_FLUSH` per cache namespace | Namespace is a key prefix (§15), so a scoped flush is `OP_SCAN` + delete in a loop over the wire. Documented as O(n) round trips. |
| `@Cacheable(sync = true)` | see below | |

### `sync = true`

Spring's contract is `Cache.get(key, Callable)`: the caller blocks until a value exists, and the loader
runs once.

**V1 provides per-JVM coalescing only.** A `ConcurrentHashMap<key, CompletableFuture<T>>` in front of the
remote call: N threads in this JVM produce one loader execution and one `put`. This satisfies Spring's
contract, matches what Caffeine provides, and is a few dozen lines.

**V1 does not use `OP_REFRESH_LEASE`.** The thesis review concluded cross-process single-flight is "an
implementation capability, not a product differentiator" — Redis needs roughly ten lines of application
locking to match it, and the difference is convenience. Adding it to V1 means designing leader-death
handling, a bounded follower poll, and lease-expiry semantics, for a capability that does not sell.

The honest counter-argument, recorded so the decision can be revisited: **a sidecar restart returns an
empty cache**, so every key stampedes at once, and at 10 MiB with an expensive loader that is a real
event. Per-JVM coalescing bounds it to one loader per key *per application instance*, which for a
single-service deployment — the target deployment — is the same thing. It stops being the same thing
with multiple application instances on one host, which is the multi-process case §1 says is unvalidated.
Deferred on the same evidence.

---

## 8. Sidecar lifecycle

**The design already exists. V1 implements the existing contract in Java rather than inventing one.**

`SidecarMain` and the Python `bootstrap.py` already define a three-step protocol that has been in
production use:

1. **Explicit address wins.** `fastcache.sidecar.host` / `.port` — how a fleet points at one shared
   sidecar.
2. **Discovery file.** `~/.fastcache/sidecar.json`, written atomically (temp + `ATOMIC_MOVE`). If it
   names a port that answers `OP_PING`, attach.
3. **Spawn one**, guarded by an **OS-level file lock** (`~/.fastcache/sidecar.lock`), block on the
   `FASTCACHE_READY port=<p> pid=<pid>` handshake on stdout.

Everything the brief asks about in Phase 5 is already answered by that machinery:

| Concern | Existing mechanism |
|---|---|
| Automatic startup | step 3 |
| Existing sidecar detection | step 2, plus a `PING` before trusting the file |
| PID ownership | handshake reports `pid=`; `--parent-pid` makes the sidecar watch its parent |
| Port selection | `--port 0` → ephemeral, reported in the handshake |
| Lock files | `sidecar.lock`, advisory, non-blocking with a deadline so a killed holder cannot wedge the host |
| Crash recovery (client dies) | `--parent-pid` + `--heartbeat-timeout`; a `kill -9`'d parent is noticed within the window |
| Stale process cleanup | `--idle-timeout` (default 30m) self-terminates an abandoned engine |
| Graceful shutdown | `OP_CLOSE` per connection, then drain |
| Multiple applications on one host | the discovery file is the rendezvous; they share one sidecar and one warm cache |

### The one decision V1 must make differently from Python

**Auto-start defaults to OFF for the JVM.**

Python auto-starts because a Python process has no JVM. A Spring application *is* a JVM, and a Spring
application that spawns, supervises, reaps and restarts a second JVM has taken on an operational
responsibility its process manager already provides better.

| Mode | Default | Use |
|---|---|---|
| `attach` | **default** | Sidecar is started by systemd, a Docker container, or a Kubernetes sidecar container. The application connects and fails fast at startup if it cannot. |
| `attach-or-spawn` | opt-in, one property | Local development and tests. Uses the full three-step bootstrap above. |

Docker and Kubernetes need **no new mechanism**: the sidecar is a container in the same pod sharing the
network namespace, and `attach` to `127.0.0.1:<port>` is the whole integration. This is the native idiom
and it is why orchestration complexity is not being added.

---

## 9. Failure semantics

**The governing rule: cache failure must never become application corruption.** The protocol already
distinguishes the failure modes, which is what makes a correct policy cheap.

| Event | Wire signal | V1 behaviour |
|---|---|---|
| **Sidecar unavailable at startup** | connect refused | `attach` mode: **fail fast**, refuse to start. A service silently running with no cache is worse than one that will not start. `attach-or-spawn`: spawn it. |
| **Sidecar unavailable at runtime** | connect refused | **Fail open.** Read → miss; write → dropped and counted. Open a circuit breaker so a dead sidecar costs one probe per interval, not one connect timeout per request. |
| **Sidecar crashes** | connection reset | Discard the socket, do not return it to the pool, reconnect lazily with jittered backoff. **On reconnect the cache is empty** — this is the stampede event in §7. |
| **Connection breaks mid-read** | short read / `IOException` | **Discard the connection and treat as a miss.** Never return a partial value. A truncated 10 MiB payload that reaches the application as a valid `byte[]` is the corruption this rule exists to prevent — and the end-to-end harness proved the failure mode is real, having once served 1 MiB payloads under 10 MiB labels for a whole run. |
| **Cache full / memory pressure** | `ST_REJECTED_MEMORY` | **Not an error.** A normal, expected outcome. Surfaced as a distinct counter (`rejectedWrites`), never as an exception, or operators will read a healthy shedding cache as a broken one. After §11 this should become rare, because the guard evicts to fit rather than refusing. |
| **Payload exceeds maximum** | `ST_REJECTED_TOO_LARGE` | **Throw.** This one *is* a programming error: the caller asked to store something the cache is configured to refuse, and silently dropping it would make a cache that never hits look like a cache that is merely cold. |
| **Allocation failure** | `ST_REJECTED_ALLOCATION` | Treat as `ST_REJECTED_MEMORY`, counted separately. The engine's last-line OOM defence. |
| **Sidecar shutting down** | `ST_REJECTED_SHUTDOWN` | Fail open, trip the breaker. |
| **Malformed request** | `ST_BAD_REQUEST` | **Throw.** Client bug or version skew; must be loud. |
| **Server error** | `ST_SERVER_ERROR` | Fail open, count, log once per breaker window. |
| **Stale hit** | `ST_STALE` | **Return the value.** That is what the grace window is for. Count it; V1 does not act on the refresh signal (that is `OP_REFRESH_LEASE`, deferred). |
| **Application restarts** | — | New connections, cache intact and warm. The main reason `attach` is the default. |

### Timeouts

**A fixed timeout is wrong for this product and would break it.** A 10 MiB read legitimately takes
21.9 ms at p50 and 41.9 ms at p99, and a 50 MiB read takes proportionally longer. A conventional 100 ms
socket timeout would abort real reads under load.

V1 uses a **size-aware deadline**: a fixed connect/handshake budget plus a transfer budget derived from
the response's length prefix, which the protocol supplies in the 8-byte header *before* the body arrives.
The floor must be generous — the measured p99 is the starting point, not the target.

---

## 10. Zero-copy terminology

**The phrase is banned from unqualified use.** Five layers, answered from source:

| Layer | Definition | FastCache V1 | Evidence |
|---|---|---|---|
| **Storage zero-copy** | The payload is never copied between arrival and its resting place. | **Yes** | `ClientSession:201-218` reads the socket directly into the destination off-heap slot. Caveat: `allocateDirect` zeroes the slot first — a full-payload `memset` per write Redis does not pay. |
| **Transport zero-copy** | The payload goes from its resting place to the socket without an intermediate buffer or a trip through the Java heap. | **Yes** | `ClientSession:177-189`, one gathering `channel.write(new ByteBuffer[]{header, payload})`. This is the mechanism the 2.981× is attributed to — *likely*, not proven; no profiler has attributed time to `memcpy` in either server. |
| **Client zero-copy** | The value reaches the consumer without a payload-sized allocation in the consumer's address space. | **No in V1. Impossible across a process boundary without shared memory.** | `Lease` is a reference count in the *engine's* address space. Option B (§6) removes the *per-read* allocation by reusing a caller-supplied buffer, but bytes still cross the socket. |
| **Application zero-copy** | The application acts on the value without materialising an object graph. | **No, and not achievable for a service that reads its data.** | Measured: 20.04 MiB allocated per 10 MiB request — the encoded array plus the decoded graph. |
| **Response zero-copy** | Cached bytes reach the HTTP response without a heap copy. | **No on a servlet stack, structurally.** | `ServletOutputStream` inherits only `write(int)`, `write(byte[])`, `write(byte[],int,int)`. No `ByteBuffer` overload exists. Achievable only on WebFlux/Netty via `DataBufferFactory.wrap(ByteBuffer)`. |

**The one-sentence summary for any future documentation:** *FastCache is zero-copy up to the socket and
copy-bound after it.*

---

## 11. MemoryGuard redesign

**This is the V1 blocker.** Every FastCache number in this repository was measured with
`--reject-ratio 1.0`, i.e. admission control disabled. With shipped defaults the guard honours **5.1%–6.3%
of a configured budget** on an ordinary host, and one pressure pass can empty a large-value cache.

### The four defects, re-verified in the working tree

| Defect | Location | Class |
|---|---|---|
| Reads `free`, not `available` | `PhysicalMemory:42`, `getFreeMemorySize` | **Design flaw** — the API's own documentation says reclaimable page cache counts as used, so a healthy host reads 80–90%. The code acknowledges this and uses it as a threshold input anyway. |
| Gate floor `max(64 MiB, budget × 0.05)` | `MemoryGuard:139` | **Tuning issue wrapping a design flaw** — 64 MiB is six 10 MiB values; and a floor that does not scale with the budget means a bigger budget buys proportionally less protection. |
| Latching relief | `MemoryGuard:231-238`, release at 75% of floor | **Design flaw** — a controller whose actuator does not move its measured variable cannot stabilise. Shedding 300 MiB on a 16 GiB host moves the physical ratio by under two points, and the reclaimable pages that inflated it are not FastCache's. It can only oscillate or latch. |
| `Math.max(1, size × 0.10)` | `EvictionSweeper:152` | **Bug** — states "shed 10%", delivers 33% at three entries per shard and 100% at one. With 32 shards and large values, most shards hold ≤1 entry, so one pass empties the cache. The failure is worst exactly where the product claims to be strongest. |

### The replacement model

Two independent controllers with two different jobs. The current design fuses them, which is the root
cause of all four defects.

**Controller 1 — the budget. The only authority that refuses a write.**

```
reserved      bytes currently held by live entries + in-flight writes
budget        the configured capacity, in bytes
highWatermark 0.90 × budget   → begin evicting, do not refuse
lowWatermark  0.75 × budget   → stop evicting
reserve       max(maxValueBytes, 0.05 × budget) → headroom so an admitted
                                                  write can always complete
```

Admission: accept if `reserved + valueBytes ≤ budget − reserve`. Otherwise **evict to fit, then accept**.
Refuse only if eviction cannot free enough — which, for a value smaller than the budget, means the cache
is pinned by in-flight leases, not full.

This is the single most consequential change. Redis with `allkeys-lru` accepted **0 refused writes** across
every run of the comparison; FastCache refused tens of thousands. A byte-bounded cache whose answer to
being full is to reject new writes and retain old entries preserves cold data and discards hot data —
the inverse of what an LRU cache is for.

**Controller 2 — host pressure. Never refuses. Sheds and says so.**

```
available     MemAvailable from /proc/meminfo (Linux), platform probe elsewhere,
              current getFreeMemorySize reading as a last-resort fallback
target        keep `available` above max(512 MiB, 0.10 × hostTotal)
action        shed bytes from the cache, LRU-first, and enter an OBSERVABLE
              state: "shedding under host pressure"
never         refuse a write because of this controller
```

Two reasons it must not refuse. First, it is measuring a quantity the cache cannot move. Second, an
operator who configured 4 GiB and got 208 MiB must be able to see *why* without a profiler — the current
design makes host pressure masquerade as the cache being full.

The gate floor disappears entirely. It existed to stop a small engine refusing writes on a busy host; a
controller that never refuses does not need it.

**Eviction.**

```
target   = reserved − lowWatermark          // a byte figure, not an entry count
select   = LRU across ALL shards, ordered by last access, until target is met
never    = Math.max(1, ...)
```

Byte-targeted and global, not proportional and per-shard. Entries are not the resource under pressure,
and the per-shard proportional policy is what turns a shed into a wipe when entries are large and few.

### Interaction with sharding

Sharding exists for **lock distribution**, not for capacity. `Shard` uses a `StampedLock` so maintenance
serialises against maintenance. Capacity accounting must therefore be **global** (one byte counter for
the engine) while *locking* stays per-shard. The current design leaks the sharding — a locking decision —
into the capacity model, which is why 32 shards and 20 entries interact catastrophically.

Concretely: eviction takes a global byte target, then walks shards in LRU order taking each shard's write
stamp only while removing from it. No shard-local proportional slice.

---

## 12. Capacity model

Bytes are the primitive. Entries are a diagnostic.

```
configured capacity = 8 GiB
current usage       = 6.7 GiB      (live entries + in-flight writes)
available capacity  = 1.3 GiB
entries             = 684          ← reported, never the bound
```

| Quantity | Unit | Role |
|---|---|---|
| `budget` | bytes | configured capacity; the admission authority |
| `reserved` | bytes | live payload + in-flight writes |
| `available` | bytes | `budget − reserved − reserve` |
| `reserve` | bytes | headroom guaranteeing an admitted write can complete |
| `entries` | count | reported for diagnosis only |
| `maxEntriesPerShard` | count | **retained only as a runaway guard**, defaulted high enough it cannot bind |

`maxEntriesPerShard` is the bound that silently destroyed a benchmark run: set to `entries/32 + 1`, murmur3
skew of 1.2–1.4× made it bind on the hottest shard while the cache sat half empty, collapsing throughput
to the miss-bound rate with no error. **A bound that degrades into a miss storm rather than an error must
never be the primary capacity control.** V1 keeps it as a safety valve at a default that cannot bind, and
the byte budget does the work.

---

## 13. Large-value admission

Recommendations from the evidence, with the evidence attached. **Nothing here is a guess; sizes that were
not measured say so.**

| Size | Accept? | Evidence | Recommendation |
|---|---|---|---|
| **< 1 MiB** | Accept, **warn once per cache** | Redis 17–20% faster single-client; FastCache p99 **1.94×–2.27× worse** at 256 KiB at every concurrency tested | Do not use FastCache. The warning names Caffeine and Redis. |
| **1 MiB** | Accept | Redis faster single-client (1.20×); FastCache faster at ≥8 concurrency (0.68×) but p99 **1.09× worse** | Supported, no advantage claimed. |
| **5 MiB** | Accept | **Never measured at any layer** | **NOT JUSTIFIED BY CURRENT EVIDENCE** either way. Between the crossovers. No claim. |
| **10 MiB** | Accept | **End-to-end validated: 2.981×, 12/12 cells, <7% variance** | **The supported configuration.** |
| **25 MiB** | Accept | Microbenchmark only: p50 0.22×, p99 0.18× at 8 clients. **No end-to-end cell.** | Supported; claims must say "microbenchmark". |
| **50 MiB** | Accept | Microbenchmark only: p50 0.18×, p99 0.15×. Within the 64 MiB default `maxValueBytes`. | Supported; microbenchmark only. |
| **100 MiB** | **Reject by default** | Exceeds `DEFAULT_MAX_VALUE_BYTES` (64 MiB). Never measured at any layer. | Requires an explicit raise, and carries no evidence. |

### Should there be a minimum size?

**A warning, not a rejection.** A hard floor would break a cache holding a mixed distribution where most
values are large, and a cache that throws on a small value is a worse developer experience than one that
says "this is not what I am for". The warning fires once per cache name, not per operation.

### Should small values bypass FastCache automatically?

**No in V1. NOT JUSTIFIED BY CURRENT EVIDENCE.** Automatic tiering — a Caffeine L1 in front of the
sidecar — is a plausible design and the Python client already has an L1. But no measurement exists of a
two-tier Java path, tiering introduces coherence questions between the L1 and the shared sidecar that the
evidence cannot answer, and per-cache configuration achieves the same result declaratively: put the large
caches on the sidecar and leave the rest on Caffeine.

### Should one value be allowed to consume most of the cache?

**No.** `maxValueBytes` gains a second bound: **a single value may not exceed 25% of the budget.**
Admitting one would force evicting most of the cache to make room, which is the wipe behaviour §11 exists
to eliminate. At the target sizing (2 GiB budget, 10 MiB values) this never binds; it binds exactly in
the pathological configurations where the guard used to fail.

---

## 14. Multi-reader model

The target shape:

```
producer → FastCache → reader 1 … reader N
```

**With the §1 correction: N was N threads in one process, not N processes.** Multi-*process* fan-out
under load is unvalidated.

| Capability | V1? | Reasoning |
|---|---|---|
| **Immutable values** | **Yes — already true, make it explicit** | A `put` replaces; nothing mutates in place. Making immutability a documented guarantee costs nothing and is what makes concurrent reads safe without coordination. |
| **Reference-counted leases** | **Yes, server-side — already exist; not exposed** | `Lease` is what lets the gathering write stream from a slot while an evictor runs. Necessary, invisible, and not a client concept (§10). |
| **Shared read buffers** | **No** | Requires shared memory. Different architecture; §22. |
| **Streaming reads** | **No in V1** | Option C in §6. Justified in principle for byte-pipe consumers, unmeasured, and Spring has no streaming cache contract. Deferred. |
| **Range reads** | **NO — NOT JUSTIFIED, and contradicts the workload** | The target workload is defined by whole-consumption (§3, characteristic 7). A consumer reading ranges should store ranges as separate keys. Adding range reads would invite the access pattern the product is worst at. |

Nothing in this section requires new work for V1. The multi-reader model is *already* what the engine
does; V1's job is not to break it.

---

## 15. Python position

| | Java | Python |
|---|---|---|
| Client exists | **No** | **Yes**, 70 integration tests, 3.10–3.13 |
| Benchmarked against Redis | **Yes**, end-to-end, 2.981× at 10 MiB | **Never** |
| Single-flight reachable | No | Yes (`OP_REFRESH_LEASE`) |
| Structural argument | Bandwidth ceiling | Worker-process duplication: N workers × 25 MiB in-process becomes 1 × 25 MiB |
| Deployment cost | none (already a JVM) | **requires a JDK 21+ on a Python host** |

**Position: Java is the primary surface. Python is a maintained secondary surface with an unestablished
competitive position.**

The reasoning, stated plainly rather than by preference:

- The *validated* advantage is on the Java path. Product decisions should follow evidence, and all of it
  is Java.
- Python's structural argument — one shared copy instead of one per Gunicorn worker — is real, **and is
  equally true of Redis**, which is why it is not a differentiator on its own. The differentiator would
  again be bandwidth, and `fastcache_ai` versus `redis-py` at 10 MiB has never been run.
- CPython refcounts rather than collecting, so the GC-relief argument that motivates off-heap on the JVM
  **does not transfer** and must stop being repeated for the Python path.
- Requiring a JDK on a Python deployment is an adoption cost Redis does not charge.

**V1 does not change the Python client and does not invest in it.** It is not a separate product surface;
it is the same sidecar with a second client. If its competitive position is ever wanted, the experiment
is one day (`fastcache_ai` vs `redis-py`, 1/10/25 MiB) and it is not scheduled.

---

## 16. API

The complete V1 surface. Everything not listed is deferred.

### Client

```java
byte[]  get(String key);
boolean exists(String key);
void    put(String key, byte[] value, Duration ttl);
void    evict(String key);
void    clear();
Stats   stats();
```

### Spring

```java
@Cacheable            @Cacheable(sync = true)     // per-JVM coalescing
@CachePut             @CacheEvict                  // key and allEntries
```

### Configuration

```properties
fastcache.mode                     = sidecar        # or: embedded
fastcache.sidecar.host             = 127.0.0.1
fastcache.sidecar.port             = 7431
fastcache.sidecar.bootstrap        = attach         # or: attach-or-spawn
fastcache.sidecar.connect-timeout  = 5s
fastcache.sidecar.pool.max         = 32             # 1:1 with in-flight ops; no pipelining
fastcache.sidecar.fail-open        = true
fastcache.caches.<name>.ttl        = 15m
fastcache.caches.<name>.codec      = <bean name>
```

### Caching semantics — deliberately small

| Concept | V1 |
|---|---|
| **TTL** | Per-cache `Duration`, passed on `OP_PUT`. No TTL-update opcode exists, so `@CachePut` is how a TTL is refreshed. |
| **Stale reads** | `ST_STALE` returns a usable value from the grace window (default 2 s). V1 returns it and counts it; it does not act on the refresh signal. |
| **Eviction** | Entirely server-side, LRU, byte-targeted (§11). The application has no control and no visibility beyond `stats()`. **This is a real difference from Caffeine that teams will trip over** and must be documented, not softened. |
| **Invalidation** | Explicit only: `evict(key)` or `clear()`. No pub/sub, no change notification. |
| **Consistency** | Last write wins. No CAS, no transactions, no atomicity across keys. Immutable values (§14) make this sufficient for the target workload. |
| **Clear** | `OP_FLUSH` engine-wide; per-namespace clear is scan-and-delete, O(n) round trips. |
| **Namespace** | A key prefix, `<cacheName>:<key>`. Not a server concept. |
| **Key limits** | 65 535 UTF-8 bytes (protocol). |
| **Value limits** | `min(maxValueBytes, 25% of budget)` (§13). |

---

## 17. Observability

Of the brief's list, these earn their place because each one answers a question an operator will actually
ask about *this* product:

| Metric | Why it matters here |
|---|---|
| `hit` / `miss` / hit ratio | The baseline. Also the only way to notice a silently fail-open cache. |
| **`bytesRead` / `bytesWritten`** | **The primary metric for this product.** Operations/second is the wrong unit when the value proposition is bytes/second. |
| **`readBandwidth` (MiB/s)** | The number a user adopted FastCache for. If it is below ~1 GiB/s, they did not need it. |
| `p50` / `p95` / `p99` read latency | Tail is the known weakness below 10 MiB; it must be visible. |
| `rejectedWrites`, by status | `ST_REJECTED_MEMORY` vs `TOO_LARGE` vs `ALLOCATION` are different operator actions. Must be separable. |
| `evictions` (**bytes and count**) | Bytes is the primitive (§12). Count alone hid the wipe behaviour. |
| **`shedding` state + duration** | The observable state §11 introduces. The single metric that would have made the capacity defect visible in an afternoon rather than after a soak. |
| `residentBytes` / `budgetBytes` / `availableBytes` | The capacity model, directly. |
| `sidecarRss` | **Measured to grow across cells** (594 → 616 → 834 MiB for a 510 MiB working set, against Redis flat at 528.7). Undiagnosed. Must be watchable in production because we cannot yet explain it. |
| `activeConnections` / `poolWaitTime` | No pipelining means sockets are the concurrency limit; exhaustion is a cliff. |
| `circuitOpen`, `reconnects` | Distinguishes "cache is cold" from "cache is gone". |
| `sidecarUptime` | A restart means an empty cache and a stampede. |

**Dropped from the brief's list:** `allocatedBytes` as a *cache* metric — the JVM already exposes it and
it belongs to the application, not the cache. Keep it in the benchmark harness, not the product.

---

## 18. Security

The current posture, from source: **loopback TCP, `protected-mode` equivalent absent, no authentication.**
The protocol has no auth opcode and `FastCacheProperties.Server` documents the listener as loopback-only.

**The trust boundary must be stated plainly in the documentation, not implied:** *any local process that
can open a socket to the sidecar port can read and delete every value in the cache.*

| Question | V1 |
|---|---|
| **Bind address** | `127.0.0.1` only. Binding a non-loopback address must be refused, not merely discouraged. |
| **Unix socket vs TCP** | **TCP in V1** — it is what was validated. AF_UNIX (JDK 16+) is strictly better here: filesystem permissions become the access control, and it may be faster on the loopback path. **Deferred, and it requires re-validation**, because the 2.981× was measured over TCP. |
| **Authentication** | **None in V1.** For the target deployment — one service plus its sidecar, in one container or one pod — an auth secret protects against a threat that is already inside the trust boundary. |
| **Filesystem permissions** | The discovery file and lock in `~/.fastcache/` must be created `0600`. They name a port that grants full cache access. **This is a V1 requirement**, not a deferral. |
| **Process ownership** | Sidecar runs as the application's user. `--parent-pid` already ties its lifetime to that process. |
| **Untrusted local users** | **Not supported.** A shared host with untrusted local users is outside the threat model; say so rather than implying otherwise. |
| **Container isolation** | The recommended deployment. Same pod, shared network namespace, loopback never leaves the pod. This is what makes "no authentication" defensible. |

No distributed security model is being built, because there is no distributed product.

---

## 19. Packaging

Target developer experience: `dependency + configuration + application starts = FastCache running`.

| Option | Assessment |
|---|---|
| **Maven dependency (`fastcache-spring-boot-starter`)** | **V1.** Already exists; gains the sidecar mode. The only thing a developer adds. |
| **Bundled engine JAR inside the starter** | **V1.** The Python wheel already ships the JAR in `_bin/`; the same artifact, as a classpath resource, makes `attach-or-spawn` work with no second download. |
| **Executable sidecar JAR** | **V1.** Already exists (`SidecarMain`). It is what production runs under systemd or in a container. |
| **Docker image** | **V1.** ~10 lines over a JRE base. It is the deployment the security model (§18) assumes and the Kubernetes story (§8) requires. |
| **Native executable (GraalVM)** | **Deferred. NOT JUSTIFIED.** Would remove JVM start-up cost, which the idle/orphan watchdogs and `attach` mode already make irrelevant, and the engine depends on virtual threads and `allocateDirect`. |

**V1 packaging is therefore: one Maven dependency carrying a bundled engine JAR, plus a published Docker
image of the same JAR.** Nothing else.

---

## 20. Caffeine / Redis / Valkey comparison

**Valkey has never been benchmarked.** It is a Redis 7.2 fork sharing the single-event-loop reply path
(`addReplyBulk` into an output buffer before `writeToClient`) that the transport hypothesis identifies as
the mechanism, so the result is *expected* to transfer — **that is an inference from architecture, not a
measurement**, and every Valkey cell below is marked accordingly.

| Dimension | Caffeine | Redis / Valkey | FastCache |
|---|---|---|---|
| **In-process latency** | **0.2 µs** lookup, 687 µs end-to-end (HTTP-bound) | n/a — not in-process | 13 213 µs lookup. **Loses by ~66 000×.** |
| **Large-value bandwidth (10 MiB, 8 concurrent)** | n/a — no transfer | Redis **170 MiB/s** per stream, 1 184 MiB/s application. Valkey *inferred same*. | **757 MiB/s** per stream, **3 530 MiB/s** application. **The distinction.** |
| **Cross-process** | **No** | Yes, and across hosts | Yes, **one host only** |
| **JVM heap retention** | 636 MiB for a 510 MiB working set | **25 MiB** | **25 MiB** — identical to Redis in the same cell. **Not a distinction.** |
| **Operational complexity** | none — a library | Mature: packages, managed services, decades of runbooks | A second JVM with no replication, no persistence, no auth, no failover, and no operational literature. **Worse than Redis.** |
| **Durability** | none | RDB + AOF, replication | **None, by design.** |
| **Clustering** | n/a | Redis Cluster, sentinel, managed offerings | **None, and none planned.** |
| **Large-value specialisation** | Bounded by heap; G1 humongous rounding wastes up to 100% at some sizes | **Degrades with size**: 418 → 142 MiB/s from 1 to 50 MiB; write p50 270 ms behind read-heavy load at 10 MiB | **Flat**: 617 → 767 MiB/s across the same range; write p50 19 ms under the same load. **The distinction.** |
| **Developer experience** | Best in class — one dependency, no process | Well understood, but a process to run, a connection string, a client library choice | One dependency + one process. Discovery, handshake and orphan reaping already built. **Unproven at scale; one validated path.** |
| **Tail latency** | 1 682 µs p99 (HTTP-bound) | **Better below 10 MiB** (1.94×–2.27× at 256 KiB) | **Better at ≥10 MiB** (p99 0.327× end-to-end) |
| **CPU per cached read** | negligible | ~8.5 ms/request (cache process) | **~3.6 ms/request — ~2.4× more efficient** |
| **Ecosystem** | Universal | Universal | Two clients, one of which does not exist yet |

### Where FastCache has a distinction

Exactly two, and they are the same mechanism seen twice:

1. **Sustained read bandwidth on multi-megabyte values** — ~750 MiB/s per stream where Redis falls to
   ~142–170, measured on two platforms, three runs each, non-overlapping ranges, reproduced end-to-end
   within 2.5%.
2. **Write latency that does not head-of-line-block behind large reads** — 19 ms against Redis's 270 ms
   at 10 MiB under a 90/10 mix. Thinly sampled (~120 SET samples); counts as the same finding, not a
   second one.

A third, supporting and newer: **~2.4× lower cache-process CPU per request**.

Everywhere else FastCache either ties (heap), loses (latency below 10 MiB, tail below 10 MiB,
in-process latency, operational maturity, durability, clustering, ecosystem), or is not comparable.

---

## 21. V1 scope

The full list lives in [`V1_SCOPE.md`](V1_SCOPE.md). Summary of the principle:

> **V1 contains only what is required for: JVM application → local FastCache sidecar → high-throughput
> multi-megabyte values.**

Two components to build (Java client, MemoryGuard redesign), one to extend (Spring starter), one to
harden (protocol/server), and packaging. Everything else is §22.

---

## 22. Explicitly deferred features

### Later — justified, sequenced after V1

| Feature | Why deferred | What would trigger it |
|---|---|---|
| `get(key, ByteBuffer)` + direct buffer pool | Addresses the measured 20.04 MiB/request allocation, but that cost did not consume the advantage | A user who is GC-bound rather than bandwidth-bound |
| Streaming read to a `WritableByteChannel` | Only pays for byte-pipe services that never deserialise | A proxy/passthrough user |
| Cross-process single-flight (`OP_REFRESH_LEASE`) | Protocol exists; not a differentiator; Java-side design is non-trivial | Multiple application instances per host |
| Unix domain socket transport | Better security posture, possibly faster — **but the 2.981× was measured over TCP** | Security requirement, or a re-validated bandwidth gain |
| Automatic Caffeine L1 tiering | No two-tier Java measurement exists; coherence questions unanswered | Evidence that mixed-size workloads matter |
| WebFlux/Netty response path (`DataBufferFactory.wrap`) | The only route to response zero-copy; only pays for services that never look at the value | A reactive byte-pipe user |
| Metadata-only `exists` opcode | Today `exists` transfers the payload | Measured cost in a real workload |
| Async/reactive client API | No async path was built or measured | A reactive user with evidence |

### Explicitly not planned

| Feature | Reason |
|---|---|
| Replication, persistence, failover, clustering | Not this product. Use Redis. |
| Redis wire compatibility | §14 below. |
| Multi-host transport | Contradicts the validated single-host thesis. |
| Range reads | Contradicts whole-consumption, the workload's defining property. |
| Shared-memory (`mmap`) client transport | The only path to true client zero-copy, and a different architecture. Named so it is not confused with V1's "zero-copy". |
| Authentication / ACLs | Solves a threat already inside the trust boundary for the target deployment. |
| Native (GraalVM) sidecar | No benefit given `attach` mode; engine depends on virtual threads. |
| Pub/sub, streams, transactions, scripting, secondary data structures | Redis features for Redis workloads. |
| An "AI cache" product surface | No AI workload has ever been benchmarked. |

### Redis compatibility — the §14 question answered

**A Redis-like API creates scope without aiding adoption, and V1 should not pursue it.**

The minimum conceptual compatibility for a migrating user is `get` / `put` / `delete` / TTL — which is
already the V1 surface, because those are the operations a cache has. That mapping is a documentation
table, not an implementation.

Against it:

- **Bulk operations do not transfer.** The protocol has no pipelining (`ClientSession` is strictly
  request→response), so `MGET` would require protocol work. And at 10 MiB, batching is pointless: one
  value already saturates the link, so `MGET` of four values is four sequential transfers with extra
  framing. **NOT JUSTIFIED BY CURRENT EVIDENCE.**
- **RESP compatibility would invite the wrong users.** A drop-in Redis replacement will be dropped in
  for small-value workloads, which is where FastCache measurably loses.
- The target user is migrating *one cache*, not their whole Redis usage. They keep Redis.

---

## 23. Implementation plan

Ordered by dependency. Estimates are engineering days for one experienced engineer including tests, and
they assume the existing engine, protocol and server are kept as they are.

| # | Work | Days | Depends on | Notes |
|---|---|---:|---|---|
| 1 | **MemoryGuard redesign** (§11) — two controllers, byte-targeted global eviction, evict-to-fit, `available` probe, shedding state | **8–12** | — | **Hard blocker.** Every published number assumes this is off. Includes the regression tests the analysis specified: budget honoured within 10% on a loaded host; a cache with fewer entries than shards loses ≤20% to one pressure pass; a full cache evicts rather than refuses. |
| 2 | **Capacity model + metrics plumbing** (§12, §17) | **3–5** | 1 | Bytes as the primitive; `maxEntriesPerShard` demoted to a runaway guard. |
| 3 | **Java sidecar client** (§6) — framing, pool, timeouts, status mapping, `Stats` | **6–10** | — | Can proceed in parallel with 1. `WireClient` is a reference, not a starting point: no pooling policy, no reconnection, no failure semantics. |
| 4 | **Failure policy + circuit breaker + reconnection** (§9) | **4–6** | 3 | Size-aware deadlines are the subtle part. |
| 5 | **Protocol/server hardening** | **4–6** | 3 | Oversized/malformed frame handling, connection limits, refusing a non-loopback bind, `0600` on discovery files. |
| 6 | **Java bootstrap** (§8) — attach, discovery file, file lock, spawn, handshake | **5–8** | 3 | Ports the Python contract. Cross-platform file locking is the fiddly part. |
| 7 | **Spring integration** (§7) — `FastCacheRemoteManager`, mutual exclusion with embedded, `sync=true` coalescing, codec binding | **6–9** | 3, 4, 6 | |
| 8 | **Packaging** (§19) — bundled JAR, Docker image, starter wiring | **3–5** | 6, 7 | |
| 9 | **Documentation** — including the honest trust boundary, the size guidance, and the zero-copy layer table | **4–6** | all | The README rewrite belongs here, not before. |
| 10 | **Real-workload validation** | **5–8** | all | See §25. Not a re-run of the benchmark. |
| — | Serialization/copy reduction (§6 options B–D) | **0 in V1** | — | Deferred. |

**Total: 43–65 engineering days — roughly 9 to 13 weeks for one engineer.**

That is materially more than the "another month" the thesis review contemplated, and the estimate should
be read as the price of the decision, not as a reason to compress it. Items 1 and 3 are the critical
path and are independent, so two engineers could bring it to roughly 6–8 weeks.

---

## 24. Risks

| # | Risk | Severity | Evidence | Mitigation |
|---|---|---|---|---|
| 1 | **MemoryGuard redesign does not deliver a plannable budget**, and the shipped configuration still cannot hold a large-value working set | **Critical** | Currently honours 5.1%–6.3% of budget | Item 1 ships with its three regression tests as acceptance criteria. If they cannot be met, **V1 does not ship** — there is no product without a usable capacity model. |
| 2 | **Serialization consumes the advantage** | **High** | The 39.6 ms saving is over raw `byte[]`. A real serializer's cost at 10 MiB is **unmeasured**. | §25 measures it before any performance claim is republished. V1 ships no default codec precisely so this cannot be hidden. |
| 3 | **Multi-process fan-out does not reproduce the advantage** | **High** | All concurrency measured was 8 threads in one JVM sharing a pool (§1) | §25. This is the clause that had to be removed from the product definition. |
| 4 | **Sidecar RSS growth is a real leak** | **Medium** | 594 → 616 → 834 MiB across three cells for a 510 MiB working set; Redis flat at 528.7 | Undiagnosed. Per-operation leak probes are 8/8 clean, so it is not a per-op leak. §25. |
| 5 | **The market is too narrow to justify 9–13 weeks** | **Medium** | One workload shape in eleven examined | Unchanged by the validation. This is a business decision, not a technical one, and it should be made before item 1 starts. |
| 6 | **No pipelining becomes a throughput ceiling** | **Low for the target workload** | Sockets are 1:1 with in-flight operations | At 10 MiB a single value dominates the link; irrelevant until small values matter, which they should not. |
| 7 | **Unexplained decode cost** — sidecar decode p50 3 747 µs vs Redis 1 227 µs for identical bytes through identical code | **Low** | Measured; unattributed | Hypothesis is CPU contention at 3× the request rate on 4 vCPU. Unproven. Would be resolved by §25's larger host. |
| 8 | **`attach` default surprises developers** | **Low** | — | `attach-or-spawn` is one property, and the failure is loud (refuse to start) rather than silent. |

---

## 25. Remaining validation

**None of this is scheduled, and none of it should run before the business decision in Risk 5.** Ordered
by how much each could change the architecture above.

| # | Question | Why it matters | Shape |
|---|---|---|---|
| 1 | **Does the advantage survive multi-process fan-out?** | §1 had to remove "multiple consumers" from the product definition because of this. It is the difference between "a fast cache for one service" and "a shared large-value tier". | 4 consumer JVMs on one host against one sidecar, 10 MiB, versus the same against Redis. Same harness, same gates. |
| 2 | **What does a real serializer cost at 10 MiB?** | Risk 2. Could eliminate the advantage for object-graph users while leaving it intact for byte-native ones. | Jackson Smile / Kryo / protobuf against the current hand-rolled codec, standalone, no cache in the path. |
| 3 | **Does the redesigned MemoryGuard honour its budget?** | Gates V1 entirely. | The three regression tests, on a host above the physical threshold. Not a benchmark. |
| 4 | **Is the sidecar RSS growth real?** | Risk 4. A leak in a long-lived cache process is disqualifying. | 1–4 hour run at 10 MiB with RSS floor tracking. The long run that has never been done. |
| 5 | **Does the advantage hold on 16+ cores?** | The 4-vCPU runner constrains the axis where thread-per-connection and a single event loop differ most, and constrains it *against* FastCache. Would also resolve Risk 7. | The same single cell on a dedicated 16-core host. |
| 6 | **Does Valkey behave like Redis here?** | §20 marks every Valkey cell as inferred. | One arm swap in the existing harness. |
| 7 | **Where is the real payload-size floor?** | 5 MiB is unmeasured (§13) and sits between the crossovers. | 5 MiB end-to-end, same gates. |

**Do not run any of these now.** The brief for this phase is architecture, and the next decision is
whether to fund items 1–10 of §23 at all.
