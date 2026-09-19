# fastcache-benchmarks

The experiment that decides whether FastCache's off-heap design earns its keep for JVM services holding
large cached values. It is built to produce a negative result as readily as a positive one.

Read first:

- [`docs/benchmarks/OFFHEAP_MEMORY_MODEL.md`](../docs/benchmarks/OFFHEAP_MEMORY_MODEL.md) — what the code
  actually does, written before any benchmark existed.
- [`docs/benchmarks/HYPOTHESES.md`](../docs/benchmarks/HYPOTHESES.md) — the acceptance criteria, fixed
  before the first measurement.
- [`docs/benchmarks/JVM_MEMORY_GC_RESULTS.md`](../docs/benchmarks/JVM_MEMORY_GC_RESULTS.md) — results.

---

## The four arms

FastCache has **two** storage paths, so a two-way comparison would measure the wrong thing — and its
only non-Caffeine capability is one Redis already provides, so Redis belongs in the table too.

| Arm | Payload lives | Process | What it represents |
|---|---|---|---|
| `caffeine` | JVM heap | in-process | the baseline every JVM team already has |
| `fastcache-embedded` | **JVM heap** | in-process | what the Spring starter actually gives you |
| `fastcache-sidecar` | **off-heap** | second JVM | the only off-heap configuration |
| `redis` | server memory | separate server | the incumbent for cross-process caching |

`fastcache-embedded` stores values on the heap because every Spring entry point calls
`ShardedStorageEngine.putReference`, which parks the caller's object graph in the shard map with
`footprintBytes() == 0`. That is a deliberate design decision, not a bug — but it means the off-heap
thesis cannot be tested through the Spring annotations, and the benchmark says so out loud.

---

## Running it

Requires JDK 21+ and a populated `~/.m2/repository` (Caffeine and Spring are resolved from it). The
project's own modules must be built first so `target/classes` exists:

```bash
mvn -q package
```

Then:

```bash
./fastcache-benchmarks/run.sh --scenario A --implementation all --repeat 3
```

`run.sh` compiles with `javac` and runs directly. It exists because the machine these results were
produced on had a JDK but no `mvn` binary; it needs nothing that a bare JDK does not provide.

### Options

```
--scenario <A-H>        A fill, B read, C churn, D eviction, E expiration,
                        F replacement, G clear, H long churn (>=30m)
--implementation <list> caffeine,fastcache-embedded,fastcache-sidecar,redis
                        ('all' = first three; 'cross-process' = sidecar + redis)
--payload-size <list>   e.g. 256k,1m,10m,50m     (default: all seven sizes)
--budget <size>         cache memory budget, applied identically to every arm (default 1g)
--target <size>         resident payload volume to fill to (default 512m)
--duration <seconds>    scenario C/H run length (default 60)
--concurrency <n>       worker threads (default 8)
--operations <n>        scenario B operation count (default 2000)
--cycles <n>            scenario F replacement cycles (default 200)
--repeat <n>            repeat the whole matrix n times (default 1)
--reject-ratio <0..1>   sidecar memory-guard reject ratio (1.0 disables admission control)
--redis-port <n>        redis port (default 6399)
--redis-pid <pid>       redis server pid, so its RSS can be reported
--csv <path>            also write results as CSV
```

### The other entry points

```bash
BENCH_MAIN=io.fastcache.bench.LeakProbe        ./fastcache-benchmarks/run.sh
BENCH_MAIN=io.fastcache.bench.GuardProbe       ./fastcache-benchmarks/run.sh
BENCH_MAIN=io.fastcache.bench.SpringCacheBench ./fastcache-benchmarks/run.sh --loader-millis 50
BENCH_MAIN=io.fastcache.bench.CrossProcessBench ./fastcache-benchmarks/run.sh
```

| Class | Phase | Question it answers |
|---|---|---|
| `Bench` | 2, 3 | where does the memory live, and what does GC cost? |
| `LeakProbe` | 6 | does every release path actually release? |
| `GuardProbe` | 7 | when the guard refuses a write, how much budget was still free? |
| `SpringCacheBench` | 4 | `@Cacheable(sync=true)` through the real `CacheManager`, both providers |
| `CrossProcessBench` | 5 | can a second process read it, and does Redis do it better? |

