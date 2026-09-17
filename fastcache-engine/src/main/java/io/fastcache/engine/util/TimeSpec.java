package io.fastcache.engine.util;

import java.util.Locale;

/**
 * Parses the human TTL grammar used across every FastCache surface ({@code @FastCache(ttl = "15m")},
 * {@code @fastcache(ttl="15m")}, {@code --default-ttl=15m}).
 *
 * <p>Grammar: {@code <number><unit>} where unit is one of {@code ms, s, m, h, d}. A bare number is
 * interpreted as <em>milliseconds</em> (machine-facing default — never seconds, because a silent
 * 1000x TTL error is the kind of bug that only shows up in production).
 */
public final class TimeSpec {

    /** Sentinel meaning "never expires". */
    public static final long NEVER = -1L;

    private TimeSpec() {
    }

    public static long parseMillis(String spec) {
        if (spec == null) {
            throw new IllegalArgumentException("ttl spec must not be null");
        }
        String s = spec.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("ttl spec must not be blank");
        }
        if (s.equals("never") || s.equals("inf") || s.equals("infinite") || s.equals("0")) {
            return NEVER;
        }

        int split = 0;
        while (split < s.length() && (Character.isDigit(s.charAt(split)) || s.charAt(split) == '.')) {
            split++;
        }
        if (split == 0) {
            throw new IllegalArgumentException("ttl spec must start with a number: '" + spec + "'");
        }

        double magnitude;
        try {
            magnitude = Double.parseDouble(s.substring(0, split));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("unparseable ttl magnitude: '" + spec + "'", e);
        }
        if (magnitude < 0) {
            throw new IllegalArgumentException("ttl must not be negative: '" + spec + "'");
        }

        String unit = s.substring(split).trim();
        long multiplier = switch (unit) {
            case "", "ms" -> 1L;
            case "s", "sec", "secs" -> 1_000L;
            case "m", "min", "mins" -> 60_000L;
            case "h", "hr", "hrs" -> 3_600_000L;
            case "d", "day", "days" -> 86_400_000L;
            default -> throw new IllegalArgumentException("unknown ttl unit '" + unit + "' in '" + spec + "'");
        };

        double millis = magnitude * multiplier;
        if (millis <= 0) {
            return NEVER;
        }
        // Saturate rather than overflow: a 300-year TTL is indistinguishable from "never" in practice.
        return millis >= Long.MAX_VALUE / 4.0 ? NEVER : (long) millis;
    }

    /** Renders a millisecond duration back into the compact grammar (for stats / logging). */
    public static String format(long millis) {
        if (millis == NEVER) {
            return "never";
        }
        if (millis % 86_400_000L == 0) return (millis / 86_400_000L) + "d";
        if (millis % 3_600_000L == 0) return (millis / 3_600_000L) + "h";
        if (millis % 60_000L == 0) return (millis / 60_000L) + "m";
        if (millis % 1_000L == 0) return (millis / 1_000L) + "s";
        return millis + "ms";
    }
}
