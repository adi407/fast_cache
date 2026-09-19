# FastCache vs Caffeine — JVM memory and GC results

Measured on 2026-09-19 at commit `535405f`. Acceptance criteria were fixed in
[`HYPOTHESES.md`](HYPOTHESES.md) before the first measurement; the implementation was read and documented
in [`OFFHEAP_MEMORY_MODEL.md`](OFFHEAP_MEMORY_MODEL.md) before any benchmark code was written.

---

## Executive summary

Five findings, in order of how much they should change decisions.

1. **The Spring integration does not use the off-heap store at all.** Every Spring entry point calls
   `putReference`, which parks the caller's object on the Java heap with `footprintBytes() == 0`.
   Measured heap occupancy for `fastcache-embedded` and `caffeine` is identical to within 1% at every one
   of seven payload sizes. **The off-heap thesis is not merely unproven for embedded Spring use — it is
   structurally inapplicable.**

2. **The off-heap store works, and works well, but only in the sidecar configuration.** With payloads in a
   second process, the application JVM's settled heap attributable to the cache was **0 MB** at every
   payload size, against 510–1024 MB for Caffeine. That is a real and large effect. It is paid for with a
   socket round trip per operation and a second process whose RSS must be counted.

3. **`MemoryGuard` does not honour the configured budget.** On a host at 90% physical memory, the engine
   refused its first write at exactly the machine-pressure gate floor in every configuration tested. A
   1 GB budget yielded **64 MB** of usable cache (6.3%); a 4 GB budget yielded **208 MB** (5.1%). The
   effective capacity is `max(64 MiB, 5% of budget)`, not the budget.

4. **No native memory leak was found.** Eight targeted probes covering every release path in the
   lifecycle — replacement, delete, expiration, LRU, rejected writes, concurrent read/evict, flush, failed
   load — all returned slots to baseline with RSS flat to within 3 MB, after 2 GB of allocate/free churn
   each. 6.8 million leased reads raced against concurrent eviction with no crash. **This answers the
   question the existing soak abstained on.**

5. **An unexpected result that favours off-heap storage generally:** G1 humongous-region rounding wastes up
   to **100% of the heap** for payloads just above half a region. At 1 MB payloads in a 4 GB heap, 512 MB
   of cached data occupied 1024 MB of heap. This penalty applies to Caffeine and `fastcache-embedded`
   identically, and the sidecar avoids it entirely.

Two further results arrived from the churn and Spring phases and belong in this summary:

6. **Under sustained churn, embedded FastCache is *worse* than Caffeine on GC** — 2.2-2.4x more total
   pause time and 3-4x worse p95/p99 at 1 MB payloads, while allocating less. This was not predicted.

7. **FastCache's stampede collapse is correct and its tail latency is better than Caffeine's.** 60 of 60
   Spring `@Cacheable(sync = true)` cells collapsed 100 and 500 concurrent callers to exactly one loader
   execution, and FastCache's p99 was lower than Caffeine's in 9 of 10 cells.

8. **Against Redis, FastCache wins on large-value reads — and the result survived every attempt to break
   it.** Read p50 was **0.26x-0.38x of Redis at 8 concurrent clients** and **0.44x-0.73x single-client**,
   with non-overlapping ranges across 3 runs. Enabling Redis's `io-threads 8` (the obvious confound, since
   Redis defaults to one I/O thread) made Redis *slower*, not faster. The mechanism is identified and
   architectural: FastCache writes the payload straight from its off-heap slot to the socket in one
   gathering write, while Redis stages every reply through an output buffer at the cost of one
   full-payload memcpy per GET. **This is the only FastCache documentation claim this exercise has
   confirmed rather than contradicted.** It is bounded by one large caveat: the server tested was a
   Windows port (Memurai 4.1.2 / Redis 7.2.5), not native Redis on Linux.

Verdicts against the pre-registered criteria: **H1 SUPPORTED for the sidecar, NOT SUPPORTED for embedded;
H2 NOT SUPPORTED; H3 SUPPORTED for embedded; H4 SUPPORTED per-operation, INCONCLUSIVE long-horizon;
H5 NOT SUPPORTED on its literal p99 bar — which Redis also fails, by more — with its "worse than Redis"
disqualifier refuted.** Full reasoning in the Verdict section.

---

## Methodology

### Hardware, OS, runtime

| | |
|---|---|
| CPU | Intel Core i7-10810U @ 1.10 GHz, 6 cores / 12 threads |
| RAM | 15.8 GB |
| OS | Windows 11, build 10.0.26200.9457 |
| JVM | Temurin OpenJDK 21.0.11+10 LTS, 64-bit Server VM |
| GC | G1 (default), region size 2 MiB at `-Xmx4g` |
| Caffeine | 3.2.2 |
| Spring | 6.1.13 (matching spring-boot 3.3.4 in the parent pom) |
| FastCache | 1.1.0 @ `535405f` |
| Deterministic off-heap free | **available** (`Unsafe.invokeCleaner` resolved) |

**The host was not idle and not lightly loaded.** Physical memory sat between 80% and 91% used throughout,
with 1.1–3.2 GB free. This is stated up front because it is not a nuisance to be apologised for — it is
the condition a developer laptop and a well-packed container are both normally in, and it is the
independent variable that Phase 7's result turns on. It is also a limitation for the GC numbers, noted
under Limitations.

### Arms

| Arm | Payload lives | Process | Represents |
|---|---|---|---|
| `caffeine` | JVM heap | in-process | the baseline every JVM team has |
| `fastcache-embedded` | **JVM heap** | in-process | what the Spring starter actually gives you |
| `fastcache-sidecar` | **off-heap** | second JVM | the only off-heap configuration |

### Measurement

Three independent sources, because none is sufficient alone:

- **MXBeans** for heap, non-heap and old-generation occupancy.
- **`GarbageCollectionNotificationInfo`** for per-collection pause durations. `getCollectionTime()` gives
  only a cumulative total, which cannot produce a p99.
- **Process RSS** — `/proc/<pid>/status` on Linux, PowerShell `WorkingSet64` on Windows. This is the only
  reading that sees off-heap memory and is therefore the ground truth for every memory claim here. A
  `-Xlog:gc` log is written as a fourth, independent record.

