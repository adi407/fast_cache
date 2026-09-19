package io.fastcache.bench;

import io.fastcache.engine.core.EngineConfig;
import io.fastcache.engine.core.Lease;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.core.WriteStatus;

import java.util.concurrent.atomic.LongAdder;

/**
 * FastCache as a Spring application actually embeds it: in-process, through {@code putReference}.
 *
 * <p><b>This arm stores values on the Java heap.</b> That is not a misconfiguration of the benchmark — it
 * is what {@code FastCacheAdapter}, {@code FastCacheAspect} and {@code FastCacheOperations} all do. See
 * {@code docs/benchmarks/OFFHEAP_MEMORY_MODEL.md} §1. Including this arm is the only way to measure what
 * a Spring team would actually get, as distinct from what the architecture document describes.
 *
 * <p>Two consequences shape the numbers and are called out in the results rather than smoothed over:
 * {@code CachePayload.Reference.footprintBytes()} returns 0, so this arm reports no byte usage and is
 * bounded only by the per-shard entry ceiling; and {@code putReference} deliberately never consults
 * {@code MemoryGuard}, so this arm cannot shed writes.
 */
public final class FastCacheEmbeddedArm implements CacheArm {

    private final ShardedStorageEngine engine;
    private final LongAdder rejections = new LongAdder();

    public FastCacheEmbeddedArm(long budgetBytes, int maxEntriesPerShard) {
        this.engine = new ShardedStorageEngine(EngineConfig.builder()
                .maxOffHeapBytes(budgetBytes)
                .maxEntriesPerShard(maxEntriesPerShard)
                .maxValueBytes(Integer.MAX_VALUE - 8)
                .staleGraceMillis(0)
                .build());
    }

    public ShardedStorageEngine engine() {
        return engine;
    }

    @Override
    public String name() {
        return "fastcache-embedded";
    }

    @Override
    public boolean put(String key, byte[] value, long ttlMillis) {
        WriteStatus status = engine.putReference(key, value, ttlMillis);
        if (status != WriteStatus.ACCEPTED) {
            rejections.increment();
            return false;
        }
        return true;
    }

    @Override
    public byte[] get(String key) {
        try (Lease lease = engine.acquire(key)) {
            return lease == null ? null : (byte[]) lease.reference();
        }
    }

    @Override
    public void remove(String key) {
        engine.delete(key);
    }

    @Override
    public void clear() {
        engine.flush();
    }

    @Override
    public long reportedBytes() {
        return engine.stats().bytes();
    }

    @Override
    public long offHeapSlots() {
        return engine.stats().liveOffHeapSlots();
    }

    @Override
    public long offHeapReserved() {
        return engine.stats().memory().reservedBytes();
    }

    @Override
    public long entries() {
        return engine.stats().entries();
    }

    @Override
    public long writeRejections() {
        return rejections.sum();
    }

    @Override
    public boolean storesOffHeap() {
        return false;   // putReference parks the caller's object on the heap. See the class Javadoc.
    }

    @Override
    public void close() {
        engine.close();
    }
}
