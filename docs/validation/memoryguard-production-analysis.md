# MemoryGuard: production capacity analysis

**Analysis only. No implementation change is proposed for this task and none was made.**

Every favourable latency number FastCache has produced was measured with `--reject-ratio 1.0`, which
disables admission control. That is not the shipped default. This document establishes what the shipped
default actually does, why, and whether the design is recoverable — so that the gap between "the
benchmark configuration" and "the product" stops being a footnote.

---

## 1. What was measured

`GuardProbe` fills an engine with 4 MB payloads until the first refusal, then reports how much of the
configured budget had been reserved at that moment. Host at the time: 16 157 MB total, **1 663 MB free,
0.8971 used**. The guard's reject threshold is 0.85.

| Configured budget | Gate floor | First refusal at | **Budget honoured** | Physical ratio | What refused |
|---:|---:|---:|---:|---:|---|
| 128 MB | 64 MB | 64 MB | **50.0%** | 0.9045 | machine gate |
| 512 MB | 64 MB | 64 MB | **12.5%** | 0.9081 | machine gate |
| 1024 MB | 64 MB | 64 MB | **6.3%** | 0.9110 | machine gate |
| 4096 MB | 204 MB | 208 MB | **5.1%** | 0.9182 | machine gate |

**The first refusal lands on the gate floor in every row**, not on the budget. Usable capacity on this
host was `max(64 MiB, 5% of budget)` — so raising the budget from 1 GB to 4 GB bought 144 MB of usable
cache and 3 GB of nothing.

A second effect compounds it. In a drain test — fill, then stop writing entirely, 10-minute TTL so
nothing can expire — the cache shed **25 of 52 entries within 5 seconds** and then stabilised. And in the
Scenario A fill at 25 MB and 50 MB payloads, writes were accepted and `entries` read **0** moments later.

---

## 2. Why configured and usable capacity diverge

Three mechanisms compose. Each is individually defensible; the combination is not.

### 2.1 The machine-pressure gate arms almost immediately

`MemoryGuard` evaluates two ceilings. The budget ceiling (`reserved / budgetBytes ≥ 0.85`) is the one
operators think they are configuring. The machine ceiling (`PhysicalMemory.usedRatio() ≥ 0.85`) engages
only once this engine's own reservation crosses:

```java
physicalGateFloorBytes = Math.max(64L * 1024 * 1024, (long) (budgetBytes * 0.05));
```

For any cache of large values, 64 MiB is three payloads. The gate is effectively always armed.

### 2.2 The machine reading is "free", not "available"

`PhysicalMemory.usedRatio()` is `1 - free/total`, built on
`com.sun.management.OperatingSystemMXBean.getFreeMemorySize()`. Its own Javadoc states the problem:

> this is **free**, not **available**: the OS counts reclaimable file-cache and standby pages as used, so
> a perfectly healthy host routinely reports 80–90% used.

So the number the gate consults is, on an ordinary machine, already above the 0.85 reject threshold before
FastCache allocates anything. **The gate does not detect memory pressure. It detects a busy page cache.**

### 2.3 Relief is judged on a quantity the cache cannot move

A budget-sourced rejection self-corrects: shed memory, the ratio falls below `reliefRatio` (0.78), writes
resume. A machine-sourced rejection does not. Shedding 300 MB on a 16 GB host moves the physical ratio by
under two points, and the reclaimable pages that inflated it in the first place do not belong to FastCache.

The only escape is the gate's own release threshold — `physicalGateReleaseBytes` = 75% of the floor — so
the engine sheds until its reservation drops below ~48 MiB, disarms, refills past 64 MiB, re-arms, and
repeats. **That is the sawtooth the original soak recorded** (off-heap oscillating 183–442 MB,
59 741 shed writes, 56% hit rate) and it is why that soak could not certify anything.

### 2.4 The eviction floor turns shedding into a wipe

While the guard is rejecting, `EvictionSweeper.sweepShard` sheds:

```java
int slice = Math.max(1, (int) (shard.size() * PRESSURE_EVICTION_FRACTION));  // fraction = 0.10
```

`Math.max(1, …)` is an absolute floor of **one entry per shard per pass**, every second, across 32 shards.
The intent is clearly "shed 10%". The effect depends entirely on entry count:

| entries per shard | intended 10% | actual shed per pass | actual rate |
|---:|---:|---:|---|
| 1 000 | 100 | 100 | 10% |
| 10 | 1 | 1 | 10% |
| 3 | 0 | **1** | **33%** |
| 1 | 0 | **1** | **100%** |

A cache of 20 × 25 MB entries spread over 32 shards holds ≤ 1 entry in most shards. **One pressure pass
evicts essentially all of it.** The failure mode is precisely inverted relative to the product's target:
the larger the values, the fewer the entries, and the more total the wipe.

