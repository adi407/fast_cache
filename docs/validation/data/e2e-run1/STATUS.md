# STATUS — end-to-end JVM matrix, run 1

## PARTIAL — 7 of 8 rows VALID. One row INVALID, and it is the most important cell in the matrix.

Full analysis: [`../../THESIS_REASSESSMENT.md`](../../THESIS_REASSESSMENT.md) §Benchmark integrity.

The run was aborted after 8 cells. Windows / Memurai 4.1.2, `-Xmx4g`, G1, `--reject-ratio 1.0`,
15 s warmup discarded, 45 s measured.

## Row classification — `steady-partial.csv`

| Cell | Classification | Reason |
|---|---|---|
| `steady-caffeine-1m-c8` | **VALID** | entries 512, corrupt 0, misses 512 = loaders 512 (one cold fill per key) |
| `steady-fastcache-embedded-1m-c8` | **VALID** | as above |
| `steady-fastcache-sidecar-1m-c8` | **VALID** | as above |
| `steady-redis-1m-c8` | **VALID** | as above |
| `steady-caffeine-10m-c8` | **VALID** | entries 51, corrupt 0 |
| `steady-fastcache-embedded-10m-c8` | **VALID** | entries 51, corrupt 0 |
| `steady-fastcache-sidecar-10m-c8` | **INVALID — harness contamination** | see below |
| `steady-caffeine-25m-c8` | **VALID** | entries 20, corrupt 0 |
| `steady-fastcache-embedded-25m-c8` | **INVALID — host resource exhaustion** | 608 × `OutOfMemoryError: Java heap space`; no CSV row produced. Its applog is in `../e2e/`. |
| `steady-redis-10m-c8`, `steady-redis-25m-c8`, all `c1`/`c32` cells | **NEEDS RE-RUN** | never executed; run aborted |

## The invalid row

`steady-fastcache-sidecar-10m-c8` reports **55 941 corrupt reads out of 55 941**. Two independent
confirmations that it was not transporting 10 MB:

- `entries` reads **512** — the 1 MB tier's key count, not 10 MB's 51.
- `lookupP50us` is **3 194 µs**, statistically indistinguishable from the same arm's 1 MB cell at
  **3 108 µs**.

The sidecar was not flushed between payload tiers, so this cell read back **stale 1 MB payloads under
10 MB labels**. The payload-length validation added as Defect 3's fix is what caught it.

**Consequence:** there is no valid end-to-end measurement of the FastCache sidecar above 1 MB, and Redis
was never run end-to-end at 10 MB or 25 MB at all. That is the region where the product's only
differentiator lives, and re-measuring it is the single next experiment.

## Reading caveat on the valid rows

All cells ran on Windows against Memurai 4.1.2, where native Redis is known to be materially faster below
~1 MB and equivalent at ≥10 MB. **The 1 MB comparison is therefore pessimistic for Redis**, which makes
FastCache's 1.31× throughput edge at 1 MB an upper bound rather than a measurement.
