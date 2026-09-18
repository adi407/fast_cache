package io.fastcache.engine.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * A fixed-bucket latency histogram, sized so it can be emitted directly as a Prometheus histogram.
 *
 * <h2>Why buckets rather than a reservoir</h2>
 * The obvious way to get percentiles is to keep samples and sort them, which means either an unbounded
 * list or a reservoir with a lock. Both put allocation and contention on the hot path of a cache whose
 * whole premise is that a hit costs a hash lookup — measuring the operation would become more expensive
 * than the operation.
 *
 * <p>Bucketed counting costs one comparison loop and one {@link LongAdder} increment, allocates nothing,
 * and never blocks. The price is that percentiles are <em>interpolated</em> rather than exact: p99 is
 * reported as a point inside the bucket where the 99th sample falls. That is the same trade every
 * Prometheus histogram makes, and it is the right one — knowing p99 is "between 1ms and 2.5ms" is
 * actionable, and knowing it is exactly 1.83ms almost never changes a decision.
 *
 * <h2>Why these boundaries</h2>
 * They span five orders of magnitude because this cache legitimately serves both: an in-process hit on a
 * small value lands in the tens of microseconds, while a 50&nbsp;MB payload crossing a socket takes tens
 * of milliseconds. A histogram tuned for one would be useless for the other.
 */
public final class LatencyHistogram {

    /** Upper bounds in seconds, matching Prometheus convention. The final +Inf bucket is implicit. */
    static final double[] BUCKET_BOUNDS_SECONDS = {
            0.000_05, 0.000_1, 0.000_25, 0.000_5,
            0.001, 0.002_5, 0.005, 0.01, 0.025, 0.05,
            0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0
    };

    private static final long[] BUCKET_BOUNDS_NANOS = new long[BUCKET_BOUNDS_SECONDS.length];

    static {
        for (int i = 0; i < BUCKET_BOUNDS_SECONDS.length; i++) {
            BUCKET_BOUNDS_NANOS[i] = (long) (BUCKET_BOUNDS_SECONDS[i] * 1_000_000_000L);
        }
    }

    private final String name;
    /** One extra slot for the implicit +Inf bucket. */
    private final LongAdder[] buckets = new LongAdder[BUCKET_BOUNDS_NANOS.length + 1];
    private final LongAdder count = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();

    public LatencyHistogram(String name) {
        this.name = name;
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    public String name() {
        return name;
    }

    /**
     * Records one observation.
     *
     * <p>Cumulative by Prometheus convention: a sample lands in its own bucket <em>and every bucket above
     * it</em>. Doing that at record time rather than at scrape time keeps the read path trivial, at the
     * cost of up to 18 increments per observation — all of them uncontended {@link LongAdder} cells.
     */
    public void record(long nanos) {
        if (nanos < 0) {
            return; // A clock that went backwards is not a measurement.
        }
        count.increment();
        totalNanos.add(nanos);
        for (int i = 0; i < BUCKET_BOUNDS_NANOS.length; i++) {
            if (nanos <= BUCKET_BOUNDS_NANOS[i]) {
                buckets[i].increment();
            }
        }
        buckets[buckets.length - 1].increment(); // +Inf always counts everything.
    }

    /** Times a block and records it. Returns whatever the block returned. */
    public <T> T time(java.util.function.Supplier<T> operation) {
        long started = System.nanoTime();
        try {
            return operation.get();
        } finally {
            record(System.nanoTime() - started);
        }
    }

    public long count() {
        return count.sum();
    }

    public double totalSeconds() {
        return totalNanos.sum() / 1_000_000_000.0;
    }

    public double meanMillis() {
        long observations = count.sum();
        return observations == 0 ? 0.0 : (totalNanos.sum() / (double) observations) / 1_000_000.0;
    }

    /** Cumulative bucket counts, aligned with {@link #BUCKET_BOUNDS_SECONDS} plus a trailing +Inf. */
    public long[] cumulativeCounts() {
        long[] snapshot = new long[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            snapshot[i] = buckets[i].sum();
        }
        // The adders are read one at a time, so a fast writer can make a lower bucket appear to exceed a
        // higher one. Monotonicity is a hard requirement of the Prometheus format, so repair it here
        // rather than emit something a scraper will reject.
        for (int i = 1; i < snapshot.length; i++) {
            snapshot[i] = Math.max(snapshot[i], snapshot[i - 1]);
        }
        return snapshot;
    }

    /**
     * Approximate percentile in milliseconds, interpolated within the bucket the sample falls in.
     *
     * @param quantile between 0 and 1, e.g. 0.99
     */
    public double percentileMillis(double quantile) {
        long[] counts = cumulativeCounts();
        long total = counts[counts.length - 1];
        if (total == 0) {
            return 0.0;
        }
        double target = quantile * total;
        for (int i = 0; i < BUCKET_BOUNDS_SECONDS.length; i++) {
            if (counts[i] >= target) {
                double lowerBound = i == 0 ? 0.0 : BUCKET_BOUNDS_SECONDS[i - 1];
                double upperBound = BUCKET_BOUNDS_SECONDS[i];
                long lowerCount = i == 0 ? 0 : counts[i - 1];
                long inBucket = counts[i] - lowerCount;
                double fraction = inBucket == 0 ? 0.0 : (target - lowerCount) / inBucket;
                return (lowerBound + (upperBound - lowerBound) * fraction) * 1000.0;
            }
        }
        // Everything landed in +Inf: report the top boundary rather than infinity, which no dashboard
        // renders usefully.
        return BUCKET_BOUNDS_SECONDS[BUCKET_BOUNDS_SECONDS.length - 1] * 1000.0;
    }

    public void reset() {
        for (LongAdder bucket : buckets) {
            bucket.reset();
        }
        count.reset();
        totalNanos.reset();
    }
}