---

## 3. Is the design fundamentally wrong?

**The intent is right. Two of the three parameter choices are wrong, and one interaction is unsound.**

| Element | Verdict |
|---|---|
| Having a machine-pressure ceiling at all | **Sound.** An off-heap cache that ignores host memory will get the process OOM-killed with an empty Java heap. |
| Gating it behind the engine's own reservation | **Sound, and well-argued** — an engine holding 5 MB should not refuse writes because the host is busy. |
| Floor at `max(64 MiB, 5% of budget)` | **Wrong for the target workload.** Three 25 MB values cross it. The floor should scale with the budget alone, not with a constant sized for small-value caches. |
| Using `free` rather than `available` | **Wrong.** It systematically reads 80–90% on healthy hosts, which is the documented behaviour of the API, acknowledged in the code, and then used anyway as a threshold input. |
| Rejecting on a quantity the cache cannot influence | **Unsound.** A controller whose actuator does not move its measured variable cannot stabilise; it can only oscillate or latch. This one latches until the gate's own hysteresis releases it. |
| `Math.max(1, size × 0.10)` | **A defect.** It converts a proportional policy into an absolute one at low entry counts, in the direction that destroys large-value caches. |

So: not fundamentally wrong as a concept, but **the shipped defaults do not deliver the configured
capacity on an ordinary host, and the failure is worst exactly where the product claims to be strongest.**

---

## 4. Is the behaviour acceptable for any deployment?

| Deployment | Verdict |
|---|---|
| Dedicated host with ≥50% genuinely free RAM | Acceptable — the gate rarely arms. |
| Developer laptop | **Not acceptable.** Measured: 6.3% of a 1 GB budget. |
| Well-packed container | **Unknown, probably worse.** A container is sized close to its working set by design, so the used ratio sits high permanently. `getTotalMemorySize` is container-aware on modern JDKs; whether `getFreeMemorySize` is container-aware was **not tested** and must not be assumed. This is the single largest untested surface. |
| Large-value cache (the target) | **Not acceptable.** Few entries relative to 32 shards means one pressure pass can empty the cache. |

---

## 5. A corrected capacity model — proposed, not implemented

Stated so the fix can be argued about before anyone writes it. Each item is independent.

**5.1 Honour the budget as a capacity, and make machine pressure a separate, louder signal.**
The budget ceiling should be the only thing that refuses an ordinary write. Machine pressure should
trigger a distinct, observable state ("shedding under host pressure") rather than silently masquerading as
the cache being full. An operator who configured 4 GB and got 208 MB should be able to see why without a
profiler.

**5.2 Read `available`, not `free`.** On Linux, `MemAvailable` from `/proc/meminfo` is the number that
means what this gate wants. The JDK does not expose it, so it needs a platform probe with the current
reading as fallback. Until then the threshold is measuring the page cache.

**5.3 Make the gate floor a pure function of the budget.** `max(64 MiB, 5%)` is a constant chosen for
small-value caches. `25% of budget` (with no absolute floor) would arm the gate when FastCache is
plausibly a contributor to host pressure, which is the stated intent, without arming at three payloads.

**5.4 Remove the `max(1, …)` floor and shed by bytes.** `Math.max(1, size × 0.10)` should be
`(int) Math.floor(size × 0.10)` — shedding nothing from a shard holding fewer than ten entries is the
correct behaviour, not a bug to be patched around. For a byte-bounded cache the proportional unit should
be bytes, not entries, since entries are not the resource under pressure.

**5.5 Evict to fit rather than refuse.** Redis with `allkeys-lru` accepted **0 refused writes** across
every run of the comparison; FastCache refused tens of thousands. A byte-bounded cache whose response to
being full is to reject new writes and retain old entries preserves cold data and discards hot data —
the opposite of what an LRU cache is for. This is the change most likely to matter to a real user, and
the least visible in a latency benchmark.

**5.6 Regression tests before any of it.** At minimum: a budget is honoured to within 10% on a host above
the physical threshold; a cache with fewer entries than shards loses ≤ 20% to one pressure pass; and a
full cache evicts rather than refuses.

---

## 6. What this means for the published numbers

Every FastCache latency figure in `native-linux-redis-validation.md` and
`end-to-end-jvm-results.md` was produced with `--reject-ratio 1.0`.

Those numbers are **valid as a measurement of the engine's transport and storage paths** — which is what
they were for — and **invalid as a description of what a user would experience today**, because with
shipped defaults on a loaded host the cache would not hold the working set the benchmark assumes.

The honest framing: *FastCache's data path is fast; its admission-control path currently prevents that
data path from being used at the configured scale on an ordinary machine.* Until §5 is addressed, the
performance results describe a potential, not a product.
