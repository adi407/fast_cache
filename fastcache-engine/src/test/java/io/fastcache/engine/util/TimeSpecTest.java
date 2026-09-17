package io.fastcache.engine.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TTL grammar, which must stay byte-identical to the Python implementation in {@code ttl.py} —
 * {@code ttl="15m"} is copy-pasteable between a Spring annotation and a Python decorator, and it would be
 * a nasty surprise if the two runtimes disagreed about what it meant.
 */
class TimeSpecTest {

    @ParameterizedTest(name = "{0} -> {1}ms")
    @CsvSource({
            "500ms, 500",
            "30s,   30000",
            "15m,   900000",
            "2h,    7200000",
            "1d,    86400000",
            "250,   250",
            "1.5s,  1500",
    })
    @DisplayName("parses the shared duration grammar")
    void parsesDurations(String spec, long expected) {
        assertEquals(expected, TimeSpec.parseMillis(spec));
    }

    @ParameterizedTest
    @CsvSource({"never", "inf", "infinite", "0"})
    @DisplayName("recognises the eternal sentinels")
    void parsesNever(String spec) {
        assertEquals(TimeSpec.NEVER, TimeSpec.parseMillis(spec));
    }

    @Test
    @DisplayName("a bare number means milliseconds, not seconds")
    void bareNumberIsMillis() {
        // Deliberate: a silent 1000x TTL error is the kind of bug that only shows up in production.
        assertEquals(250, TimeSpec.parseMillis("250"));
    }

    @Test
    @DisplayName("is case- and whitespace-insensitive")
    void tolerantOfFormatting() {
        assertEquals(900_000, TimeSpec.parseMillis("  15M  "));
        assertEquals(30_000, TimeSpec.parseMillis("30S"));
    }

    @ParameterizedTest
    @CsvSource({"''", "'   '", "fortnight, 15 fortnights", "-5m", "m", "abc"})
    @DisplayName("rejects nonsense loudly")
    void rejectsGarbage(String spec) {
        assertThrows(IllegalArgumentException.class, () -> TimeSpec.parseMillis(spec));
    }

    @Test
    @DisplayName("rejects null rather than defaulting silently")
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TimeSpec.parseMillis(null));
    }

    @Test
    @DisplayName("a very long TTL is passed through, never wrapped negative")
    void largeDurationsDoNotOverflow() {
        // 999999999 days is ~2.7 million years: far beyond any real TTL, but well inside the range a
        // long can hold, so it is returned as written rather than clamped.
        long parsed = TimeSpec.parseMillis("999999999d");
        assertTrue(parsed > 0, "a huge TTL must never wrap to a negative duration that expires instantly");
        assertEquals(999999999L * 86_400_000L, parsed);
    }

    @Test
    @DisplayName("a magnitude that would overflow the narrowing becomes 'never'")
    void absurdDurationsSaturate() {
        assertEquals(TimeSpec.NEVER, TimeSpec.parseMillis("99999999999999999999999d"));
    }

    @ParameterizedTest(name = "{0}ms -> {1}")
    @CsvSource({
            "900000,   15m",
            "30000,    30s",
            "7200000,  2h",
            "86400000, 1d",
            "1500,     1500ms",
            "-1,       never",
    })
    @DisplayName("formats back into the compact grammar")
    void formats(long millis, String expected) {
        assertEquals(expected, TimeSpec.format(millis));
    }

    @Test
    @DisplayName("format and parse round-trip")
    void roundTrips() {
        for (String spec : new String[]{"15m", "30s", "2h", "1d", "500ms"}) {
            assertEquals(spec, TimeSpec.format(TimeSpec.parseMillis(spec)));
        }
    }
}
