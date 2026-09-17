package io.fastcache.spring;

import io.fastcache.engine.core.EngineStats;
import io.fastcache.engine.core.Lease;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.core.WriteStatus;
import io.fastcache.engine.util.TimeSpec;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Programmatic facade for the cases annotations cannot express: caching inside a loop, caching a value
 * computed from several methods, or invalidating on a write path.
 *
 * <p>Inject it like any bean:
 *
 * <pre>{@code
 * private final FastCacheOperations cache;
 *
 * public Embedding embed(String prompt) {
 *     return cache.computeIfAbsent("embed:" + prompt, "15m", () -> model.embed(prompt));
 * }
 * }</pre>
 */
public class FastCacheOperations {

    private final ShardedStorageEngine engine;
    private final FastCacheProperties properties;

    public FastCacheOperations(ShardedStorageEngine engine, FastCacheProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    /** @return the cached value, or empty on a miss */
    public <T> Optional<T> get(String key, Class<T> type) {
        try (Lease lease = engine.acquire(key)) {
            if (lease == null) {
                return Optional.empty();
            }
            Object value = lease.reference();
            return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty(); // Fail open, exactly as the aspect does.
        }
    }

    public WriteStatus put(String key, Object value) {
        return put(key, value, properties.getDefaultTtl());
    }

    public WriteStatus put(String key, Object value, String ttlSpec) {
        Objects.requireNonNull(key, "key");
        return engine.putReference(key, value, TimeSpec.parseMillis(ttlSpec));
    }

    /**
     * Read-through helper.
     *
     * <p><b>No single-flight.</b> Concurrent misses on the same key all invoke {@code supplier}; the last
     * write wins. Adding per-key locking here would put a lock acquisition on the hot path of every call
     * to protect against a stampede that, for an in-process cache with a cheap supplier, costs less than
     * the lock itself. When the supplier is genuinely expensive and the key is genuinely hot, coordinate
     * outside the cache &mdash; that is the same trade-off Caffeine makes between {@code get(k, fn)} and
     * {@code getIfPresent}.
     */
    public <T> T computeIfAbsent(String key, String ttlSpec, Supplier<T> supplier) {
        @SuppressWarnings("unchecked")
        Optional<T> cached = (Optional<T>) get(key, Object.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        T value = supplier.get();
        if (value != null) {
            put(key, value, ttlSpec);
        }
        return value;
    }

    public boolean evict(String key) {
        return engine.delete(key);
    }

    public long clear() {
        return engine.flush();
    }

    /** Live engine telemetry: hit ratio, per-shard breakdown, memory pressure, eviction counts. */
    public EngineStats stats() {
        return engine.stats();
    }

    /** The shard a key routes to. Useful when diagnosing a hot-shard imbalance. */
    public int shardOf(String key) {
        return engine.shardIndexFor(key);
    }
}
