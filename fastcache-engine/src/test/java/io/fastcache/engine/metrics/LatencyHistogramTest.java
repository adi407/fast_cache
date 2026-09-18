package io.fastcache.engine.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The histogram behind the latency percentiles and the Prometheus buckets.
 *
 * <p>Monotonicity gets its own test because a non-monotonic bucket sequence is not a slightly wrong
 * number — Prometheus rejects the whole scrape, so one racing increment would silently take every metric
 * off the dashboard.
 */
class LatencyHistogramTest {

    private static final long MILLIS = 1_000_000L;

    @Test
    @DisplayName("counts observations and totals their duration")
    void countsAndSums() {
        LatencyHistogram histogram = new LatencyHistogram("get");
        histogram.record(1 * MILLIS);
        histogram.record(3 * MILLIS);

        assertEquals(2, histogram.count());
        assertEquals(0.004, histogram.totalSeconds(), 1e-9);
        assertEquals(2.0, histogram.meanMillis(), 1e-6);
    }

    @Test
    @DisplayName("buckets are cumulative, as the Prometheus format requires")
    void bucketsAreCumulative() {
        LatencyHistogram histogram = new LatencyHistogram("get");
        histogram.record(MILLIS / 10);   // 0.1ms
        histogram.record(5 * MILLIS);    // 5ms

        long[] counts = histogram.cumulativeCounts();
        for (int i = 1; i < counts.length; i++) {
            assertTrue(counts[i] >= counts[i - 1],
                    "bucket " + i + " (" + counts[i] + ") is below its predecessor (" + counts[i - 1]
                            + "); Prometheus rejects a non-monotonic histogram outright");
        }
        assertEquals(2, counts[counts.length - 1], "+Inf must equal the total observation count");
    }

    @Test
    @DisplayName("percentiles land in the right region")
    void percentiles() {
        LatencyHistogram histogram = new LatencyHistogram("get");
        for (int i = 0; i < 99; i++) {
            histogram.record(MILLIS / 2);   // 0.5ms
        }
        histogram.record(2 * MILLIS);       // one slow outlier

        assertTrue(histogram.percentileMillis(0.50) <= 1.0,
                "p50 should sit near the bulk at 0.5ms, got " + histogram.percentileMillis(0.50));
        assertTrue(histogram.percentileMillis(0.99) >= 0.5,
                "p99 must reflect the tail, got " + histogram.percentileMillis(0.99));
    }

    @Test
    @DisplayName("an empty histogram reports zero rather than dividing by zero")
    void emptyIsSafe() {
        LatencyHistogram histogram = new LatencyHistogram("idle");
        assertEquals(0, histogram.count());
        assertEquals(0.0, histogram.meanMillis(), 1e-9);
        assertEquals(0.0, histogram.percentileMillis(0.99), 1e-9);
        assertEquals(0, histogram.cumulativeCounts()[histogram.cumulativeCounts().length - 1]);
    }

    @Test
    @DisplayName("a negative duration is discarded, not recorded")
    void negativeIgnored() {
        LatencyHistogram histogram = new LatencyHistogram("get");
        histogram.record(-5);
        assertEquals(0, histogram.count(), "a clock that went backwards is not a measurement");
    }

    @Test
    @DisplayName("an observation past the top bound still lands in +Inf")
    void hugeObservation() {
        LatencyHistogram histogram = new LatencyHistogram("get");
        histogram.record(60L * 1_000_000_000L);   // a minute

        long[] counts = histogram.cumulativeCounts();
        assertEquals(1, counts[counts.length - 1]);
        assertEquals(0, counts[0], "it must not be counted in the fastest bucket");
        assertTrue(histogram.percentileMillis(0.99) > 0, "and must not report as instantaneous");
    }

    @Test
    @DisplayName("concurrent recording stays consistent and monotonic")
    void concurrentRecording() throws InterruptedException {
        LatencyHistogram histogram = new LatencyHistogram("get");
        int threads = 16;
        int perThread = 500;
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                pool.execute(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            histogram.record((seed + 1L) * MILLIS / 4);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
        }

        assertEquals((long) threads * perThread, histogram.count());
        long[] counts = histogram.cumulativeCounts();
        for (int i = 1; i < counts.length; i++) {
            assertTrue(counts[i] >= counts[i - 1], "buckets lost monotonicity under concurrency");
        }
        assertEquals((long) threads * perThread, counts[counts.length - 1]);
    }

    @Test
    @DisplayName("time() records the block it wraps and returns its value")
    void timeHelper() {
        LatencyHistogram histogram = new LatencyHistogram("get");
        String result = histogram.time(() -> "value");

        assertEquals("value", result);
        assertEquals(1, histogram.count());
    }
}
