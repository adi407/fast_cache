package io.fastcache.e2e;

import io.fastcache.bench.Payloads;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * The service under test. Identical logic for every arm; only the injected {@link Backends.Backend}
 * differs.
 *
 * <p>The request is deliberately split into measurable phases rather than timed as a lump:
 *
 * <pre>
 *   lookup   - getting bytes (or a reference) out of the cache
 *   decode   - turning bytes into an object graph, zero for in-process arms
 *   business - consuming the value, which every arm pays equally
 * </pre>
 *
 * <p>Reporting only total latency would hide the thing this experiment exists to find: whether the
 * sidecar's transport advantage is spent again on decoding what it transported.
 */
public final class DocumentService {

    /** Per-request phase timings. Caller-owned and reused, so measuring costs no allocation. */
    public static final class Timings {
        public long lookupNanos;
        public long decodeNanos;
        public long businessNanos;
        public boolean hit;

        public long totalNanos() {
            return lookupNanos + decodeNanos + businessNanos;
        }
    }

    /**
     * Thrown the instant a value fails verification, rather than after the run.
     *
     * <p>The previous round recorded {@code corrupt=55941} in a CSV column and let the cell finish, which
     * produced a complete, plausible, entirely meaningless result table. A cell that reads the wrong
     * payload is void, so it now fails loudly at the first bad read and the harness discards it.
     */
    public static final class CorruptValueException extends RuntimeException {
        CorruptValueException(String reason) {
            super(reason);
        }
    }

    private final Backends.Backend backend;
    private final int payloadBytes;
    private final long missCostMillis;
    private final boolean flatRepresentation;

    /**
     * Expected content digest per seed, computed once at construction.
     *
     * <p>Indexed by seed, which the load generator draws from {@code [0, entries)}. Precomputed because
     * the alternative — deriving the canonical payload per request to compare against — would allocate a
     * payload-sized array on every single read, which is the exact quantity under measurement.
     */
    private final long[] expectedDigests;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder loaderRuns = new LongAdder();
    private final LongAdder corrupt = new LongAdder();
    private final AtomicReference<String> corruptReason = new AtomicReference<>();

    public DocumentService(Backends.Backend backend, int payloadBytes, long missCostMillis,
                           boolean flatRepresentation, int entries) {
        this.backend = backend;
        this.payloadBytes = payloadBytes;
        this.missCostMillis = missCostMillis;
        this.flatRepresentation = flatRepresentation;
        this.expectedDigests = new long[Math.max(1, entries)];
        for (int seed = 0; seed < expectedDigests.length; seed++) {
            expectedDigests[seed] = Payloads.digest(Payloads.of(payloadBytes, seed));
        }
    }

    /** The precomputed digest for a seed, or {@link LargeResponse#SKIP_DIGEST} outside the known range. */
    private long expectedDigest(int seed) {
        return seed >= 0 && seed < expectedDigests.length
                ? expectedDigests[seed]
                : LargeResponse.SKIP_DIGEST;
    }

    public Backends.Backend backend() {
        return backend;
    }

    /**
     * The request path: look up, decode if the backend hands back bytes, consume, and on a miss run the
     * expensive load and populate.
     */
    public LargeResponse fetch(String documentId, int seed, Timings timings) {
        LargeResponse value;

        long t0 = System.nanoTime();
        if (backend.decodesOnHit()) {
            byte[] encoded = backend.getEncoded(documentId);
            long t1 = System.nanoTime();
            timings.lookupNanos = t1 - t0;
            if (encoded == null) {
                timings.decodeNanos = 0;
                value = null;
            } else {
                value = Codec.decode(encoded);
                timings.decodeNanos = System.nanoTime() - t1;
            }
        } else {
            value = backend.getReference(documentId);
            timings.lookupNanos = System.nanoTime() - t0;
            timings.decodeNanos = 0;
        }

        timings.hit = value != null;
        if (value == null) {
            misses.increment();
            value = load(documentId, seed);
            backend.put(documentId, value);
        } else {
            hits.increment();
            String reason = value.verify(documentId, payloadBytes, seed, expectedDigest(seed));
            if (reason != null) {
                corrupt.increment();
                corruptReason.compareAndSet(null, reason);
                throw new CorruptValueException(reason);
            }
        }

        long t2 = System.nanoTime();
        // Touch the value. A service that fetches 25 MB and never reads it lets the JIT elide work a
        // real service cannot, and would make every arm look better than it is.
        long consumed = value.consume();
        timings.businessNanos = System.nanoTime() - t2;
        if (consumed == Long.MIN_VALUE) {
            throw new IllegalStateException("unreachable; keeps consume() from being optimised away");
        }
        return value;
    }

