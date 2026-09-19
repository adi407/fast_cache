package io.fastcache.bench;

import java.util.Random;

/**
 * Deterministic payload generation.
 *
 * <p>Payloads are built from a pre-generated pool rather than per call. Generating 100 MB of random bytes
 * inside a timed loop would make the benchmark a measurement of {@link Random}, and the allocation of the
 * source array would contaminate exactly the GC readings the experiment exists to take.
 *
 * <p>The pool is incompressible random data on purpose: a payload of repeated zeroes lets the OS collapse
 * pages and would make RSS readings meaningless.
 */
public final class Payloads {

    private static final int POOL_BYTES = 8 << 20;   // 8 MB, large enough that slices rarely repeat
    private static final byte[] POOL;

    static {
        POOL = new byte[POOL_BYTES];
        new Random(20260919L).nextBytes(POOL);
    }

    private Payloads() {
    }

    /**
     * A distinct byte array of the requested size.
     *
     * <p>The array is freshly allocated, because every arm must receive a value it can own. The
     * <em>contents</em> come from the shared pool at a rotating offset, so no two payloads are identical
     * and no time is spent in the RNG.
     */
    public static byte[] of(int size, int seed) {
        byte[] value = new byte[size];
        int offset = Math.floorMod(seed * 4099, POOL_BYTES);
        int written = 0;
        while (written < size) {
            int chunk = Math.min(size - written, POOL_BYTES - offset);
            System.arraycopy(POOL, offset, value, written, chunk);
            written += chunk;
            offset = 0;
        }
        // Stamp the seed so a corrupted or swapped payload is detectable on read-back.
        if (size >= 4) {
            value[0] = (byte) (seed >>> 24);
            value[1] = (byte) (seed >>> 16);
            value[2] = (byte) (seed >>> 8);
            value[3] = (byte) seed;
        }
        return value;
    }

    /** Reads back the stamp written by {@link #of}, for integrity checks. */
    public static int seedOf(byte[] value) {
        if (value == null || value.length < 4) {
            return -1;
        }
        return ((value[0] & 0xFF) << 24) | ((value[1] & 0xFF) << 16)
                | ((value[2] & 0xFF) << 8) | (value[3] & 0xFF);
    }

    public static String label(int bytes) {
        if (bytes >= 1 << 20) {
            return (bytes / (1 << 20)) + "MB";
        }
        return (bytes / 1024) + "KB";
    }
}
