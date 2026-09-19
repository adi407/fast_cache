package io.fastcache.bench;

/**
 * One thing being benchmarked. Three arms exist because FastCache has two storage paths, not one:
 *
 * <ul>
 *   <li>{@code caffeine} &mdash; on-heap, in-process. The baseline.
 *   <li>{@code fastcache-embedded} &mdash; on-heap, in-process, via {@code putReference}. This is what the
 *       Spring starter actually uses; see {@code docs/benchmarks/OFFHEAP_MEMORY_MODEL.md}.
 *   <li>{@code fastcache-sidecar} &mdash; off-heap, in a second JVM, over the wire protocol. The only
 *       configuration in which the off-heap thesis can be true.
 * </ul>
 *
 * <p>Every arm stores and returns {@code byte[]}, so no arm pays a serialization cost the others avoid.
 * That is generous to FastCache's sidecar arm (a real object graph would need encoding) and is stated as
 * a limitation in the results rather than quietly assumed away.
 */
public interface CacheArm extends AutoCloseable {

    String name();

    /** @return false when the write was refused (memory guard, capacity, size limit) */
    boolean put(String key, byte[] value, long ttlMillis);

    /** @return the value, or null on a miss */
    byte[] get(String key);

    void remove(String key);

    void clear();

    /** Bytes this arm believes it is holding, or -1 when the arm cannot tell. */
    long reportedBytes();

    /** Live native slots, or -1 for arms with no off-heap storage. */
    long offHeapSlots();

    /** Off-heap bytes reserved, or -1 for arms with no off-heap storage. */
    long offHeapReserved();

    long entries();

    /** Writes refused since start, or -1 when the arm does not track refusals. */
    long writeRejections();

    /** True when payloads live outside this JVM's heap. Used to label results honestly. */
    boolean storesOffHeap();

    /**
     * RSS of a helper process this arm owns, or -1 when the arm has none.
     *
     * <p>Without this the sidecar arm looks as though the memory disappeared. It did not: it moved to
     * another process, and a benchmark that does not say so is not measuring, it is advertising.
     */
    default long externalRssBytes() {
        return -1;
    }

    @Override
    void close();
}
