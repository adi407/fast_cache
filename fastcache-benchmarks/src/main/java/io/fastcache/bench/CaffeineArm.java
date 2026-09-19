package io.fastcache.bench;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy;

import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

/**
 * Caffeine baseline, configured to be as strong as the comparison allows.
 *
 * <p>Fairness notes, since an artificially weakened baseline would invalidate everything:
 *
 * <ul>
 *   <li><b>Byte-accurate bound.</b> {@code maximumWeight} with a weigher returning the payload length,
 *       so Caffeine is bounded by the same number of bytes as FastCache's off-heap budget. This is
 *       Caffeine at its best for large values, and is a capability FastCache has no equivalent for.
 *   <li><b>Per-entry TTL.</b> Caffeine's {@code expireAfter} variant is used rather than the simpler
 *       {@code expireAfterWrite}, so that per-key TTLs match FastCache's semantics instead of forcing one
 *       global TTL.
 *   <li><b>No recording of stats unless asked</b>, so the baseline is not taxed with bookkeeping
 *       FastCache does not also do. FastCache's counters are always on, so stats recording is enabled
 *       here too &mdash; the tax is symmetric.
 * </ul>
 */
public final class CaffeineArm implements CacheArm {

    private final Cache<String, byte[]> cache;
    private final LongAdder rejections = new LongAdder();

    public CaffeineArm(long maxWeightBytes) {
        this.cache = Caffeine.newBuilder()
                .maximumWeight(maxWeightBytes)
                .weigher((String key, byte[] value) -> value.length)
                .expireAfter(new com.github.benmanes.caffeine.cache.Expiry<String, byte[]>() {
                    @Override
                    public long expireAfterCreate(String key, byte[] value, long currentTime) {
                        return ttlNanosFor(key);
                    }

                    @Override
                    public long expireAfterUpdate(String key, byte[] value, long currentTime,
                                                  long currentDuration) {
                        return ttlNanosFor(key);
                    }

                    @Override
                    public long expireAfterRead(String key, byte[] value, long currentTime,
                                                long currentDuration) {
                        return currentDuration;   // read does not extend the TTL, matching FastCache
                    }
                })
                .recordStats()
                .build();
    }

    /**
     * Per-key TTL carrier.
     *
     * <p>Caffeine's {@code Expiry} callbacks do not receive the TTL the caller passed to {@code put}, so
     * it is handed across in a thread local set immediately before the write. Ugly, but it is the only way
     * to give Caffeine genuine per-entry TTLs, and giving it anything less would be the kind of
     * handicapping the fairness rules forbid.
     */
    private static final ThreadLocal<Long> PENDING_TTL_NANOS = ThreadLocal.withInitial(() -> Long.MAX_VALUE);

    private static long ttlNanosFor(String key) {
        return PENDING_TTL_NANOS.get();
    }

    @Override
    public String name() {
        return "caffeine";
    }

    @Override
    public boolean put(String key, byte[] value, long ttlMillis) {
        PENDING_TTL_NANOS.set(ttlMillis <= 0 ? Long.MAX_VALUE : Duration.ofMillis(ttlMillis).toNanos());
        try {
            cache.put(key, value);
            return true;
        } finally {
            PENDING_TTL_NANOS.set(Long.MAX_VALUE);
        }
    }

    @Override
    public byte[] get(String key) {
        return cache.getIfPresent(key);
    }

    @Override
    public void remove(String key) {
        cache.invalidate(key);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
        cache.cleanUp();
    }

    @Override
    public long reportedBytes() {
        return cache.policy().eviction().map(Policy.Eviction::weightedSize)
                .flatMap(optional -> optional.stream().boxed().findFirst()).orElse(-1L);
    }

    @Override
    public long offHeapSlots() {
        return -1;
    }

    @Override
    public long offHeapReserved() {
        return -1;
    }

    @Override
    public long entries() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    @Override
    public long writeRejections() {
        return rejections.sum();
    }

    @Override
    public boolean storesOffHeap() {
        return false;
    }

    @Override
    public void close() {
        cache.invalidateAll();
    }
}