    /** Stands in for the expensive work the cache exists to avoid. */
    private LargeResponse load(String documentId, int seed) {
        loaderRuns.increment();
        try {
            Thread.sleep(missCostMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return flatRepresentation
                ? LargeResponse.flat(documentId, payloadBytes, seed)
                : LargeResponse.of(documentId, payloadBytes, seed);
    }

    /** A lookup with no loading and no timing, for the stampede harness. */
    public LargeResponse peek(String documentId) {
        if (backend.decodesOnHit()) {
            byte[] encoded = backend.getEncoded(documentId);
            return encoded == null ? null : Codec.decode(encoded);
        }
        return backend.getReference(documentId);
    }

    /** Stores a value the stampede harness loaded itself. */
    public void populate(String documentId, LargeResponse value) {
        backend.put(documentId, value);
    }

    /**
     * Whatever request coalescing the backend provides unaided.
     *
     * <p>Caffeine and the embedded engine both have a read-through that collapses concurrent loads. The
     * cross-process arms do not expose one to Java, so they fall through to loading per caller - which is
     * the measurement, not a gap in the harness.
     */
    public LargeResponse nativeReadThrough(String documentId, java.util.function.Supplier<LargeResponse> loader) {
        return backend.readThrough(documentId, loader);
    }

    public long hits() { return hits.sum(); }
    public long misses() { return misses.sum(); }
    public long loaderRuns() { return loaderRuns.sum(); }
    public long corrupt() { return corrupt.sum(); }
    public String corruptReason() { return corruptReason.get(); }

    /**
     * Zeroes the hit accounting so it describes the measured window rather than the run.
     *
     * <p>Necessary for the hit-ratio gate to mean anything. The cold fill contributes one miss per key,
     * and against a slow arm those misses are a large enough fraction of a 45-second window to push the
     * ratio under a 99.9% bar on their own: 51 fill misses against ~45 000 hits is 0.9989. Gating on the
     * cumulative figure would therefore fail the arm that is merely slower, which is the opposite of what
     * the gate is for.
     *
     * <p>In the measured window of a fully populated 100% GET run, misses and loader runs must both be
     * exactly zero — a stricter check than the ratio, and the one that actually catches mid-run eviction.
     *
     * <p>{@code corrupt} is deliberately not reset: a corrupt read during warmup still voids the cell.
     */
    public void resetCounters() {
        hits.reset();
        misses.reset();
        loaderRuns.reset();
    }

    /**
     * Reads every populated key back and verifies it, outside any timed path.
     *
     * <p>Run after the fill and before the warmup, so a cell that would have measured the wrong payload
     * size never reaches its measurement window.
     *
     * @return null when every entry verified, otherwise the first failure
     */
    public String verifyAll(int entries) {
        for (int seed = 0; seed < entries; seed++) {
            String id = "doc-" + seed;
            LargeResponse value = peek(id);
            if (value == null) {
                return "missing after populate: " + id;
            }
            String reason = value.verify(id, payloadBytes, seed, expectedDigest(seed));
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    public double hitRatio() {
        long total = hits.sum() + misses.sum();
        return total == 0 ? 0 : (double) hits.sum() / total;
    }
}