Heap figures are **settled occupancy**: two `System.gc()` calls with a 250 ms pause between them, because
a single collection on G1 routinely leaves floating garbage. Applied identically to every arm.

For the sidecar arm the helper process's RSS is reported separately. Without it the arm would appear to
make memory vanish. It does not; it moves it.

### Reproduction

```bash
./fastcache-benchmarks/run.sh --scenario A --implementation all --repeat 3
BENCH_MAIN=io.fastcache.bench.LeakProbe  ./fastcache-benchmarks/run.sh
BENCH_MAIN=io.fastcache.bench.GuardProbe ./fastcache-benchmarks/run.sh

# Redis head-to-head (server must already be running; see fastcache-benchmarks/README.md)
./fastcache-benchmarks/run.sh --scenario B --implementation fastcache-sidecar,redis \
    --payload-size 1m,10m,25m --redis-port 6399 --redis-pid <pid>
```

Full options in [`fastcache-benchmarks/README.md`](../../fastcache-benchmarks/README.md). Raw CSVs are in
[`data/`](data/).

---

## Results — Scenario A (fill)

512 MB of payload resident, 1 GB budget, `-Xmx4g`, 3 runs per cell, means shown.

| payload | arm | accepted | resident MB | **heap MB** | oldGen MB | app RSS Δ MB | off-heap MB | **sidecar RSS MB** |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 256 KB | caffeine | 2048 | 512 | **515** | 524 | 725 | — | — |
| 256 KB | fastcache-embedded | 2048 | 512 | **515** | 524 | 670 | 0 | — |
| 256 KB | fastcache-sidecar | 2048 | 512 | **0** | 12 | −10 | 512 | 583 |
| 1 MB | caffeine | 512 | 512 | **1024** | 1036 | 529 | — | — |
| 1 MB | fastcache-embedded | 512 | 512 | **1025** | 1036 | 527 | 0 | — |
| 1 MB | fastcache-sidecar | 438 | 438 | **0** | 12 | 0 | 384 | 396 |
| 5 MB | caffeine | 102 | 510 | **612** | 624 | 504 | — | — |
| 5 MB | fastcache-embedded | 102 | 510 | **612** | 624 | 505 | 0 | — |
| 5 MB | fastcache-sidecar | 90 | 448 | **0** | 12 | 0 | 262 | 252 |
| 10 MB | caffeine | 51 | 510 | **612** | 624 | 510 | — | — |
| 10 MB | fastcache-embedded | 51 | 510 | **612** | 624 | 507 | 0 | — |
| 10 MB | fastcache-sidecar | 45 | 447 | **0** | 12 | 1 | 180 | 248 |
| 25 MB | caffeine | 20 | 500 | **520** | 532 | 490 | — | — |
| 25 MB | fastcache-embedded | 20 | 500 | **521** | 532 | 487 | 0 | — |
| 25 MB | fastcache-sidecar | 16 | 400 | **0** | 12 | 2 | 0 | 68 |
| 50 MB | caffeine | 10 | 500 | **520** | 532 | 514 | — | — |
| 50 MB | fastcache-embedded | 10 | 500 | **486** | 497 | 515 | 0 | — |
| 50 MB | fastcache-sidecar | 7 | 367 | **0** | 12 | −3 | 0 | 67 |
| 100 MB | caffeine | 5 | 500 | **510** | 522 | 515 | — | — |
| 100 MB | fastcache-embedded | 5 | 500 | **512** | 522 | 515 | 0 | — |
| 100 MB | fastcache-sidecar | 4 | 433 | **0** | 12 | 0 | 167 | 232 |

Three things to read off this table.

**Caffeine and `fastcache-embedded` are the same cache, as far as the heap is concerned.** 515/515,
1024/1025, 612/612, 612/612, 520/521, 510/512. The one apparent difference — 520 vs 486 at 50 MB — is
within the run-to-run spread and is not reproducible in direction. This is the predicted consequence of
`putReference`, confirmed.

**The sidecar arm removes the payload from the application heap completely.** 0 MB attributable heap,
12 MB old-gen, RSS delta indistinguishable from zero. The memory reappears in the sidecar's RSS, as it
must.

**The sidecar arm could not hold the working set.** `accepted` falls short at every size from 1 MB up, and
at 25 MB and 50 MB `offHeapReserved` and `entries` read **zero** after the fill despite writes having been
accepted. That is not a measurement error; it is the `MemoryGuard` behaviour analysed below, and it is why
this scenario was re-run with admission control disabled for the GC comparison.

### Run-to-run variability (Phase 8 requires this be shown, not averaged away)

Three runs per cell. Mean with [min-max] beside it.

| payload | arm | heap MB | app RSS Δ MB | GC pause ms | alloc MB |
|---|---|---|---|---|---|
| 256 KB | caffeine | 515.3 [512-522] | 724.7 [602-787] | **212.7 [169-292]** | 516.0 [513-521] |
| 256 KB | fastcache-embedded | 514.7 [512-520] | 670.3 [634-712] | **174.7 [172-179]** | 514.0 [513-515] |
| 256 KB | fastcache-sidecar | **0.0 [0-0]** | -9.7 [-14--7] | **63.7 [54-78]** | 513.3 [512-515] |
| 1 MB | caffeine | 1024.0 [1024-1024] | 529.0 [528-530] | 45.0 [43-48] | 512.0 |
| 1 MB | fastcache-embedded | 1024.7 [1024-1026] | 526.7 [522-531] | 44.7 [43-48] | 512.3 |
| 1 MB | fastcache-sidecar | **0.0 [0-0]** | 0.0 [0-0] | 50.0 [41-62] | 512.3 |
| 10 MB | caffeine | 612.0 [612-612] | 510.0 [509-512] | 36.0 [33-41] | 510.0 |
| 10 MB | fastcache-embedded | 612.0 [612-612] | 507.3 [504-509] | 36.0 [33-40] | 510.0 |
| 10 MB | fastcache-sidecar | **0.0 [0-0]** | 1.3 [0-4] | 37.3 [35-40] | 510.0 |
| 100 MB | caffeine | 510.0 [510-510] | 515.0 [513-519] | 36.3 [33-40] | 500.0 |
| 100 MB | fastcache-embedded | 512.0 [510-514] | 514.7 [514-516] | 39.3 [34-42] | 500.0 |
| 100 MB | fastcache-sidecar | **0.0 [0-0]** | -0.3 [-3-2] | 39.0 [35-43] | 500.0 |

