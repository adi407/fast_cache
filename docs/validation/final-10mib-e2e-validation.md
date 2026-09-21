# Final 10 MiB end-to-end validation

Run 2026-09-21 on native Linux with native Redis 7.0.15. Raw data, logs, the Redis config and the
environment record are in [`data/final-10mib/`](data/final-10mib/). Workflow run
[35570002758](https://github.com/adi407/fast_cache/actions/runs/35570002758).

**All sizes in this document are binary.** 10 MiB means exactly 10 485 760 bytes, and MiB/s means
2²⁰ bytes per second. Where earlier documents wrote "MB" they used `1 << 20` as well, so their figures
are directly comparable; they are restated here as MiB for consistency.

---

## 1. Objective

One question, and no other:

> **Does FastCache's measured large-value transport/bandwidth advantage over native Redis survive an
> end-to-end JVM application workload at 10 MiB values?**

Every favourable FastCache number on file came from a microbenchmark of `get`/`put`. The product claim
is about what happens to a real service. The two previous attempts to close that gap were voided — one
by two matrix processes interleaving on one host, one by a server serving stale 1 MiB entries under
10 MiB labels — leaving **no valid end-to-end measurement of the sidecar or Redis above 1 MiB**, which is
the payload range where the entire narrow product thesis lives.

---

## 2. Hypothesis

From the microbenchmark, at 10 MiB and 8 concurrent clients: FastCache's lookup p50 was **0.24×** of
Redis's, with effective bandwidth **741 MiB/s against 174 MiB/s**.

The hypothesis under test is that this survives the application. The specific way it could fail was
named in advance: the sidecar allocates a payload-sized `byte[]` on every read and then decodes it, so
a high-hit-rate service might pay back, as GC cost inside the application, the heap pressure the
off-heap design was adopted to remove — making the argument for the sidecar circular.

---

## 3. Pre-registered threshold

Fixed before the run and not changed after seeing results:

> If FastCache sidecar's 10 MiB end-to-end throughput is less than **1.5× Redis**, the narrow
> large-value product thesis is considered insufficiently differentiated and the project moves to
> Outcome C: interesting technology, weak product.

Inconclusive if any of: corruption, process contamination, host exhaustion, inconsistent payload, hit
ratio failure, errors, or unexplained run-to-run variance above 20%.

---

## 4. Environment

| | |
|---|---|
| Host | GitHub Actions `ubuntu-latest`, Ubuntu 24.04.5 LTS |
| Kernel | Linux 6.17.0-1022-azure x86_64 |
| CPU | AMD EPYC 7763 64-Core, **4 vCPU allocated** |
| RAM | 15.6 GB total, 14.6 GB available at start |
| Redis | **redis-server 7.0.15**, jemalloc 5.3.0, native Linux build (epoll) |
| Redis config | `io-threads 4`, `io-threads-do-reads yes`, `maxmemory 2gb`, `allkeys-lru`, `save ""`, `appendonly no` — **asserted at runtime via `CONFIG GET`**, not assumed |
| JDK | Temurin OpenJDK 21.0.12.1 LTS |
| Application JVM | `-Xmx4g -XX:MaxDirectMemorySize=2g`, G1 (default) |
| Sidecar JVM | separate process, `-Xmx512m`, 2 GiB off-heap budget, `--reject-ratio 1.0` |
| Load generator | separate JVM, `-Xmx1g` |
| FastCache commit | `05a48e3` |

**Same host class, same Redis build and same JDK as the microbenchmark round**, which is what makes the
two directly comparable in §11.

Run on a clean CI runner rather than a developer machine deliberately. The previous attempt was voided
by host memory exhaustion, and the only Redis worth comparing against is a native Linux build.

---

## 5. Exact methodology

| Parameter | Value |
|---|---|
| Payload | **10 MiB** (10 485 760 bytes), incompressible, deterministic in seed |
| Working set | **51 entries = 510 MiB**, identical definition for all four arms |
| Concurrency | **8** concurrent clients |
| Workload | **100% GET**, `write-ratio 0.0`, no writes in the measured window |
| Warmup | 20 s per cell, discarded; GC, allocation and hit counters reset after it |
| Measurement | 45 s per cell |
| Repetitions | **3 per arm**, each a fresh application JVM, warmed up independently |
| Arm order | rotated per repetition, so no arm sits systematically in a favoured position |
| Miss cost | 50 ms simulated load (does not fire in the measured window — see §6) |

Four arms behind one interface, with the application path byte-identical above the cache:

```
HTTP -> DocumentController -> DocumentService -> Backend -> LargeResponse -> response
```

| Arm | Cached representation | What a hit returns |
|---|---|---|
| `caffeine` | live object, application heap | same instance, no copy |
| `fastcache-embedded` | live object, application heap (`putReference`) | same instance, no copy |
| `fastcache-sidecar` | off-heap, second JVM | fresh `byte[]`, then decoded |
| `redis` | server memory, separate process | fresh `byte[]`, then decoded |

**Fairness.** Both cross-process arms encode and decode through the *same* `Codec` class, so neither is
given serialization work the other avoids and neither gets a shortcut. Both use a hand-rolled client
over a `Socket` with `TCP_NODELAY` and 64 KiB buffers — byte-for-byte the same pooling and stream setup.
The in-process arms pay no codec cost, which is architectural rather than a harness artefact and is the
reason §12 is reported separately.

**One asymmetry, stated rather than hidden:** the endpoint returns a JSON digest of the value, not the
10 MiB body. Writing 10 MiB back over HTTP would make this a benchmark of Tomcat's response buffering,
charged identically to every arm while dwarfing the differences between them. The value is fully consumed
inside the service (`LargeResponse.consume()` walks the payload and the object graph), so nothing is
elided. "Application MiB/s" below therefore means *cache bytes consumed by the application per second*,
not HTTP egress.

---

## 6. Integrity checks

All six pre-flight requirements were enforced programmatically, and every one passed in every cell.

| Check | Requirement | Result |
|---|---|---|
| **Process isolation** | exactly one application JVM, one generator, one cache server | Verified by `pgrep` before the run and after every cell. **Passed 12/12.** |
| **Port isolation** | port free, then started process owns it, then process alive | Kernel **bind test** rather than an HTTP probe, plus `ss` pid-ownership walked through descendants. **Passed 12/12.** |
| **Cache isolation** | START → CLEAR → POPULATE → VERIFY COUNT → VERIFY LENGTH → WARMUP → MEASURE | `/admin/verify?phase=empty` asserted 0 entries before every fill. **Passed 12/12.** |
| **Payload verification** | id, length, seed stamp **and content digest** on every GET; abort the cell on mismatch | Verified per request against a **precomputed** per-seed digest. A mismatch returns HTTP 500 and the generator halts the cell immediately. **0 corrupt reads in 12/12 cells.** |
| **Hit ratio** | ≥ 99.9% | **1.0 in 12/12 cells.** |
| **Error rate** | 0 | **0 in 12/12 cells.** |

Two checks stricter than the brief required, because the ratio alone would not have caught what voided
the previous attempts:

- **Content digest, not just length.** A payload of the right size carrying the wrong bytes passes a
  length check. Expected digests are precomputed once per seed at startup — deriving one per request
  would allocate a payload-sized array on the exact path under measurement.
- **Zero loader runs in the measured window.** Counters are reset at the GC mark, so `misses` and
  `loaderRuns` describe the measurement. In a fully populated 100% GET window both must be exactly zero;
  a single loader run means the cache evicted mid-measurement. **0 in 12/12 cells.**

The hit counters had to be windowed for the 99.9% gate to mean anything: left cumulative, the 51
unavoidable cold-fill misses put the slowest arm at 0.9989 and the gate would have failed an arm for
being slow rather than for missing.

Redis's configuration was **asserted at runtime**, not assumed: `io_threads_active` reads 0 at idle even
when configured, so `CONFIG GET io-threads` was checked to confirm the config file loaded and Redis was
not silently running on defaults. Confirmed: `io-threads=4 maxmemory=2147483648 policy=allkeys-lru`.

**No cell was discarded. No cell failed. 12 of 12 valid.**

---

## 7. Raw results

Every valid cell, all three repetitions:

| Cell | req/s | HTTP p50 µs | HTTP p99 µs | lookup p50 µs | errors | corrupt | hitRatio |
|---|---:|---:|---:|---:|---:|---:|---:|
| `caffeine` r1 | 10 818.1 | 680 | 1 628 | 0.2 | 0 | 0 | 1.0 |
| `caffeine` r2 | 10 596.8 | 691 | 1 725 | 0.2 | 0 | 0 | 1.0 |
| `caffeine` r3 | 10 646.1 | 689 | 1 692 | 0.2 | 0 | 0 | 1.0 |
| `fastcache-embedded` r1 | 10 832.2 | 675 | 1 678 | 1.0 | 0 | 0 | 1.0 |
| `fastcache-embedded` r2 | 10 801.9 | 680 | 1 629 | 1.0 | 0 | 0 | 1.0 |
| `fastcache-embedded` r3 | 10 841.4 | 676 | 1 643 | 1.0 | 0 | 0 | 1.0 |
| `fastcache-sidecar` r1 | 355.1 | 21 777 | 41 354 | 13 091 | 0 | 0 | 1.0 |
| `fastcache-sidecar` r2 | 354.9 | 21 665 | 42 024 | 13 187 | 0 | 0 | 1.0 |
| `fastcache-sidecar` r3 | 349.0 | 22 128 | 42 390 | 13 360 | 0 | 0 | 1.0 |
| `redis` r1 | 118.8 | 60 796 | 126 695 | 58 242 | 0 | 0 | 1.0 |
| `redis` r2 | 118.7 | 61 420 | 124 544 | 58 877 | 0 | 0 | 1.0 |
| `redis` r3 | 117.7 | 62 028 | 132 865 | 59 380 | 0 | 0 | 1.0 |

**The FastCache and Redis throughput ranges do not overlap, and are not close to overlapping:**
[349.0 – 355.1] against [117.7 – 118.8].

---

## 8. Cache-level results (Level 1 — transport)

Means of three runs.

| Metric | caffeine | fastcache-embedded | fastcache-sidecar | redis |
|---|---:|---:|---:|---:|
| lookup p50 µs | **0.2** | 1.0 | **13 213** | 58 833 |
| lookup p95 µs | 0.4 | 1.4 | **22 842** | 95 332 |
| lookup p99 µs | 0.5 | 1.8 | **28 612** | 125 675 |
| decode p50 µs | 0.0 | 0.0 | 3 747 | **1 227** |
| transport MiB/s at p50 | n/a | n/a | **757** | 170 |

`transport MiB/s` is payload ÷ lookup p50 — a per-stream rate, the same definition the microbenchmark
used. It is reported as `n/a` for the in-process arms: dividing a payload size by a pointer dereference
is not a transfer rate.

**The cache engine retains the bandwidth advantage in full: 757 MiB/s against 170 MiB/s, a ratio of
0.225× on lookup p50.** §11 shows how closely that reproduces the microbenchmark.

**One result that goes the other way and is not explained by this experiment:** the sidecar's decode p50
is 3 747 µs against Redis's 1 227 µs, for identical bytes through the identical `Codec`. The plausible
mechanism is that the sidecar arm sustains 3× the request rate on a 4-vCPU host, so decoding competes
with roughly 3× the concurrent allocation and collection activity. **That is a hypothesis, not a
measurement** — nothing here attributes the difference. It matters because it is charged to FastCache
and it narrows the end-to-end advantage relative to the transport advantage.

---

## 9. End-to-end results (Level 2 — application)

Client-observed wall-clock latency around the HTTP call, measured in the load generator. **This is the
number a developer's application would see, and it is the more important of the two.**

| Metric | caffeine | fastcache-embedded | fastcache-sidecar | redis |
|---|---:|---:|---:|---:|
| HTTP p50 µs | 686.9 | **677.1** | **21 857** | 61 415 |
| HTTP p95 µs | 1 193.4 | 1 182.5 | **34 350** | 97 915 |
| HTTP p99 µs | 1 681.7 | 1 649.9 | **41 923** | 128 035 |
| req/s | 10 687.0 | **10 825.2** | **353.0** | 118.4 |
| application MiB/s | 106 870 | 108 252 | **3 530** | 1 184 |

Combined table in the requested form:

| Metric | Caffeine | Embedded | FastCache Sidecar | Redis |
|---|---:|---:|---:|---:|
| p50 | 686.9 µs | 677.1 µs | 21 857 µs | 61 415 µs |
| p95 | 1 193.4 µs | 1 182.5 µs | 34 350 µs | 97 915 µs |
| p99 | 1 681.7 µs | 1 649.9 µs | 41 923 µs | 128 035 µs |
| req/s | 10 687.0 | 10 825.2 | 353.0 | 118.4 |
| MiB/s | 106 870 | 108 252 | 3 530 | 1 184 |
| Heap | 636 MiB | 637 MiB | 25 MiB | 25 MiB |
| GC pause | 105 ms | 106 ms | 747 ms | 207 ms |

**Important caveat on the in-process arms.** Caffeine and embedded FastCache have a lookup p50 of 0.2 µs
and 1.0 µs against an HTTP p50 of ~680 µs. Those two arms are **HTTP-bound, not cache-bound** — the
measurement is of Tomcat, not of the cache. Their throughput figures are a floor on what the cache could
do, not a measurement of it. The cross-process arms are genuinely cache-bound: lookup is 60% and 96% of
their total request time respectively.

---

## 10. Memory and GC results

### Application JVM

| Metric | caffeine | fastcache-embedded | fastcache-sidecar | redis |
|---|---:|---:|---:|---:|
| heap used MiB | 636 | 637 | **25** | **25** |
| heap committed MiB | 1 070 | 1 070 | 96 | 94 |
| allocated GiB (45 s) | 7.1 | 7.1 | 311.0 | 104.4 |
| **allocated MiB per request** | **0.02** | **0.01** | **20.04** | **20.04** |
| GC collections | 40 | 40 | 311 | 75 |
| GC total pause ms | 105 | 106 | 747 | 207 |
| **GC pause µs per request** | **0.22** | **0.22** | **47.03** | **38.80** |
| GC p99 pause µs | 20 333 | 21 667 | **7 333** | 22 000 |

### Cache process

| Arm | CPU seconds per cell | CPU ms per request (approx.) | RSS MiB |
|---|---:|---:|---:|
| `caffeine` / `fastcache-embedded` | 0 (no second process) | — | — |
| `fastcache-sidecar` | 81.1 / 83.7 / 83.9 | **~3.6** | 594.5 / 615.7 / 834.1 |
| `redis` | 65.6 / 65.6 / 65.8 | **~8.5** | 528.7 / 528.7 / 528.7 |

CPU per request is approximate: the CPU counter spans the whole cell (fill, warmup and measurement)
while the request count covers the measured window only. The correction is the same multiplier for both
arms, so the **~2.4× CPU-efficiency advantage to FastCache** holds under it.

Four things in that data matter, and only the first is favourable:

1. **FastCache does ~3× the work for ~0.8× the cache-process CPU.** Redis spent 12.3 ms of CPU per
   request unadjusted; FastCache spent 5.2 ms. This is a supporting result for the transport hypothesis,
   not an independent one.
2. **Per request, the sidecar and Redis allocate identically — 20.04 MiB each**, for a 10 MiB payload.
   That is the encoded array plus the decoded object graph, twice the payload, in both arms. The
   sidecar's larger *total* allocation and GC pause follow from it serving 3× the requests, not from
   being less efficient.
3. **Per request, the sidecar costs 21% more GC pause than Redis** (47.03 vs 38.80 µs) and **210× more
   than Caffeine** (0.22 µs). The pre-registered failure mode — per-read allocation reintroducing GC
   cost — is **real and measurable**. It did not, however, consume the throughput advantage.
4. **The sidecar's RSS grew across the three cells: 594.5 → 615.7 → 834.1 MiB, against Redis flat at
   528.7 MiB in all three.** For a 510 MiB working set that is 16%–63% overhead against Redis's 3.7%.
   Three points is not a trend, and this experiment cannot distinguish JVM heap growth, off-heap slot
   reuse and native arena fragmentation. **It is an observation, not a finding**, and it is exactly what
   the unrun long-horizon test exists to settle.

---

## 11. FastCache vs Redis

The headline, with the threshold applied:

```
fastcache-sidecar   353.0 req/s   [349.0 - 355.1]
redis               118.4 req/s   [117.7 - 118.8]
ratio               2.981x        (threshold 1.5x)
```

| Axis | ratio (sidecar ÷ redis) | Who wins |
|---|---:|---|
| Throughput | **2.981×** | FastCache |
| HTTP p50 | **0.356×** | FastCache |
| HTTP p95 | **0.351×** | FastCache |
| HTTP p99 | **0.327×** | FastCache |
| lookup p50 | **0.225×** | FastCache |
| lookup p99 | **0.228×** | FastCache |
| Cache-process CPU per request | ~0.42× | FastCache |
| Application heap | 1.004× | tie — both 25 MiB |
| GC pause per request | 1.21× | Redis |
| Cache-process RSS | 1.12×–1.58× | Redis |
| decode p50 | 3.05× | Redis |

### How closely the microbenchmark reproduced

This is the most important validation in the document, because it is what licenses trusting the earlier
round at all:

| Measurement | Microbenchmark (10 MiB, 8 clients) | End-to-end (this run) | Difference |
|---|---:|---:|---:|
| FastCache lookup p50 | 13 501 µs | 13 213 µs | **−2.1%** |
| Redis lookup p50 | 57 405 µs | 58 833 µs | **+2.5%** |
| Ratio | 0.235× | 0.225× | — |
| FastCache transport MiB/s | 741 | 757 | +2.2% |
| Redis transport MiB/s | 174 | 170 | −2.3% |

**The transport measurement reproduced inside 2.5% on a separate harness, a separate application and a
separate run.** The cache-level claim was correct.

### Where the advantage shrank, and why that is the honest headline

The throughput ratio fell from **4.52×** in the microbenchmark to **2.98×** end-to-end. Nothing regressed
— the cause is arithmetic. A real request contains work that has nothing to do with the cache (decoding,
consuming the object graph, HTTP framing, Tomcat queueing) and that work is charged to both arms, which
compresses any ratio built on top of it.

The decomposition, from the measured phase timings:

| Phase | fastcache-sidecar | redis |
|---|---:|---:|
| lookup | 13 213 µs | 58 833 µs |
| decode | 3 747 µs | 1 227 µs |
| everything else (consume, HTTP, queueing) | ~4 896 µs | ~1 355 µs |
| **total HTTP p50** | **21 857 µs** | **61 415 µs** |

FastCache wins the transport phase by 45 620 µs and gives back 6 061 µs of it in the other phases.
**The end-to-end advantage is real, it is large, and it is smaller than the cache-level advantage. Any
claim must use the 2.98× / 0.356× figures, not the 4.5× / 0.225× ones.**

---

## 12. FastCache vs Caffeine

| | caffeine | fastcache-sidecar | factor |
|---|---:|---:|---:|
| lookup p50 | 0.2 µs | 13 213 µs | **~66 000×** |
| HTTP p50 | 686.9 µs | 21 857 µs | 31.8× |
| req/s | 10 687.0 | 353.0 | **30.3×** |
| application heap | 636 MiB | 25 MiB | 0.04× |
| GC pause per request | 0.22 µs | 47.03 µs | 214× |

Caffeine wins every latency and throughput axis by a wide margin and loses on exactly two: it cannot
share a cache between processes, and it holds 636 MiB of application heap where the sidecar holds 25 MiB.

Caffeine is included as the in-process baseline and answers a different question — *is an external
process justified at all?* The answer this run gives is: **only where the data genuinely cannot live in
one process.** A service that can hold its working set in-process should, and the measured cost of
deciding otherwise is roughly 30× throughput.

Note again that Caffeine's figure is **HTTP-bound** (§9), so 30× is a floor on the gap.

### Embedded FastCache

Reported for completeness, as instructed, without effort spent explaining it:

| | caffeine | fastcache-embedded |
|---|---:|---:|
| req/s | 10 687.0 | 10 825.2 |
| HTTP p50 | 686.9 µs | 677.1 µs |
| lookup p50 | 0.2 µs | 1.0 µs |
| heap used | 636 MiB | 637 MiB |
| GC total pause | 105 ms | 106 ms |

Heap parity to within 1 MiB, GC parity to within 1 ms, throughput within 1.3%, and a lookup 5× slower in
absolute terms that is invisible behind HTTP. **The previous rounds' finding repeats: the embedded path
has Caffeine-like heap behaviour with no corresponding performance advantage.** Documented, not
investigated.

---

## 13. Tail-latency analysis

Reported in full, because a throughput win with a tail regression is a real and common outcome:

| Percentile | caffeine | embedded | **fastcache-sidecar** | **redis** | sidecar ÷ redis |
|---|---:|---:|---:|---:|---:|
| p50 | 686.9 µs | 677.1 µs | **21 857 µs** | 61 415 µs | **0.356×** |
| p95 | 1 193.4 µs | 1 182.5 µs | **34 350 µs** | 97 915 µs | **0.351×** |
| p99 | 1 681.7 µs | 1 649.9 µs | **41 923 µs** | 128 035 µs | **0.327×** |

**At 10 MiB there is no tail regression. FastCache is better at every percentile measured, and its
advantage widens slightly as the percentile rises** (0.356 → 0.351 → 0.327). This is consistent with the
microbenchmark, which found p99 ratios of 0.20 at 10 MiB while finding FastCache *worse* on p99 at 256 KiB
(2.10×) and at 1 MiB (1.09×).

**That contrast is the boundary of the claim, and it must travel with it.** The tail advantage measured
here belongs to 10 MiB. The known tail *regression* below ~10 MiB is not contradicted by this run,
because this run did not test those sizes and was not permitted to.

GC pause tail runs the other way from the request tail, and favours the sidecar: its p99 GC pause is
**7 333 µs**, the lowest of all four arms (Caffeine 20 333, embedded 21 667, Redis 22 000). It collects
4× as often as Redis with less to do each time.

---

## 14. Run-to-run variance

Max−min as a percentage of the mean. The pre-registered inconclusive bar is 20%.

| Metric | caffeine | fastcache-embedded | fastcache-sidecar | redis |
|---|---:|---:|---:|---:|
| req/s | 2.1% | 0.4% | **1.7%** | **0.9%** |
| HTTP p50 | 1.6% | 0.7% | **2.1%** | **2.0%** |
| HTTP p99 | 5.7% | 3.0% | **2.5%** | **6.5%** |
| lookup p50 | 5.1% | 1.0% | **2.0%** | **1.9%** |

**Maximum observed spread on any metric in any arm: 6.5%, against a 20% bar.** Throughput — the metric
the decision rests on — varied by 1.7% and 0.9% in the two decisive arms, and the ranges are separated by
a factor of three. Arm order was rotated across repetitions, so no arm held a favoured position in every
run.

The result is stable. It is not a single lucky run.

---

## 15. Limitations

Ordered by how much each should restrain the conclusion.

1. **`--reject-ratio 1.0`. This is the largest limitation and it is unchanged.** Admission control was
   disabled so it could not confound latency. With the shipped default, `MemoryGuard` delivers 5–6% of a
   configured budget on a loaded host, and one pressure pass can empty a large-value cache. **Every
   FastCache number here describes a configuration the product does not ship.**
2. **There is still no Java client for the sidecar protocol in the product.** `WireClient`, used by the
   sidecar arm, is ~200 lines of benchmark code in `fastcache-benchmarks`. The Spring starter builds an
   in-process engine and `fastcache.server.*` exists so Python can read a JVM's cache. **This experiment
   measures an architecture a JVM team cannot currently adopt.**
3. **One payload size, one concurrency, one workload.** 10 MiB, 8 clients, 100% GET. By design. Nothing
   here says anything about 1 MiB, 25 MiB, other concurrency levels, mixed read/write, or churn — and the
   microbenchmark's finding that FastCache's p99 is *worse* below 10 MiB is neither confirmed nor
   refuted by this run.
4. **4 vCPU, and the arms are not equally close to saturation.** The sidecar arm sustains 3× the request
   rate, so it does 3× the decoding, consuming and collecting per second on the same four cores. The
   unexplained decode difference in §8 is the visible symptom. This most likely makes 2.98× a **lower
   bound** — but that direction is inferred, not measured, and it is not claimed.
5. **Hand-rolled clients, not Lettuce or Jedis.** Deliberate and symmetric, but these are not numbers a
   Lettuce-based application would necessarily see.
6. **Serialization is a hand-rolled binary codec**, faster than Jackson, Kryo or protobuf, and used only
   by the two cross-process arms. It flatters both of them equally. A production service would pay more,
   and at 10 MiB a slower serialiser could consume a material share of the 39.6 ms advantage.
7. **Server-side GC is not instrumented.** The sidecar is a JVM with a collector; Redis has none. Only
   the application JVM's GC was measured. Cache-process CPU was measured and is in §10.
8. **Sidecar RSS grew across three cells** (§10). Three points, one cell each; not a trend, not
   diagnosed, and not something a 45-second window can settle.
9. **The response body is a digest, not the payload** (§5). Application MiB/s means bytes consumed, not
   HTTP egress.
10. **No long run, no failure injection, no constrained heap, no container/cgroup testing.** Out of scope
    by instruction.

---

## 16. Decision

### **A — Large-value product thesis survives**

> FastCache demonstrates a reproducible **2.98× end-to-end throughput advantage** over native Redis
> 7.0.15 at 10 MiB values — 353.0 req/s against 118.4 req/s, with non-overlapping ranges across three
> independent repetitions, 12 of 12 cells passing every integrity gate, and a maximum run-to-run spread
> of 6.5% against a 20% bar.

The pre-registered threshold was 1.5×. The measured ratio is 2.98×. The threshold was not changed, no
cell was discarded, no cache-level number was substituted for the end-to-end one, and the experiment was
run once.

**The large-value transport thesis has survived the end-to-end application test.**

Stated precisely, because the qualifiers are load-bearing:

- The advantage is **2.98× on throughput and 0.356× on median latency**, not the 4.5× / 0.225× the
  cache-level measurement showed. The application path dilutes it and that is the number to quote.
- **There is no tail regression at 10 MiB** — FastCache is better at p50, p95 and p99. The known tail
  regression below ~10 MiB is untouched by this run and still bounds the claim.
- **The named failure mode is real but not fatal.** Per-read allocation does reintroduce GC cost: 20.04
  MiB and 47.03 µs of GC pause per request, 21% more than Redis per request and 214× more than Caffeine.
  It did not consume the throughput advantage. The argument for the sidecar is not circular.
- **Heap relief is real and is not a differentiator.** 25 MiB against Caffeine's 636 MiB — and Redis
  delivered 25 MiB in the same cell. This confirms rather than changes the earlier conclusion.
- **CPU efficiency is a new supporting result**: ~2.4× less cache-process CPU per request than Redis.
- **Caffeine remains 30× faster** and is the right choice wherever one process can hold the data.

### What this does not decide

Per the brief, this is not a declaration that the product is validated. Three things named before this
run are still true, and none was tested by it:

1. **The configuration is unshippable.** Every number was measured with admission control disabled (§15.1).
2. **JVM applications cannot reach this architecture.** There is no Java client (§15.2).
3. **The market is narrow.** The prior thesis review found roughly one workload shape in eleven that needs
   this bandwidth. A 2.98× advantage does not widen that set; it strengthens the case within it.

The next question is the one the brief names, and it is not a benchmark:

> **Which real workload actually needs this bandwidth?**

Redis's ceiling here was 1 184 MiB/s of application-level large-value reads at 8 concurrent clients, on
4 vCPU. FastCache's was 3 530 MiB/s. A workload that needs less than ~1 GiB/s of multi-megabyte cache
reads has no reason to prefer FastCache, and gets replication, persistence, auth, failover and a client
ecosystem by choosing Redis instead.

**No implementation is recommended and none has been started. No further benchmark is recommended.**
