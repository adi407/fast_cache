# FastCache off-heap memory model

A code-reading record, produced **before** any benchmark was written, so that the benchmark measures the
system that exists rather than the system the README describes. No implementation was changed to produce
this document.

Read at commit `535405f`. Every claim cites the file and line it came from.

---

## 1. The headline finding: there are two storage paths, and only one is off-heap

`CachePayload` is a sealed interface with exactly two implementations
(`fastcache-engine/src/main/java/io/fastcache/engine/core/CachePayload.java:26`):

| Payload | Storage | `footprintBytes()` | Charged to off-heap budget | Reachable from |
|---|---|---|---|---|
| `CachePayload.OffHeap` | direct `ByteBuffer` | actual length | **yes** | socket protocol only |
| `CachePayload.Reference` | **live JVM object on the heap** | **`0`** | **no** | Spring / in-process only |

`CachePayload.Reference.footprintBytes()` returns a hard-coded `0`, commented: *"Deliberately zero:
on-heap references are bounded by entry count and the GC, not by the off-heap budget."*

### What this means for the hypothesis under test

The hypothesis is that FastCache helps JVM/Spring services by *keeping payloads off the JVM heap*.
Tracing which payload type the Spring layer constructs:

```
FastCacheAdapter.java:190     engine.putReference(keyFor(key), value, ttlMillis)
FastCacheAspect.java:351      engine.putReference(key, storable, ttl)
FastCacheOperations.java:58   engine.putReference(key, value, TimeSpec.parseMillis(ttlSpec))
```

Grepping the starter for `Socket|ClientSession|connect` returns **no socket usage at all**. Every Spring
entry point — `@FastCache`, `@Cacheable` through the `CacheManager` adapter, and the programmatic
`FastCacheOperations` — routes to `putReference`, whose own Javadoc
(`ShardedStorageEngine.java:277`) reads:

> "no serialization, no copy, no off-heap accounting — the caller's object graph is simply parked in the
> shard map. [...] This is the same bound Spring's own `ConcurrentMapCache` and Caffeine's bounded caches
> use."

**An embedded FastCache in a Spring application stores large values on the Java heap, exactly as Caffeine
does.** The off-heap machinery is unreachable from the Spring annotations. A payload reaches a direct
`ByteBuffer` only by arriving over TCP — from the Python client, or from a JVM client talking to a
*separate sidecar process*.

This is not a defect. It is a deliberate and well-argued decision: round-tripping an in-process object
graph through a byte buffer would add serialization cost and a copy for no benefit. But it means the
benchmark must test **three** arms, and that the embedded arm cannot show a heap advantage:

1. **Caffeine** — in-process, on-heap. Baseline.
2. **FastCache embedded** — in-process, on-heap via `putReference`. What Spring actually gives you.
3. **FastCache sidecar** — over the socket, off-heap, in a second JVM. The only configuration in which
   the off-heap thesis can be true.

---

## 2. Value lifecycle

### ALLOCATE — `ShardedStorageEngine.beginWrite(int)` (`:183`)

```
length < 0 || length > config.maxValueBytes()  -> null  (REJECTED_TOO_LARGE)
memoryGuard.tryReserve(length) == false        -> null  (REJECTED_MEMORY_PRESSURE)
allocator.allocate(length)                     -> ByteBuffer.allocateDirect
  OutOfMemoryError | RuntimeException          -> memoryGuard.release(length); null
```

Admission control runs **before** allocation; `OffHeapAllocator.allocate` explicitly documents that it
performs none of its own.

`config.maxValueBytes()` defaults to `DEFAULT_MAX_VALUE_BYTES = 64 MiB` (`EngineConfig.java:40`).
**A 100 MB payload is rejected outright by the off-heap path under default configuration.**

### WRITE — `commitWrite(...)` (`:224`)

Wraps the filled slot in `CachePayload.OffHeap.owned(...)`, which sets the reference count to **1** — the
map's ownership reference — then `shardFor(key).put(entry)`.

### READ — `acquire(key)` -> `Shard.acquire` (`:95`) -> `Lease`

```
map.get(key) == null           -> miss
!entry.isServable(now)         -> removeExact(...) + miss   (lazy TTL reclamation on the read path)
!entry.payload().tryRetain()   -> miss                      (lost the race with an evictor)
                               -> refcount +1, return Lease
```

`Lease.close()` calls `releaser.release(payload)` and is idempotent via a `closed` flag, so
try-with-resources plus an explicit close cannot double-release. `tryRetain()` CAS-loops and returns
`false` once the count reaches 0, so a released payload can never be resurrected. This is the correct
shape.

### REPLACE — `Shard.put(entry)` (`:77`)

```java
CacheEntry previous = map.put(entry.key(), entry);
if (previous != null) {
    bytes.add(-previous.footprintBytes());
    releaser.release(previous.payload());   // drops the MAP's reference, not any live lease
}
```

The displaced slot is freed only once in-flight readers have closed their leases, because `release`
decrements and frees at zero. **This is the path Scenario F exists to stress.**

### EVICT — two mechanisms, and neither bounds bytes

- **TTL**: `Shard.purgeExpired(now)` (`:169`), driven by `EvictionSweeper` every `sweepIntervalMillis`
  (default **1000 ms**, `EngineConfig.java:169`), fanned out one virtual thread per shard. Also lazily on
  the read path.
- **LRU**: `Shard.evictLeastRecentlyUsed(count)` (`:221`), triggered by
  `overCapacity()` = `map.size() > maxEntries`.