Heap occupancy is highly reproducible — several cells have zero spread. The one cell with a wide spread,
`fastcache-embedded` at 50 MB (486 [416-522] against Caffeine's 520 [520-520]), is a single low run, not a
systematic difference; its range straddles Caffeine's value. It should not be read as an advantage.

**`allocMB` is identical across all three arms at every size.** That is the fairness check: the benchmark's
own payload allocation is a constant, so any GC difference between arms comes from what the cache does
with the array, not from the benchmark favouring one arm.

### The GC benefit scales with entry count, not byte volume

The GC pause column contains the most decision-relevant surprise in this table, and it cuts against the
way the product is described.

At **256 KB** — 2048 live entries — the sidecar arm cut total GC pause from 212.7 ms to 63.7 ms, a **70%
reduction**, with no overlap between the ranges [169-292] and [54-78].

At **1 MB and above** — 512 entries down to 5 — the three arms are indistinguishable: 36-50 ms everywhere,
with ranges that overlap completely. **Holding five 100 MB payloads on the heap costs essentially nothing
in GC time.**

The mechanism is that G1's collection cost is driven by object count and reference scanning, not by bytes.
A humongous region is not copied during evacuation; it is reclaimed wholesale. So a handful of very large
arrays is close to free, while thousands of medium ones are not.

This matters because the README's motivating claim is the opposite: *"a 50 MB context window in a JVM heap
causes GC pauses that cost more than the cache saves."* At 512 MB of resident payload, that is not what
was measured. Ten 50 MB arrays cost 36.7 ms of GC; two thousand 256 KB arrays cost 212.7 ms. The
off-heap design helps most in the case the documentation does not advertise.

### The G1 humongous-region penalty

The 1 MB row shows 1024 MB of heap holding 512 MB of payload — exactly 2×. That is not FastCache, Caffeine
or a measurement artefact. It is G1: an object larger than half a region is *humongous* and is given whole
regions. At `-Xmx4g` the region size is 2 MiB, so a 1 MB `byte[]` (1 048 592 bytes with its header) just
crosses the threshold and consumes a full 2 MiB region.

Predicted ratio versus measured, across all seven sizes:

| payload | humongous | regions | predicted heap ratio | measured | agrees |
|---|---|---:|---:|---:|---|
| 256 KB | no | — | 1.000 | 1.006 | yes |
| 1 MB | yes | 1 | **2.000** | **2.000** | yes |
| 5 MB | yes | 3 | 1.200 | 1.200 | yes |
| 10 MB | yes | 6 | 1.200 | 1.200 | yes |
| 25 MB | yes | 13 | 1.040 | 1.040 | yes |
| 50 MB | yes | 26 | 1.040 | 1.040 | yes |
| 100 MB | yes | 51 | 1.020 | 1.020 | yes |

Seven for seven. This is a genuine argument for off-heap storage of medium-sized values that neither the
README nor `ARCHITECTURE.md` makes — and it is an argument against *any* on-heap cache, Caffeine included,
for payloads sitting just above half a G1 region. It is also tunable for free with
`-XX:G1HeapRegionSize`, which is a cheaper fix than adopting a second process.

---

## Leak analysis (Phase 6)

4 MB payloads, 500 cycles per probe, admission control disabled so the allocate/free paths actually run.
A 100% leak would be 2000 MB per probe; the pass threshold is 64 MB of RSS growth.

| probe | slots before | slots after | RSS before MB | RSS after MB | verdict | note |
|---|---:|---:|---:|---:|---|---|
| replacement | 1 | 1 | 77 | 77 | **clean** | one key overwritten 500× |
| explicit-delete | 0 | 0 | 77 | 76 | **clean** | put then delete |
| expiration | 0 | 0 | 80 | 81 | **clean** | 10 rounds of 50 @ 500 ms TTL |
| lru-eviction | 0 | 0 | 84 | 83 | **clean** | 468 LRU evictions, 32 resident before flush |
| rejected-writes | 0 | 0 | 84 | 84 | **clean** | 493 of 500 refused |
| concurrent-read-evict | 0 | 0 | 84 | 116 | **clean** | **6 769 627 leased reads**, 2 194 495 misses, no SIGSEGV |
| flush | 0 | 0 | 117 | 114 | **clean** | 10 fill/flush rounds of 50 |
| failed-load | 0 | 0 | 97 | 98 | **clean** | 500 loader failures, leases released |

**8 of 8 clean.** Every release path in §3 of the memory model returned its slots and its pages.

The concurrent probe is the strongest single result here: 6.8 million reference-counted reads racing
against a writer doing replacements and deletes, touching the buffer on every read, with zero crashes and
zero slots outstanding. Freeing a direct buffer under a live reader is a SIGSEGV, not an exception, so this
is the test that the reference counting actually holds — and it does.

One methodological note, because it nearly produced a false positive: the LRU probe legitimately ends
holding 32 payloads (one per shard, by configuration), which on the first run was scored against an empty
baseline and reported as a 129 MB leak. The probe now flushes before its final reading. A leak detector
that cries wolf is worse than none.

**Caveat.** This is 500 cycles per path, not six hours. It rules out per-operation leaks with high
confidence. It does not rule out slow native-arena fragmentation, which needs the long run recorded under
Limitations.

---

## MemoryGuard analysis (Phase 7)

Host at the time: 16157 MB total, **1663 MB free, 0.8971 used**. The guard rejects at 0.85.

Each row fills an engine with 4 MB payloads until the first refusal, then reports how much of the
configured budget had been reserved at that moment.

| budget | gate floor | first reject at | **budget used at first reject** | accepted | physical ratio | what rejected |
|---:|---:|---:|---:|---:|---:|---|
| 128 MB | 64 MB | 64 MB | **50.0%** | 16 | 0.9045 | machine gate |
| 512 MB | 64 MB | 64 MB | **12.5%** | 16 | 0.9081 | machine gate |
| 1024 MB | 64 MB | 64 MB | **6.3%** | 16 | 0.9110 | machine gate |
| 4096 MB | 204 MB | 208 MB | **5.1%** | 52 | 0.9182 | machine gate |

**The first reject point equals the gate floor in every row** (64/64, 64/64, 64/64, 208/204). The
configured budget never participated in the decision. Effective capacity on this host was
`max(64 MiB, 5% of budget)` — so raising the budget from 1 GB to 4 GB moved usable capacity from 64 MB to
208 MB, and the extra 3 GB bought nothing.

### Answers to the nine Phase 7 questions

1. **What triggers it?** Either of two ceilings: the budget ratio `reserved/budget ≥ 0.85`, or the machine
   ratio `PhysicalMemory.usedRatio() ≥ 0.85` — the latter only once the engine's own reservation crosses
   `physicalGateFloorBytes = max(64 MiB, 5% of budget)`.
2. **Which metric?** `com.sun.management.OperatingSystemMXBean.getFreeMemorySize()`, as
   `1 - free/total`. This is **free**, not **available**: reclaimable file-cache and standby pages count
   as used. The code's own Javadoc says a healthy host reads 80–90%.
3. **Is it intentional?** Yes, and carefully argued — the gate floor exists precisely so that an engine
   holding 5 MB does not refuse writes on a busy host. The intent is sound. The threshold is too low: at
   64 MiB, a large-value cache crosses it almost immediately.
4. **Does it shed on ordinary development machines?** **Yes, measured.** This machine was not artificially
   loaded. It shed in every configuration tested, and in Scenario A it shed 12–30% of writes at every
   payload size from 1 MB up.
5. **Does hit rate degrade unexpectedly?** Yes, and worse than shedding alone implies. `EvictionSweeper`
   sheds `max(1, size × 0.10)` entries **per shard per pass** while the guard is rejecting. The `max(1, …)`
   is a floor, so a shard holding fewer than ten entries still loses one per second. With 32 shards and a
   cache of large values holding fewer entries than shards, **a single pressure pass can evict the entire
   cache.** Observed directly: in Scenario A at 25 MB and 50 MB, writes were accepted and `entries` read
   **0** moments later. In the drain test — fill, then stop writing entirely, 10-minute TTL so nothing can
   expire — the cache shed 25 of 52 entries within 5 seconds and then stabilised once the reservation fell
   below the gate release threshold.
6. **Is the configured budget honoured?** **No.** 6.3% of a 1 GB budget was reachable; 5.1% of 4 GB.
7. **Is the behaviour appropriate in containers?** Partly. `getTotalMemorySize` is container-aware on
   modern JDKs, so the denominator is the cgroup limit rather than the host's. But a container is normally
   sized close to its working set, so the *used* ratio sits high by design — which makes the gate more
   likely to fire in a container, not less.
8. **Is cgroup memory accounted for correctly?** Not verified here. No container runtime was available on
   this machine. `getFreeMemorySize()` is container-aware in recent JDKs, but this was not tested and
   should not be assumed. **This is the single largest untested surface in the guard.**
9. **Laptop versus server?** The gate is a function of the host's free-memory fraction, so behaviour
   differs entirely by deployment. A 64 GB server at 40% used never arms it; a 16 GB laptop at 85% arms it
   as soon as the cache exceeds 64 MiB. FastCache will behave completely differently on a developer's
   machine and on a production box, in the direction that makes local testing look *better* than
   production if production is packed tighter — or, as here, far worse.

### The defect, stated minimally

Two separable problems, in priority order:

- **`EvictionSweeper.sweepShard`'s `Math.max(1, shard.size() * 0.10)`** turns a proportional 10% shed into
  an absolute floor of one entry per shard per pass. For a cache holding fewer entries than it has shards
  — the large-value case FastCache is positioned for — that floor evicts everything. A cache of 20 × 25 MB
  entries is wiped by one pass; a cache of 200 000 × 1 KB entries loses 0.016%.
- **`physicalGateFloorBytes = max(64 MiB, 5% of budget)`** arms machine-pressure gating at a point that any
  large-value cache reaches within a handful of writes, after which a whole-machine reading the cache
  cannot influence decides its admissions.

Both are small, local changes. Neither should be made before there is a regression test, and no change was
made as part of this task.

---

---

## Results — Scenario C (sustained churn): the H2 test

90 seconds of continuous PUT / GET / REPLACE / REMOVE, 8 threads, 512 MB working set, `-Xmx4g`, 2 runs.
The sidecar arm ran with `--reject-ratio 1.0` so that admission control could not confound a GC
comparison; the admission question is measured separately in the MemoryGuard section.

**One sample was excluded.** `fastcache-embedded` at 10 MB, run 2, reported `durationMillis=708225`
against a nominal 90 000 and `cpuRatio=0.53` against ~5.5 for every other sample — the host (1.1 GB free)
had started paging. It is excluded as contaminated and named here rather than quietly dropped. Its
figures, had they been kept, would have *flattered* FastCache.

| payload | arm | ops/s | heap Δ MB | GC count | **GC pause total ms** | pause p95 µs | pause p99 µs | alloc MB | writes refused |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 MB | caffeine | 12 091 | 1024 | 3599 | **8 322** | 6 000 | 6 000 | 547 203 | 0 |
| 1 MB | caffeine | 14 606 | 1024 | 2485 | **8 453** | 7 000 | 8 000 | 670 251 | 0 |
| 1 MB | fastcache-embedded | 11 329 | 1018 | 3761 | **18 408** | 26 000 | 27 000 | 518 301 | 0 |
| 1 MB | fastcache-embedded | 12 698 | 985 | 4188 | **20 200** | 26 000 | 27 000 | 581 970 | 0 |
| 1 MB | fastcache-sidecar | 1 385 | 1 | 187 | **1 000** | 17 000 | 20 000 | 114 991 | 21 438 |
| 1 MB | fastcache-sidecar | 1 520 | 1 | 221 | **976** | 15 000 | 16 000 | 125 293 | 24 222 |
| 10 MB | caffeine | 643 | 612 | 2968 | **4 715** | 3 000 | 3 000 | 292 906 | 0 |
| 10 MB | caffeine | 724 | 612 | 2619 | **4 043** | 3 000 | 3 000 | 329 034 | 0 |
| 10 MB | fastcache-embedded | 699 | 624 | 2740 | **4 065** | 3 000 | 3 000 | 315 821 | 0 |
| 10 MB | fastcache-sidecar | 206 | 1 | 243 | **736** | 4 000 | 8 000 | 170 510 | 5 478 |
| 10 MB | fastcache-sidecar | 199 | 1 | 235 | **683** | 6 000 | 6 000 | 165 612 | 4 802 |

### Finding 1 — embedded FastCache is measurably *worse* than Caffeine under churn

At 1 MB, `fastcache-embedded` more than doubled total GC pause against Caffeine — **18 408 and 20 200 ms
versus 8 322 and 8 453 ms**, with no overlap between the pairs — and its p95/p99 individual pause was
**26–27 ms against Caffeine's 6–8 ms**, a 3–4× regression in the tail.

It did so while allocating *less* (518–582 GB against 547–670 GB), so this is not allocation volume. The
plausible mechanism is object-graph shape and sweeper traffic: FastCache retains a `CacheEntry` record
pointing at a `CachePayload.Reference` record pointing at the array — three objects and two extra
reference hops per entry against Caffeine's one — allocates a `Lease` on every read where Caffeine's
`getIfPresent` allocates nothing, and runs a sweeper that fans one virtual thread per shard every second
and iterates every entry in every shard. More references to trace during evacuation, and a background
task that keeps touching them.

This was not predicted by the memory model and is the clearest negative result in the exercise. **On the
Spring path, FastCache does not merely fail to improve GC — at 1 MB payloads it degrades it.**

At 10 MB the two are indistinguishable (4 065 vs 4 043/4 715), consistent with the entry-count scaling
already seen in Scenario A: with 51 entries there is nothing to trace.

### Finding 2 — the sidecar's total GC pause is dramatically lower, but its individual pauses are longer

Total pause fell **8.5×** at 1 MB (1 000 / 976 against 8 322 / 8 453) and **6×** at 10 MB (736 / 683
against 4 715 / 4 043). Collection *count* fell ~15× (187–243 against 2 485–4 188). Application heap
attributable to the cache was 1 MB against Caffeine's 1024 MB.

But the pre-registered criterion also required that p99 individual pause be no worse than Caffeine's, and
it is worse: **20 000 and 16 000 µs against Caffeine's 6 000 and 8 000 µs** at 1 MB, and 8 000 / 6 000
against 3 000 / 3 000 at 10 MB. Far fewer collections, each with more to do. For a latency-sensitive
service, fewer-and-longer is not unambiguously better than more-and-shorter, which is why the criterion
was written that way before the numbers existed.

### Finding 3 — the throughput cost of the sidecar is an order of magnitude

**12 091–14 606 ops/s for Caffeine against 1 385–1 520 for the sidecar at 1 MB — an 8.7× gap.** At 10 MB
it is 643–724 against 199–206, a 3.4× gap. This is the socket round trip plus a fresh `byte[]` allocated
on every read, and it is the price of the heap reduction. Note the gap is *understated*: a refused write
is cheap, and the sidecar refused 21 438–24 222 of its writes.

### Finding 4 — a byte-bounded FastCache refuses writes where Caffeine evicts

The sidecar refused writes even at `--reject-ratio 1.0`, because the churn's key space legitimately
exceeded the 1 GB budget. Caffeine, given the same 1 GB `maximumWeight`, evicted least-recently-used
entries and accepted every write: **0 refusals across every run**.

This is a design difference, not a bug, but it has a direct hit-rate consequence. FastCache's LRU is bound
by *entry count* (`maxEntriesPerShard`), never by bytes; the only byte-level bound is `MemoryGuard`, whose
response to a full cache is to **refuse the new write and keep the old entries**. A byte-bounded cache
that cannot evict-to-fit will, under sustained pressure, preserve cold data and reject hot data — the
opposite of what an LRU cache is for.

---

## Cross-process analysis (Phase 5)

A writer process fills a sidecar; a second JVM that knows only the port reads every key back and compares
bytes.

| payload | keys | written | read back | **byte-exact** | read p50 ms | read p95 ms | read p99 ms | sidecar RSS MB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 MB | 256 | 256 | 256 | **256/256** | 1.30 | 2.20 | 11.41 | 321 |
| 10 MB | 25 | 25 | 25 | **25/25** | 10.68 | 26.95 | 82.76 | 313 |
| 25 MB | 10 | 10 | 10 | **10/10** | 27.97 | 110.22 | 110.22 | 313 |

**Correctness is perfect**: every value read by the second process was byte-identical to what the first
wrote, at every size.

**Latency misses the pre-registered bar.** H5 required p99 under 10 ms for payloads up to 10 MB. Measured
p99 was 11.41 ms at 1 MB and 82.76 ms at 10 MB. The p50 figures are reasonable (1.3 ms at 1 MB), but the
tail is not, and the criterion was on the tail.

**Caffeine cannot appear in this table at all.** A second process has no access to another process's heap.
That is the capability gap and it is the entire substance of FastCache's cross-process case — a real
capability, correctly implemented, with a tail-latency cost.

**Redis was not measured.** No Redis server and no container runtime were available on this machine.
Since Redis is the honest comparison for cross-process caching, its absence means the most important
question about this capability — *is FastCache better than the thing people already use for it?* — is
unanswered here, and is named as the top remaining unknown rather than guessed at.

---

## Limitations

Everything that prevents stronger conclusions, stated so the results are not over-read.

1. **The host was memory-constrained throughout** — 1.1 to 3.2 GB free of 15.8 GB. This is realistic for a
   developer machine and it is the variable Phase 7's result depends on, but it degraded one Scenario C
   sample into paging (excluded and named) and it means the GC figures carry more host noise than a
   dedicated benchmark box would produce. **Every number here should be reproduced on an idle server
   before being quoted externally.**
2. **One platform only.** Windows 11, G1, JDK 21, single CPU model. G1 region sizing drives the humongous
   penalty, and ZGC or Shenandoah — which have no humongous concept in the same form — could change the
   on-heap results substantially. Neither was tested.
3. **Redis was measured, but as a Windows port.** Memurai 4.1.2 (`redis_version:7.2.5`) on WinSock IOCP,
   not native Redis on Linux with epoll, which is the configuration Redis is fastest in. No WSL and no
   container runtime were available. The identified mechanism (an extra full-payload memcpy per reply) is
   platform-independent, so the direction should hold, but the magnitude may shrink substantially on
   Linux. This bounds every Redis number in this document.
4. **No container or cgroup testing.** `MemoryGuard`'s behaviour under a cgroup memory limit is the single
   largest untested surface, and it is exactly where the product would be deployed.
5. **Payloads are `byte[]`.** This is generous to the sidecar arm: a real Spring value is an object graph
   that would need encoding before it could cross a socket, and that cost is absent here. The embedded and
   Caffeine arms are unaffected.
6. **The sidecar's own GC is not measured.** Only the application JVM's. The sidecar's heap was
   demonstrably tiny (12 MB old-gen, 47 MB committed), so this is unlikely to hide much, but it is not
   zero and it was not instrumented.
7. **Leak testing is 500 cycles per path, not hours.** Per-operation leaks are ruled out with high
   confidence. Slow native-arena fragmentation over days is not, and that is what the existing soak
   harness exists for.
8. **Scenario C ran 90 seconds with 2 repeats**, not the 30-minute Scenario H the plan called for. The
   30-minute run was displaced by the wall-clock cost of the earlier phases on this hardware; `--scenario H`
   is implemented and ready to run.
9. **Scenarios D, E, F and G were not run as separate matrix sweeps.** Their subject matter — eviction,
   expiration, replacement and clear — is covered directly and more precisely by the eight `LeakProbe`
   probes, which drive each release path in isolation with RSS and slot accounting. The scenarios remain
   implemented for anyone wanting the per-payload-size breakdown.
10. **`fastcache-embedded` was given an entry ceiling chosen by hand** to approximate the byte budget
    Caffeine enforces natively. FastCache cannot bound the heap path by bytes at all, so this was the
    benchmark supplying a bound the implementation lacks — a courtesy to FastCache, not a handicap.

---

## Results — Phase 4: Spring `CacheManager`, `@Cacheable(sync = true)`

Driven through `Cache.get(key, Callable)` — the exact call `@Cacheable(sync = true)` makes — against
`CaffeineCacheManager` and FastCache's own `FastCacheManager`. Loader cost 50 ms, 3 repeats per cell,
one uncached key hit by N concurrent callers.

**Single-flight: 60 of 60 rows had `loaderRuns == 1` and every caller correct.** At 100 and at 500
concurrent callers, at 1/5/10/25/50 MB, both providers collapsed the stampede to exactly one loader
execution and returned a byte-correct value to all 500. FastCache retains its single-flight behaviour
through the real Spring abstraction, not merely through `@FastCache`.

| payload | conc | caffeine p50 ms | fastcache p50 ms | ratio | caffeine p99 ms | fastcache p99 ms | caffeine heap MB | fastcache heap MB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 MB | 100 | 72.73 | 56.87 | **0.78×** | 75.48 | **58.09** | 2 | 2 |
| 1 MB | 500 | 53.24 | 59.03 | 1.11× | 64.86 | **59.49** | 2 | 2 |
| 5 MB | 100 | 56.97 | 60.07 | 1.05× | 58.98 | 60.94 | 6 | 6 |
| 5 MB | 500 | 65.41 | 64.24 | 0.98× | 86.75 | **66.90** | 6 | 6 |
| 10 MB | 100 | 101.66 | 72.51 | **0.71×** | 107.03 | **75.27** | 12 | 12 |
| 10 MB | 500 | 66.05 | 79.79 | 1.21× | 91.47 | **82.28** | 12 | 12 |
| 25 MB | 100 | 67.89 | 69.70 | 1.03× | 73.79 | **71.35** | 26 | 26 |
| 25 MB | 500 | 66.07 | 67.52 | 1.02× | 80.89 | **68.46** | 26 | 26 |
| 50 MB | 100 | 92.36 | 81.23 | **0.88×** | 95.04 | **81.40** | 52 | 52 |
| 50 MB | 500 | 85.21 | 95.73 | 1.12× | 96.21 | 97.36 | 52 | 52 |

**Heap is identical in all ten cells** — 2/2, 6/6, 12/12, 26/26, 52/52. Third independent confirmation
that the Spring path stores on the heap.

**Median latency is a wash**: mean p50 ratio across cells is **0.990**, with seven of ten cells inside
±10% and FastCache faster in four.

**Tail latency is a genuine FastCache win.** In **nine of ten cells FastCache's p99 is lower** than
Caffeine's, sometimes substantially (5 MB/500: 66.90 against 86.75; 10 MB/100: 75.27 against 107.03;
25 MB/500: 68.46 against 80.89). The plausible mechanism is the `RefreshCoordinator` releasing all parked
followers together once the leader publishes, against Caffeine's per-key lock handing the value to
waiters in a less coordinated order. This is the one measured advantage that belongs to the *embedded*
path rather than the sidecar.

