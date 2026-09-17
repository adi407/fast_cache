package io.fastcache.engine.core;

/**
 * Callback a shard invokes when it drops its ownership reference on a payload. Implemented by
 * {@link ShardedStorageEngine}, which owns the allocator and the memory guard; keeping it as a functional
 * interface lets {@link Shard} be unit-tested without a real native allocator.
 */
@FunctionalInterface
public interface PayloadReleaser {

    /**
     * Drops one reference. Frees the underlying native slot and returns its bytes to the budget if this was
     * the final reference. Implementations must never throw &mdash; they run inside eviction sweeps.
     */
    void release(CachePayload payload);
}
