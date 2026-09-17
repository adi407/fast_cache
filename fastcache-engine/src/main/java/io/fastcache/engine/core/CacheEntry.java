package io.fastcache.engine.core;

import io.fastcache.engine.util.TimeSpec;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The immutable unit of storage: a Java record holding the off-heap slot handle plus its lifecycle
 * metadata.
 *
 * <p><b>Why a record.</b> Structural compaction. A record with five components has no synthetic accessor
 * fields, no builder state and no mutable bookkeeping beyond the two atomics below; at 32 shards holding
 * millions of entries the per-entry header savings versus a conventional POJO with setters is measured in
 * hundreds of megabytes of heap. Immutability also means an entry can be published into the shard map with
 * a single {@code putIfAbsent} and read by any number of virtual threads without a lock.
 *
 * <p><b>The two atomics.</b> {@code lastAccessMillis} and {@code hits} are mutable <em>contents</em> behind
 * immutable <em>references</em>. LRU needs a recency stamp updated on the read path, and allocating a fresh
 * record per read to keep the entry literally immutable would defeat the purpose of an allocation-light
 * cache. The record itself is still structurally final.
 *
 * <p><b>Identity equality.</b> {@code equals}/{@code hashCode} are overridden to identity. The generated
 * record equality would compare {@code CachePayload.OffHeap} component-wise, and {@code ByteBuffer.equals}
 * is a byte-by-byte content comparison &mdash; a 50&nbsp;MB {@code memcmp} on every
 * {@code map.remove(key, entry)} during an eviction sweep. Identity is both correct (two distinct writes
 * are distinct entries) and O(1).
 */
public record CacheEntry(
        String key,
        CachePayload payload,
        long createdAtMillis,
        long expiresAtMillis,
        long staleUntilMillis,
        AtomicLong lastAccessMillis,
        AtomicLong hits) {

    public CacheEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(lastAccessMillis, "lastAccessMillis");
        Objects.requireNonNull(hits, "hits");
    }

    /**
     * @param ttlMillis positive TTL, or {@link TimeSpec#NEVER} for a non-expiring entry
     */
    public static CacheEntry create(String key, CachePayload payload, long ttlMillis, long nowMillis) {
        return create(key, payload, ttlMillis, 0L, nowMillis);
    }

    /**
     * @param ttlMillis        positive TTL, or {@link TimeSpec#NEVER} for a non-expiring entry
     * @param staleGraceMillis how long past expiry this entry may still be served while one caller
     *                         refreshes it (stale-while-revalidate). Zero restores strict TTL semantics.
     */
    public static CacheEntry create(String key, CachePayload payload, long ttlMillis,
                                    long staleGraceMillis, long nowMillis) {
        boolean eternal = ttlMillis == TimeSpec.NEVER || ttlMillis <= 0;
        long expiry = eternal ? Long.MAX_VALUE : saturatingAdd(nowMillis, ttlMillis);
        long staleUntil = eternal ? Long.MAX_VALUE : saturatingAdd(expiry, Math.max(0L, staleGraceMillis));
        return new CacheEntry(key, payload, nowMillis, expiry, staleUntil,
                new AtomicLong(nowMillis), new AtomicLong());
    }

    /** True once the TTL has elapsed. An expired entry may still be {@link #isServable} during grace. */
    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    /**
     * True while this entry may still be handed to a reader &mdash; either fresh, or expired but inside the
     * stale-while-revalidate grace window.
     *
     * <p>This is the mechanism that defuses a thundering herd. When a 50&nbsp;MB context window expires
     * under a thousand concurrent readers, the alternative is a thousand simultaneous misses hitting the
     * LLM pipeline at once. Instead the entry stays servable for the grace window: one caller wins the
     * refresh lease and recomputes, and the other 999 are handed the slightly-stale value immediately
     * rather than queueing behind a cold recompute.
     */
    public boolean isServable(long nowMillis) {
        return nowMillis < staleUntilMillis;
    }

    /** True when the TTL has elapsed but the entry is still inside its grace window. */
    public boolean isStale(long nowMillis) {
        return nowMillis >= expiresAtMillis && nowMillis < staleUntilMillis;
    }

    public boolean neverExpires() {
        return expiresAtMillis == Long.MAX_VALUE;
    }

    /** Remaining lifetime in millis, clamped at zero; {@link TimeSpec#NEVER} for eternal entries. */
    public long remainingTtlMillis(long nowMillis) {
        if (neverExpires()) {
            return TimeSpec.NEVER;
        }
        return Math.max(0L, expiresAtMillis - nowMillis);
    }

    /**
     * Records a read for LRU ordering. Uses a plain {@code lazySet} because recency is a heuristic: losing
     * a racing update costs an entry a few milliseconds of apparent freshness, and paying for a full
     * volatile store on every cache hit is not worth that.
     */
    public void touch(long nowMillis) {
        lastAccessMillis.lazySet(nowMillis);
        hits.incrementAndGet();
    }

    public long lastAccess() {
        return lastAccessMillis.get();
    }

    public long hitCount() {
        return hits.get();
    }

    public int footprintBytes() {
        return payload.footprintBytes();
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "CacheEntry[key=" + key + ", bytes=" + footprintBytes()
                + ", expiresAt=" + (neverExpires() ? "never" : expiresAtMillis) + ']';
    }
}