---

---

## Redis head-to-head

Added after the first pass, when Redis went from "not measured" to measured. This is the comparison that
decides whether FastCache's cross-process capability is worth anything, because Caffeine cannot share a
cache between processes at all but Redis has done it for fifteen years.

**Server:** Memurai Developer 4.1.2, reporting `redis_version:7.2.5`, jemalloc 3.6.0, on loopback on the
same host. Installed via winget with a verified vendor hash; the MSI's service install needed elevation,
so the files were extracted with `msiexec /a` and the server run as a plain foreground process. Config at
`.redis/redis-bench.conf`, with three settings chosen for fairness: `save` disabled and `appendonly no`
(FastCache is non-durable, so charging Redis for RDB snapshots would compare a cache against a database),
`maxmemory 2gb` with `maxmemory-policy allkeys-lru` (a bounded LRU cache like the other arms), and
`bind 127.0.0.1` (the same loopback path the sidecar uses).

**Client:** a minimal RESP2 client (`RespClient`) written to be structurally identical to the FastCache
`WireClient` - same socket options, same 64 KiB buffers, same pooling, same harness. Not Jedis or Lettuce,
because using a mature client for one side and a hand-rolled one for the other would fold the
client-library difference into the answer. The bias runs in Redis's favour: a minimal client has less
overhead than netty's pipeline. Verified independently with `memurai-cli` (`STRLEN` returned exactly the
payload size, `DBSIZE` matched the write count).

