# End-to-end JVM reality test — experimental plan

**Written before any measurement.** Hypotheses and pass/fail criteria below are fixed in advance and will
not be edited after results exist. If a criterion turns out to have been badly chosen, it will be reported
as badly chosen rather than adjusted.

---

## 1. The question

Every advantage measured so far is a *cache-level* advantage. The product claim is about applications.
Between the two sits a fact that could erase the whole thesis:

> `WireClient.get()` returns a `byte[]`. The sidecar keeps the payload off the application heap **at rest**
> and then hands the application a full-sized heap array **on every hit**.

If a service reading 10 MB values at 100 req/s allocates 1 GB/s of humongous arrays, the off-heap design
may simply move heap pressure from *retention* to *allocation* — and G1 treats a 10 MB array as a
humongous object, which is not a cheap thing to allocate repeatedly.

So the question is not "is the cache faster". It is:

> **Does moving the resident cache off-heap still help a JVM service, once the service has to materialise
> every value it reads?**

An honest answer may be that the correct product claim shrinks from *"eliminates JVM heap pressure"* to
*"keeps the cache resident off-heap"*. Those are different claims and this experiment must not conflate
them.

---

## 2. Arms

Identical application behaviour across all four. Only the cache implementation differs.

| Arm | Path | Value handed to the service |
|---|---|---|
| **A — Caffeine** | in-process | live object reference, no copy |
| **B — FastCache embedded** | in-process, `putReference` | live object reference, no copy |
| **C — FastCache sidecar** | socket → off-heap engine → socket | `byte[]`, freshly allocated per hit |
| **D — Redis** | socket → Redis → socket | `byte[]`, freshly allocated per hit |

A and B store live references and never serialize. C and D must serialize on write and deserialize on
read. **That asymmetry is inherent to the architectures, not a benchmark artefact**, so it is measured
rather than removed — §4 isolates the serialization cost so a reader can see how much of any gap it
accounts for.

---

## 3. Application architecture

A minimal Spring Boot service, identical for every arm:

```
HTTP GET /documents/{id}
    → DocumentController
    → DocumentService          cache lookup
        → on miss: simulated expensive processing (fixed cost, see §5)
    → serialize response body
    → HTTP response
```

The controller writes the payload to the response so the value is genuinely consumed — a benchmark that
fetches a 25 MB array and discards it without touching it invites the JIT to elide work that a real
service cannot.

Built as a Maven module `fastcache-e2e`, selected by `--arm` at startup so one binary serves all four.

---

## 4. Value representations

### Representation A — `byte[]`

The simplest baseline. Establishes raw transport and allocation cost with no codec in the path.

### Representation B — realistic object graph

```java
record LargeResponse(
    String documentId, String title, Instant generatedAt, Map<String,String> metadata,
    List<Section> sections,        // nested objects, strings, numeric fields
    byte[] payload)                // the bulk
```

- ~40 nested `Section` objects with strings and numerics, plus the bulk payload.
- **One codec, used identically by arms C and D**: length-prefixed binary, metadata fields first, bulk
  payload appended raw. Deliberately *not* JSON — base64-encoding a 25 MB payload would inflate it 33%
  and measure Jackson rather than the cache.
- Arms A and B store the live object and pay no codec cost, because that is what their APIs offer.
- **Codec cost is measured standalone** (serialize + deserialize, no cache) and reported separately, so
  the C/D numbers can be read with and without it.

A production service would more likely use Jackson, Kryo or protobuf. The hand-rolled codec is faster than
all three, which is generous to C and D — the arms that need it.

---

## 5. Workload parameters

| Parameter | Value | Why |
|---|---|---|
| Payload sizes | 1 MB, 10 MB, 25 MB (50 MB if the runner sustains it) | spans the crossover found in the previous round |
| Cached entries | working set sized to 512 MB per size (512 / 51 / 20 entries) | large enough to make retention matter, small enough to fit a 1 GB heap at 25 MB for Phase 14 |
| Hit ratio | 100% (Phase 6), then 80/20 and 90/10 GET/SET (Phase 7) | steady state first, then churn |
| Miss cost | fixed 50 ms simulated processing | representative of the work a cache exists to avoid; also the §13 stampede loader |
| Concurrency | 1, 8, 32, 64 | 64 only if both systems stay stable |
| Duration | 60 s measured per cell, 3 runs | matches the harness convention already in use |
| Warmup | 20 s discarded, reported not skipped | previous round proved short warmup leaks into results |
| Sampling | memory/GC every 1 s; RSS every 5 s (out-of-process probe) | RSS probing is ~100 ms, never inside a timed loop |

## 6. JVM and process configuration

| | |
|---|---|
| JDK | Temurin 21 (21.0.12 on Linux CI) |
| GC | **G1, the default** — chosen because it is what a team gets without thinking, and because its humongous-object handling is central to the question |
| Heap | `-Xmx4g` primary; `-Xmx1g` and `-Xmx512m` for Phase 14 |
| Direct memory | `-XX:MaxDirectMemorySize=4g` |
| GC log | `-Xlog:gc*,gc+heap=debug:file=...` — independent of the MXBean record |
| Allocation profiling | `-XX:StartFlightRecording=settings=profile` with `jdk.ObjectAllocationSample` and `jdk.ObjectAllocationInNewTLAB` |
| Sidecar | separate JVM, `-Xmx512m` (payloads are off-heap; a large heap here would hide the property under test) |
| CPU | whatever the runner gives, recorded; identical for all arms |
| Isolation | one arm at a time, fresh JVM per cell, servers restarted between arms |