> **`maxEntriesPerShard` defaults to `100_000` (`EngineConfig.java:166`).** With 32 shards that is
> **3.2 million entries** before LRU fires. LRU here is a **count** bound, never a **byte** bound. For a
> large-value workload it is unreachable: 3.2M x 1 MB is 3.2 TB.
>
> On the off-heap path the real byte bound is `MemoryGuard`. **On the on-heap Spring path there is no byte
> bound at all** — `footprintBytes()` is 0, the guard is deliberately not consulted, and the only limits
> are the entry ceiling and the garbage collector. Caffeine's `maximumWeight` + `Weigher` has no
> equivalent here.

### RELEASE — `ShardedStorageEngine.release(CachePayload)` (`:510`)

```java
if (!payload.releaseReference()) return;        // not the last reference
if (payload instanceof CachePayload.OffHeap o) {
    allocator.free(o.buffer(), o.length());     // Unsafe.invokeCleaner
    memoryGuard.release(o.length());            // budget returned
}
```

Wrapped in `catch (RuntimeException)` logging *"Payload release failed; this leaks native memory"* — an
honest acknowledgement that this is the leak point if anything throws.

### MEMORY RECLAIM — `OffHeapAllocator.free` (`:88`)

`sun.misc.Unsafe.invokeCleaner(buffer)`, resolved once through a `MethodHandle`. If unavailable the
allocator degrades to the GC-driven `Cleaner` and logs a warning — in that mode the central design claim
is void, so **the benchmark asserts `supportsDeterministicFree() == true`** before reporting any result.

`liveSlots` / `liveBytes` are decremented **before** `invokeCleaner` is called, so a throwing free
under-reports rather than over-reports. The consequence matters: `offheap_slots` returning to zero proves
the *accounting* returned to zero, not that the OS reclaimed the pages. **RSS is the only ground truth**,
which is why every memory conclusion in the results is anchored on RSS.

---

## 3. Where memory should be released — the complete list

| # | Trigger | Path | Releases |
|---|---|---|---|
| 1 | Key overwritten | `Shard.put` -> `release(previous)` | previous slot |
| 2 | Explicit delete | `Shard.remove` -> `release` | slot |
| 3 | TTL, lazily on read | `Shard.acquire` -> `removeExact` | slot |
| 4 | TTL, by sweeper | `purgeExpired` -> `removeExact` | slot |
| 5 | LRU over capacity | `evictLeastRecentlyUsed` -> `removeExact` | slot |
| 6 | Reader finishes | `Lease.close` -> `release` | slot, iff last reference |
| 7 | Write abandoned | `abortWrite` | slot **and** budget |
| 8 | Shard flush / close | `Shard.clear` -> `release` per entry | all slots |
| 9 | JVM shutdown hook | engine `close()` | all slots |

Any of 1-9 failing to fire is a leak that will not appear on the Java heap and will not throw. Phase 6
targets each of them.

---

## 4. MemoryGuard: what actually gates a write

`MemoryGuard.isRejecting()` (`:249`) evaluates **two independent ceilings**, each with its own hysteresis
band, remembering which one rejected so that relief is judged on the same quantity:

- **Budget ceiling** — `reserved / budgetBytes`, rejects at `rejectRatio` (default **0.85**), relieves at
  `reliefRatio = max(0.05, rejectRatio - 0.07)`.
- **Machine ceiling** — `PhysicalMemory.usedRatio()`, but only once the physical gate is armed.

The gate arms when this engine's own reservation crosses
`physicalGateFloorBytes = max(64 MiB, 5% of budget)` (`:139`), disarms below 75% of that, and cannot
re-arm until `GATE_REARM_NANOS` has passed.

`PhysicalMemory.usedRatio()` is `1 - free/total`, built on
`com.sun.management.OperatingSystemMXBean.getFreeMemorySize()`. Its own Javadoc states the problem: this
is **free**, not **available** — reclaimable file-cache and standby pages count as used, so *"a perfectly
healthy host routinely reports 80-90% used."*

**The consequence Phase 7 tests:** once a cache holds more than 64 MiB off-heap, its admission decisions
are governed by a whole-machine reading that sits at 80-90% on ordinary hardware, *regardless of how much
of the configured budget is still free*. If so, the configured budget is a ceiling that cannot be reached
rather than a capacity that is honoured. The existing soak is consistent with exactly this: 316-440 MB
reserved against a 1 GB budget (31-43%) while `memory_ratio` read 0.82-0.85 and 59,741 writes were shed.

The on-heap `putReference` path deliberately bypasses the guard, and the Javadoc at `:280` explains why in
terms that anticipate this failure: gating on physical memory would mean *"on any ordinarily busy host
[...] every write would be refused, the cache would report a healthy engine with a 0% hit rate."*

---

## 5. What this model predicts, before measurement

Stated in advance so results cannot be retrofitted:

1. **FastCache embedded vs Caffeine, heap occupancy: no advantage.** Both store live references. Any
   difference is per-entry bookkeeping, not payload placement.
2. **FastCache sidecar vs Caffeine, application heap: large advantage**, because the payload lives in
   another process. Paid for with serialization and socket cost on every miss *and every hit*.
3. **100 MB payloads are rejected off-heap** under the default 64 MiB `maxValueBytes`.
4. **Neither path bounds by bytes.** An entry-count LRU of 100k/shard cannot protect a heap holding 25 MB
   values; Caffeine's `maximumWeight` can.
5. **The machine-pressure gate sheds writes on this development host**, which idles at ~80% physical used.

Predictions 1 and 2 together mean the honest question is not "off-heap or not" but **"is moving the
payload into a second process worth the round trip?"** — a different, and more interesting, question than
the hypothesis asks.