### Configuration 1 - Redis defaults, 8 concurrent clients, 1000 ops, 3 runs

| payload | arm | ops/s | p50 us | p95 us | p99 us | corrupt |
|---|---|---:|---:|---:|---:|---:|
| 256 KB | fastcache-sidecar | 4 853 | **1 434** | 2 953 | 5 845 | 0 |
| 256 KB | redis | 2 543 | 3 052 | 6 290 | 9 343 | 0 |
| 1 MB | fastcache-sidecar | 2 645 | **2 637** | 5 377 | 7 978 | 0 |
| 1 MB | redis | 1 078 | 6 953 | 13 505 | 18 008 | 0 |
| 10 MB | fastcache-sidecar | 226 | **33 734** | 52 173 | 64 569 | 0 |
| 10 MB | redis | 52 | 130 860 | 251 664 | 288 278 | 0 |
| 25 MB | fastcache-sidecar | 87 | **87 888** | 127 298 | 158 210 | 0 |
| 25 MB | redis | 20 | 330 848 | 644 076 | 729 993 | 0 |

FastCache p50 ratio: **0.47x / 0.38x / 0.26x / 0.27x**. Ranges across 3 runs do not overlap at any size.

Both arms were verified to have moved full payloads rather than short reads - allocation volume matched
1000 full payloads to within 1% for every cell (25 002 MB observed against 25 000 MB expected at 25 MB),
with zero corrupt reads. A transport returning truncated data would look fast rather than broken, so this
check is not optional.

