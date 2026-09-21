package io.fastcache.e2e;

import io.fastcache.bench.Payloads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Representation B: a realistic response object rather than a bare array.
 *
 * <p>The point of this type is that it is not a `byte[]`. A cache benchmark that only ever moves flat
 * arrays flatters every arm equally and tells you nothing about what happens when a service caches
 * something with structure: an object graph has to be walked to serialize it, allocates many small
 * objects when deserialized, and behaves differently under GC than one humongous array.
 *
 * <p>Arms that store live references (Caffeine, embedded FastCache) hold this object as-is and pay no
 * codec cost. Arms that cross a process boundary (FastCache sidecar, Redis) encode and decode it with
 * {@link Codec}, identically. That asymmetry is architectural, not a benchmark artefact, and the codec
 * cost is measured standalone so a reader can subtract it.
 */
public record LargeResponse(
        String documentId,
        String title,
        long generatedAtMillis,
        Map<String, String> metadata,
        List<Section> sections,
        byte[] payload) {

    /** One nested element: strings, numerics and a small body, repeated enough to be worth walking. */
    public record Section(int index, String heading, String body, double score, long revision) { }

    public static final int SECTION_COUNT = 40;

    /**
     * Representation A: bulk payload plus the minimum identifying header a cache entry needs to
     * round-trip and be verified. No sections, no metadata.
     *
     * <p>Not literally a bare {@code byte[]} - the value still has to carry enough to prove on read-back
     * that the right entry came out - but everything that costs a codec anything is gone. Comparing it
     * against {@link #of} isolates what the object graph costs, separately from what the bytes cost.
     */
    public static LargeResponse flat(String documentId, int payloadBytes, int seed) {
        return new LargeResponse(documentId, "", 0L, Map.of(), List.of(),
                Payloads.of(payloadBytes, seed));
    }

    /**
     * Builds a response whose bulk is {@code payloadBytes} and whose structure is fixed.
     *
     * <p>Deterministic in {@code seed} so the same document id always produces the same bytes, which is
     * what makes read-back verification possible.
     */
    public static LargeResponse of(String documentId, int payloadBytes, int seed) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("tenant", "tenant-" + (seed % 17));
        metadata.put("contentType", "application/octet-stream");
        metadata.put("source", "document-service");
        metadata.put("checksumSeed", Integer.toString(seed));

        List<Section> sections = new ArrayList<>(SECTION_COUNT);
        for (int i = 0; i < SECTION_COUNT; i++) {
            sections.add(new Section(
                    i,
                    "Section " + i + " of document " + documentId,
                    "Body text for section " + i + ", document " + documentId
                            + ", which exists so the codec has real strings to walk rather than a single "
                            + "flat array. Repeated content is fine; the cost is in the traversal.",
                    (seed % 1000) / 1000.0 + i,
                    1000L + i));
        }

        return new LargeResponse(documentId, "Document " + documentId, 1758326400000L,
                metadata, sections, Payloads.of(payloadBytes, seed));
    }

    /**
     * Cheap identity check for read-back verification, without a deep comparison on a hot path.
     *
     * <p>The section count is checked against whatever this value actually carries rather than against
     * {@link #SECTION_COUNT}, so the same check works for both representations. A truncated or swapped
     * payload still fails on length and seed.
     */
    public boolean matches(String expectedId, int expectedPayloadBytes, int expectedSeed) {
        return verify(expectedId, expectedPayloadBytes, expectedSeed, SKIP_DIGEST) == null;
    }

    /** Passed as {@code expectedDigest} when only the cheap identity checks are wanted. */
    public static final long SKIP_DIGEST = Long.MIN_VALUE;

    /**
     * The same check, but says what failed, and optionally checks content rather than only length.
     *
     * <p>Counting corrupt reads and carrying on is what let a cell transport stale 1 MiB payloads under
     * 10 MiB labels through a whole run: the requests succeeded, the hit ratio read 1.0, and the
     * corruption was only visible in a column nobody reads until afterwards. A caller that can name the
     * mismatch can abort on it instead.
     *
     * <p>{@code expectedDigest} must be <b>precomputed</b> by the caller, once per (size, seed), and
     * passed in. Deriving it here would mean allocating a payload-sized array on every request purely to
     * check the one that just arrived — doubling the allocation this experiment exists to measure.
     *
     * @param expectedDigest {@link Payloads#digest} of the canonical payload, or {@link #SKIP_DIGEST}
     * @return null when the value is exactly what was asked for, otherwise a one-line reason
     */
    public String verify(String expectedId, int expectedPayloadBytes, int expectedSeed,
                         long expectedDigest) {
        if (!documentId.equals(expectedId)) {
            return "id expected=" + expectedId + " actual=" + documentId;
        }
        if (payload == null) {
            return "payload null for " + expectedId;
        }
        if (payload.length != expectedPayloadBytes) {
            return "length expected=" + expectedPayloadBytes + " actual=" + payload.length
                    + " for " + expectedId;
        }
        int seed = Payloads.seedOf(payload);
        if (seed != expectedSeed) {
            return "seed expected=" + expectedSeed + " actual=" + seed + " for " + expectedId;
        }
        if (expectedDigest != SKIP_DIGEST) {
            long actual = Payloads.digest(payload);
            if (actual != expectedDigest) {
                return "digest expected=" + expectedDigest + " actual=" + actual
                        + " for " + expectedId;
            }
        }
        return null;
    }

    /**
     * Forces the whole payload to be read.
     *
     * <p>A service that fetches 25 MB and never touches it lets the JIT elide work a real service cannot.
     * Called on every request so the measurement includes actually consuming the value.
     */
    public long consume() {
        long sum = 0;
        for (int i = 0; i < payload.length; i += 4096) {
            sum += payload[i];
        }
        // Empty for Representation A, so the structural walk is charged only where structure exists.
        for (Section section : sections) {
            sum += section.heading().length() + section.body().length();
        }
        return sum;
    }
}