**FastCache is configured with its shipped defaults except `--reject-ratio`.** Previous work showed the
default admission control delivers 5–6% of configured capacity on a loaded host, which would prevent the
cache from holding the working set at all. Runs are therefore done **both ways** — default and disabled —
and the difference is itself a result (§15 of the results doc), not a footnote.

Redis: `save ""`, `appendonly no`, `maxmemory` matched to the FastCache budget, `allkeys-lru`, loopback,
`io-threads 1` (its default; previous rounds established that varying it does not change the outcome).

---

## 7. Hypotheses and acceptance criteria

Fixed now. Each states what would *falsify* it.

### E1 — The sidecar keeps the resident cache off the application heap

**SUPPORTED if** settled application heap attributable to the cache is **< 10%** of the resident payload
volume for arm C, while arms A and B are **> 80%** of it.
**NOT SUPPORTED if** arm C's attributable heap exceeds 30% of resident volume.

*Expected to pass — already shown at cache level. Included because it must be re-confirmed end-to-end, and
because E2 is meaningless without it.*

### E2 — The sidecar converts retained heap into allocation rate

**SUPPORTED if** arm C's measured allocation rate is **≥ 80% of (throughput × payload size)** — i.e. the
per-hit array dominates allocation — while arms A and B allocate **< 20%** of that.

*This is expected to pass too. It is stated separately from E3 because "allocates a lot" and "costs more
in GC" are different claims, and the product argument turns on the second, not the first.*

### E3 — Despite E2, the sidecar reduces total GC pause in a running service

**This is the crux hypothesis.**

**SUPPORTED if**, at equal throughput and payload size, arm C's total GC pause over the measured window is
**≤ 70%** of arm A's, at 10 MB and 25 MB, reproduced across 3 runs.
**NOT SUPPORTED if** arm C's total GC pause is **within ±20%** of arm A's, or worse.
**INCONCLUSIVE if** throughput differs by more than 2× between arms, since GC pause is not comparable at
different work rates — in which case pause *per request* is reported instead and labelled as such.

*Genuinely uncertain. Caffeine retains N × size in old gen, which drives mixed collections. The sidecar
allocates N transient humongous arrays, which drive concurrent cycles. Either could dominate. A previous
round found embedded FastCache 2.2–2.4× **worse** than Caffeine on GC, so a negative result here is
entirely plausible.*

### E4 — The sidecar lets a service run with a smaller heap

**SUPPORTED if** at `-Xmx1g` with a 512 MB working set of 25 MB values, arm C sustains **≥ 80%** of its
`-Xmx4g` throughput while arm A either fails with `OutOfMemoryError` or drops **below 50%**.
**NOT SUPPORTED if** arm C also degrades below 50%, or if arm A is unaffected.

*Commercially the most interesting hypothesis: "run the same service in a 1 GB container" is a stronger
claim than any latency ratio.*

### E5 — The cache-level advantage over Redis survives end-to-end

**SUPPORTED if** application request p50 ratio (C ÷ D) is **≤ 0.6** at 10 MB and 25 MB, and p99 ratio
**≤ 1.0**.
**NOT SUPPORTED if** p50 ratio exceeds 0.85 — meaning deserialization and application work have absorbed
the transport advantage.

*Cache-level ratios were 0.24 and 0.22. If most of that survives, the thesis holds; if it collapses toward
1.0, the advantage was real but irrelevant.*

### E6 — There is a payload size above which leaving the heap beats staying in it

**SUPPORTED if** a crossover exists in application p99 between arms A and C within the tested range, with
arm C better above it in at least 2 of 3 runs.
**NOT SUPPORTED if** arm A is better at every size tested — in which case the sidecar's case rests
entirely on heap relief and cross-process sharing, not on latency, and must be argued that way.

*Prior evidence says Caffeine is 360×–65 000× faster on cache hits alone. For a crossover to exist at all,
GC pause or heap exhaustion must dominate the request path. E6 failing while E4 passes would be a coherent
and useful result.*

---

## 8. What gets measured

Per request: cache lookup, deserialization, business logic and total response latency, separately — p50,
p95, p99, max, plus throughput in req/s and MB/s.

Per arm: heap used/committed, old gen, allocation rate, GC count, total pause, p95 and p99 pause, process
RSS, CPU, and for the sidecar/Redis the server process RSS. Cache entries, bytes, hit/miss, evictions and
rejected writes.

Phase 8 additionally uses JFR `jdk.ObjectAllocationSample` to attribute allocation **by call site**, so
the claim "the per-hit array dominates" is evidenced rather than inferred from a total.

---

## 9. Known limitations, stated in advance

1. **4 vCPU on the Linux runner.** Constrains exactly the axis where thread-per-connection differs from a
   single event loop. Concurrency-64 results in particular will be runner-bound.
2. **One codec, hand-rolled.** Faster than Jackson/Kryo; generous to the arms that need it.
3. **Simulated miss cost**, not real I/O or model inference.
4. **G1 only.** ZGC and Shenandoah handle large objects very differently and are not tested.
5. **The sidecar is a second JVM on the same host** — its RSS and CPU are counted, but its own GC is not
   instrumented.
6. **50 MB is best-effort**: at 64 concurrent clients it implies multiple GB in flight and will be skipped
   rather than run into an allocation cliff, identically for all arms.

---

## 10. What this experiment cannot settle

Whether FastCache is *production-ready*. That is gated on the capacity model (`MemoryGuard`), analysed
separately in `memoryguard-production-analysis.md`. Every latency number produced here with
`--reject-ratio 1.0` describes a configuration the product does not ship, and will be labelled as such
wherever it appears.