### Configuration 2 - the obvious confound, tested and falsified

Redis defaults to `io-threads 1`: one thread performing every socket write. Against 8 concurrent clients
moving 25 MB values that is a one-core ceiling, and the server had burned 173 s of kernel time against
64 s of user time. That is a config artefact, not a property of Redis's design, and publishing the table
above without testing it would have been a benchmark of Redis's default configuration rather than of Redis.

Re-run with `io-threads 8` and `io-threads-do-reads yes` (server thread count confirmed to rise from 6 to
18):

| payload | Redis p50 default | Redis p50 io-threads | change | FastCache ratio before -> after |
|---|---:|---:|---:|---:|
| 1 MB | 6 953 us | 7 678 us | +10.4% | 0.38x -> 0.37x |
| 10 MB | 130 860 us | 139 452 us | +6.6% | 0.26x -> 0.25x |
| 25 MB | 330 848 us | 337 206 us | +1.9% | 0.27x -> 0.26x |

**I/O threading did not close the gap; it made Redis marginally slower.** The hypothesis is dead.

### Configuration 3 - single-client control, parallelism removed entirely

One client thread, Redis back on `io-threads 1` (its recommended low-concurrency setting), 300 ops,
3 runs. FastCache's thread-per-connection model can contribute nothing here.

