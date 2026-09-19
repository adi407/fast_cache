# Acceptance criteria, fixed before measurement

Written and committed **before** the benchmark was run, so that no threshold can be chosen to fit a
result. Where a threshold is arbitrary it is marked as such and justified.

Arms under test:

| Arm | Storage | Process | Notes |
|---|---|---|---|
| `caffeine` | on-heap | in-process | `maximumWeight` + byte `Weigher`, equal budget |
| `fastcache-embedded` | **on-heap** (`putReference`) | in-process | what Spring actually uses |
| `fastcache-sidecar` | **off-heap** | second JVM | the only off-heap configuration |

---

## H1 — FastCache materially reduces JVM heap pressure for large cached values

**SUPPORTED if** at equal resident cache payload (same keys, same sizes), the application JVM's heap
occupancy after a forced collection is **at least 50% lower** than Caffeine's, at 3 or more of the 7
payload sizes, across at least 3 repeated runs.

**NOT SUPPORTED if** the reduction is under 20%, or is not reproducible across runs.

*Arbitrary threshold note:* 50% is chosen because a smaller reduction would not justify adopting a second
process. Anything under 20% is within the noise of heap sizing decisions a team could make for free.

---

## H2 — FastCache materially reduces GC pause impact during large-object churn

**SUPPORTED if**, under Scenario C (sustained PUT/GET/REPLACE/EVICT) at 5 MB and above, FastCache shows
**both**:
- total GC pause time at least 40% lower than Caffeine over an equal-duration, equal-operation-count run,
  **and**
- p99 individual GC pause no worse than Caffeine's,

reproduced across at least 3 runs, with the run-to-run spread reported.

**NOT SUPPORTED if** total pause time is within ±20% of Caffeine, or if p99 pause is worse.

**INCONCLUSIVE if** GC counts are too low (fewer than 10 collections per arm) to compare distributions.

---

## H3 — FastCache provides useful performance despite higher cache-hit overhead

**SUPPORTED if** there exists a measured loader cost `L` such that FastCache's end-to-end request latency
is within 10% of Caffeine's, and `L` is **at or below 50 ms** — i.e. the overhead is amortised by work
that a realistic document-processing or model call would plausibly do.

**NOT SUPPORTED if** the required `L` exceeds 500 ms, which would mean only the most expensive possible
workloads can hide the overhead.

---

## H4 — FastCache safely releases off-heap memory

**SUPPORTED if**, across Scenarios D/E/F/G and the long-running churn of Scenario H:
- `offheap_slots` returns to its baseline (within ±5 slots) after each cycle, **and**
- RSS floor (10th percentile, matching the existing soak harness convention) grows **less than 10%**
  between the first and last quarter of a run of at least 30 minutes, **and**
- the guard is quiet (write shedding in under 25% of sample intervals) so the measurement means something.

**NOT SUPPORTED if** RSS floor growth exceeds 25%, or slots do not return to baseline.

**INCONCLUSIVE if** the guard sheds in more than 25% of intervals, or sampling gaps prevent a continuous
comparison — the same abstention rule the existing soak harness uses.

---

## H5 — FastCache's cross-process capability provides meaningful practical value

**SUPPORTED if** a second process can read values written by the first with:
- correctness (byte-identical), **and**
- p99 read latency under 10 ms for payloads up to 10 MB, **and**
- an operational model measurably simpler than Redis (measured as: process count, configuration files,
  and whether the second process starts itself).

**NOT SUPPORTED if** cross-process read latency is worse than a local Redis performing the same
operation, since Redis then dominates on every axis except installation.

---

## Fairness rules in force

- Caffeine gets a byte-accurate `Weigher` and an equal memory budget. No artificially small cache.
- All arms run the same JVM, same flags, same heap size, same payloads, same key distribution.
- Warmup is measured and discarded explicitly, never skipped.
- Every latency figure is labelled **client-observed** or **server-side**; the two are never mixed.
- FastCache's Python L1 is not involved in any arm — this is a JVM benchmark. The sidecar arm has no
  client-side cache, so it is compared against a cold Caffeine on equal terms.
- Every scenario runs `--repeat` times; all runs are reported, not the best one.
- Both outcomes are publishable. A NOT SUPPORTED verdict on H1/H2 is the expected result given the Phase 1
  code reading, and will be reported as such.
