package io.fastcache.bench;

import java.util.Arrays;

/**
 * Percentile summary over a sample array.
 *
 * <p>Deliberately reports p50/p95/p99/max alongside the mean, because an average alone hides exactly the
 * tail behaviour a cache benchmark exists to expose. Nothing here discards outliers.
 */
public record Stats(long count, double mean, double p50, double p95, double p99, double max, double min) {

    public static Stats of(long[] samples, int length) {
        if (length <= 0) {
            return new Stats(0, 0, 0, 0, 0, 0, 0);
        }
        long[] sorted = Arrays.copyOf(samples, length);
        Arrays.sort(sorted);
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += sorted[i];
        }
        return new Stats(length, sum / length, at(sorted, 0.50), at(sorted, 0.95), at(sorted, 0.99),
                sorted[length - 1], sorted[0]);
    }

    /** The value below which {@code q} of the distribution falls. Nearest-rank, no interpolation. */
    private static double at(long[] sorted, double q) {
        int index = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    /** Converts nanosecond samples to a microsecond view for reporting. */
    public Stats toMicros() {
        return new Stats(count, mean / 1000.0, p50 / 1000.0, p95 / 1000.0, p99 / 1000.0,
                max / 1000.0, min / 1000.0);
    }

    public String format(String unit) {
        return String.format("n=%d mean=%.1f%s p50=%.1f%s p95=%.1f%s p99=%.1f%s max=%.1f%s",
                count, mean, unit, p50, unit, p95, unit, p99, unit, max, unit);
    }

    /** Spread across repeated runs of the same experiment, reported so variability is never hidden. */
    public static String spread(double[] values) {
        if (values.length == 0) {
            return "no runs";
        }
        double min = Arrays.stream(values).min().orElse(0);
        double max = Arrays.stream(values).max().orElse(0);
        double mean = Arrays.stream(values).average().orElse(0);
        double variance = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        return String.format("mean=%.1f min=%.1f max=%.1f sd=%.1f (n=%d)",
                mean, min, max, Math.sqrt(variance), values.length);
    }
}