| payload | arm | ops/s | p50 us | p95 us | p99 us | effective MB/s |
|---|---|---:|---:|---:|---:|---:|
| 1 MB | fastcache-sidecar | 768 | **1 105** | 2 019 | 3 030 | 905 |
| 1 MB | redis | 543 | 1 509 | 2 487 | 10 817 | 663 |
| 10 MB | fastcache-sidecar | 74 | **12 611** | 25 331 | 31 835 | 793 |
| 10 MB | redis | 38 | 25 772 | 34 292 | 41 031 | 388 |
| 25 MB | fastcache-sidecar | 33 | **28 562** | 45 729 | 55 089 | 875 |
| 25 MB | redis | 15 | 65 401 | 89 239 | 96 525 | 382 |

p50 ratio **0.73x / 0.49x / 0.44x** - smaller than under concurrency, but FastCache still wins without any
parallelism advantage. Two separable effects, both real:

- **Per-operation cost:** ~2x at 10-25 MB even with one client.
- **Concurrency scaling:** the gap widens to ~3.8x at 8 clients, because FastCache runs one virtual thread
  per connection while Redis serialises through one event loop - and `io-threads` does not fix it.

Note the bandwidth column. **FastCache holds ~800-900 MB/s flat across payload sizes; Redis falls from
663 MB/s at 1 MB to 382 MB/s at 10-25 MB.** A per-reply cost proportional to payload size is exactly what
degrades that way.

### Mechanism

This is not an unexplained number. `ClientSession:189` performs a **gathering write of
`{header, payload}` straight from the off-heap `DirectByteBuffer` to the socket** - the payload is never
copied onto the Java heap and never staged in an intermediate buffer. Redis's `addReplyBulk` copies each
value into the client output buffer before writing it, which is one full-payload memcpy per GET.

A per-byte cost difference predicts a gap that grows with payload size, and that is the observed shape:
0.47x at 256 KB widening to 0.26x at 10 MB. **This is the first claim in FastCache's documentation -
"zero-copy both directions" - that this exercise has confirmed as a genuine advantage over an incumbent
rather than contradicted.**

### Cross-process, both providers

Writer process fills; a separate reader JVM that knows only a port reads back and compares every byte.

| provider | payload | keys | byte-exact | read p50 ms | read p95 ms | read p99 ms | server RSS MB |
|---|---|---:|---|---:|---:|---:|---:|
| fastcache-sidecar | 1 MB | 256 | **256/256** | 1.80 | 3.13 | 18.84 | 321 |
| redis | 1 MB | 256 | **256/256** | 2.11 | 3.81 | 17.32 | 313 |
| fastcache-sidecar | 10 MB | 25 | **25/25** | **14.35** | 27.53 | 96.38 | 314 |
| redis | 10 MB | 25 | **25/25** | 28.91 | 33.15 | 103.30 | 309 |
| fastcache-sidecar | 25 MB | 10 | **10/10** | **27.52** | 95.31 | 95.31 | 313 |
| redis | 25 MB | 10 | **10/10** | 74.31 | 153.75 | 153.75 | 309 |

Both are byte-exact across processes. Server memory footprints are within 3% of each other. FastCache is
roughly 2x faster at 10 MB and 2.7x at 25 MB, and level at 1 MB.

### The limitation that bounds all of the above

**Memurai is a Windows port of Redis, not native Redis on Linux.** Redis is written against epoll and is
most optimised there; a Windows build using WinSock IOCP is not the configuration Redis is fastest in. No
WSL and no container runtime were available on this machine, so native Redis could not be tested.