### The Redis arm

Redis is the honest comparison for FastCache's cross-process capability, so it runs through the identical
scenario code as the other three arms. The benchmark does **not** start or stop the server; point it at
one that is already running:

```bash
./fastcache-benchmarks/run.sh --scenario B \n    --implementation caffeine,fastcache-sidecar,redis --redis-port 6399 --redis-pid <pid>
```

`--implementation cross-process` is shorthand for the two arms that can actually share a cache between
processes (`fastcache-sidecar,redis`); Caffeine cannot appear in that comparison at all.

**Server configuration matters for fairness**, and the config used for the published results is committed
at `.redis/redis-bench.conf`. The three settings that matter:

- `save ""` and `appendonly no` — FastCache is non-durable and writes nothing to disk. Leaving Redis's
  default RDB snapshotting on would charge it for a feature FastCache does not offer.
- `maxmemory 2gb` with `maxmemory-policy allkeys-lru` — makes Redis a bounded LRU cache like the others,
  rather than an unbounded store.
- `bind 127.0.0.1` — same loopback path the FastCache sidecar uses. No container, no VM, no virtual NIC.

**The client is deliberately not Jedis or Lettuce.** `RespClient` is a minimal RESP2 client written to be
structurally identical to `WireClient`: same socket options, same 64 KiB buffered streams, same pooling,
same measurement harness. Using a mature client for one side and a hand-rolled one for the other would
fold the client-library difference into the answer. The bias this introduces runs *in Redis's favour* — a
minimal client has less overhead than netty's pipeline — which is the right direction.

Correctness was cross-checked independently with `memurai-cli`: `STRLEN` on a benchmark key returned
exactly the payload size, and `DBSIZE` matched the write count.

### JVM flags

`BENCH_JVM_FLAGS` overrides the defaults (`-Xmx4g -XX:MaxDirectMemorySize=4g`). Whatever is used is
echoed back in the benchmark's own header, so a result always carries the flags that produced it.

A GC log is always written to `fastcache-benchmarks/target/gc.log` as a second, independent record
alongside the MXBean and notification-based readings.

---

## How the measurements are taken

Three independent sources, because none is trustworthy alone:

- **MXBeans** — heap, non-heap, old-gen occupancy. Cheap, blind to native memory.
- **GC notifications** (`GarbageCollectionNotificationInfo`) — per-collection pause durations, which is
  what a p99 pause requires. `getCollectionTime()` gives only a cumulative total.
- **Process RSS** — the only reading that sees off-heap allocations, and therefore the ground truth for
  every memory claim. Read from `/proc/<pid>/status` on Linux, and via PowerShell `WorkingSet64` on
  Windows (there is no JDK API for it, and `wmic` is gone from Windows 11).

Heap figures are taken after two forced collections with a pause between them, because a single
`System.gc()` on G1 routinely leaves floating garbage. This is applied identically to every arm.

For the sidecar arm, the helper process's RSS is reported separately. Without it the arm looks as though
memory vanished; it did not, it moved.

---

## Fairness rules this code follows

- Caffeine gets `maximumWeight` with a byte-accurate `Weigher` and the same budget as FastCache — its
  strongest configuration for large values, and one FastCache has no equivalent for.
- Caffeine gets genuine per-entry TTLs via `Expiry`, not one global `expireAfterWrite`.
- Warmup is explicit and its operation count is reported, never silently skipped.
- Latency is labelled **client-observed** or **server-side** and the two are never mixed.
- No Python L1 is involved in any arm; the sidecar arm has no client-side cache, so it faces a cold
  Caffeine on equal terms.
- `--repeat` runs the whole matrix again and every run is emitted, so variability is visible rather than
  averaged away.
- The benchmark refuses to interpret its own results when
  `OffHeapAllocator.supportsDeterministicFree()` is false, and says so loudly.