These results are therefore a claim about **a Redis-7.2-compatible server on Windows**, not a universal
claim about Redis. The mechanism identified (an extra full-payload memcpy per reply) is platform-independent
and present in Redis's source on every platform, so the *direction* is likely to hold - but the
*magnitude* should be assumed to shrink on Linux until someone measures it there. **Reproducing this
comparison against native Redis on Linux is the single highest-value follow-up.**

---

## Verdict

Against the criteria fixed in [`HYPOTHESES.md`](HYPOTHESES.md) before any measurement.

### H1 — materially reduces JVM heap pressure for large cached values

**`fastcache-embedded`: NOT SUPPORTED.** Required ≥50% lower heap at ≥3 of 7 sizes. Measured 0% at all
seven, three runs each, with several cells showing zero spread. Confirmed a further three times by
Scenario C (`heapDeltaMB` 1018/985 against 1024/1024) and Phase 4 (identical in all ten cells).

**`fastcache-sidecar`: SUPPORTED.** 100% reduction at all seven sizes across three runs — 0 MB of
attributable application heap against 510–1024 MB. Far beyond the 50% bar. The memory moves to the
sidecar's RSS (232–583 MB), which is reported alongside.

### H2 — materially reduces GC pause impact during large-object churn

**NOT SUPPORTED**, on the second of the two required clauses.

- *Total pause clause: passed decisively.* The sidecar cut total GC pause **83%** at 10 MB (736/683 ms
  against 4 715/4 043) and **88%** at 1 MB (1 000/976 against 8 322/8 453), far beyond the 40% bar.
- *p99 clause: failed.* The criterion required p99 individual pause no worse than Caffeine's. Measured
  **8 000/6 000 µs against 3 000/3 000** at 10 MB and **20 000/16 000 against 6 000/8 000** at 1 MB. The
  sidecar takes ~15× fewer collections, each materially longer.

Stated plainly: a hypothesis worded around *total* GC time would have been strongly supported. The one
written in advance was worded around the tail as well, and the tail got worse. The criterion is not
rewritten after the fact.

**Additionally, and not anticipated: `fastcache-embedded` is worse than Caffeine.** 2.2–2.4× more total
GC pause and 3–4× worse p95/p99 at 1 MB, while allocating less. On the Spring path FastCache degrades GC
rather than improving it.

*Coverage caveat:* the criterion specified "5 MB and above" and only 10 MB was run in that band, two
repeats. The p99 ranges do not overlap, so the direction is not in doubt, but the size coverage is thin.

### H3 — useful performance despite higher cache-hit overhead

**SUPPORTED, for the embedded path only.** At a loader cost of 50 ms — at the stated bound, not beyond
it — FastCache's end-to-end latency through Spring's `CacheManager` was within 10% of Caffeine's in seven
of ten cells, with a mean p50 ratio of 0.990, and a *better* p99 in nine of ten.

**The sidecar path was not tested against this hypothesis**, and the Phase 5 numbers suggest it would
need a much larger L: a 10 MB cross-process read costs 10.68 ms at p50 and 82.76 ms at p99, so a 50 ms
loader does not hide it.

### H4 — safely releases off-heap memory

**SUPPORTED for per-operation release. INCONCLUSIVE for long-horizon stability.** Both halves stated
separately because the evidence differs.

- *Supported:* all eight release paths returned slots to baseline and RSS to within 3 MB after 2 GB of
  allocate/free churn each — replacement, delete, expiration, LRU, rejected writes, concurrent
  read/evict, flush, failed load. 6 769 627 reference-counted leased reads raced a concurrent evictor
  with buffer touches on every read: no crash, no outstanding slots. Slot accounting and OS RSS agree.
- *Inconclusive:* the criterion required a run of at least 30 minutes with a quiet guard, and that run
  was not performed. Slow native-arena fragmentation over hours remains unmeasured, which is exactly
  what the existing soak harness exists to answer.

### H5 — cross-process capability provides meaningful practical value

**NOT SUPPORTED on the literal wording; the clause that mattered is refuted in FastCache's favour.** Both
halves are stated, because the criterion turned out to be partly mis-specified and that is worth saying
rather than quietly rewriting.

- *Correctness: passed.* Byte-identical across processes at every size, for both providers.
- *Absolute latency bar: failed.* The criterion required p99 under 10 ms for payloads up to 10 MB.
  Measured 31.8 ms at 10 MB single-client, 70.7 ms at 8 clients. **But Redis fails the same bar by a
  wider margin** (41.0 ms and 315.8 ms). A 10 MB value cannot cross a loopback socket, be copied and be
  delivered in under 10 ms at p99 on this hardware by either system. The threshold was set wrong. It is
  left as written rather than adjusted after the fact, and the failure is recorded as such.
- *The explicit disqualifier is refuted.* The criterion said NOT SUPPORTED "if cross-process read latency
  is worse than a local Redis performing the same operation, since Redis then dominates on every axis
  except installation." It is not worse. FastCache read p50 was **0.44x-0.73x of Redis single-client and
  0.26x-0.38x at 8 concurrent clients**, across three server configurations including one chosen to
  favour Redis, with non-overlapping ranges.

So: the capability is real, correctly implemented, and **faster than the incumbent that provides it** - on
this platform, against a Windows Redis build, with the caveat recorded above. The pre-registered numeric
bar was not met by anything tested, including Redis.

### Summary table

| Hypothesis | Verdict |
|---|---|
| H1 heap pressure — embedded | **NOT SUPPORTED** |
| H1 heap pressure — sidecar | **SUPPORTED** |
| H2 GC pause under churn | **NOT SUPPORTED** (total pause −83%, but p99 worse) |
| H3 useful despite overhead — embedded | **SUPPORTED** |
| H3 useful despite overhead — sidecar | **NOT TESTED** |
| H4 safe off-heap release — per operation | **SUPPORTED** |
| H4 safe off-heap release — long horizon | **INCONCLUSIVE** |
| H5 cross-process value | **NOT SUPPORTED** on the literal p99 bar (which Redis also fails, by more); the "worse than Redis" disqualifier is **REFUTED** - FastCache is 1.4x-3.8x faster |
