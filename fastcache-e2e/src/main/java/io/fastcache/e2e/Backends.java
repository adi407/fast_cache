package io.fastcache.e2e;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.fastcache.bench.RespClient;
import io.fastcache.bench.WireClient;
import io.fastcache.engine.core.EngineConfig;
import io.fastcache.engine.core.Lease;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.core.WriteStatus;

import java.util.concurrent.atomic.LongAdder;

/**
 * The four cache backends, behind one interface so the service layer above them is byte-identical.
 *
 * <p>The important structural difference is visible in the return types of the private paths:
 *
 * <ul>
 *   <li><b>Caffeine and embedded FastCache</b> hand back the <em>same object instance</em> that was
 *       stored. Nothing is copied, nothing is decoded, and the cached graph stays on the application
 *       heap for as long as the cache holds it.
 *   <li><b>The FastCache sidecar and Redis</b> hand back a freshly allocated {@code byte[]} which the
 *       service must decode. The cached representation is off the application heap; the value the
 *       application actually uses is not.
 * </ul>
 *
 * <p>That difference is the entire subject of this experiment, so it is expressed here rather than
 * hidden behind a uniform façade.
 */
public final class Backends {

    private Backends() {
    }

    /** What the service layer sees. Implementations differ only in where the bytes come from. */
    public interface Backend extends AutoCloseable {

        String name();

        /**
         * Bytes as they came out of the cache, for backends that cross a process boundary.
         *
         * <p>Separate from {@link #getReference} so the service can time transport and decoding
         * independently. Collapsing them would hide whether the sidecar spends its transport advantage
         * on decoding what it transported.
         *
         * @return encoded bytes, or null on a miss
         * @throws UnsupportedOperationException on an in-process backend
         */
        default byte[] getEncoded(String key) {
            throw new UnsupportedOperationException(name() + " does not return encoded bytes");
        }

        /**
         * The live object, for in-process backends that never encoded it.
         *
         * @throws UnsupportedOperationException on a cross-process backend
         */
        default LargeResponse getReference(String key) {
            throw new UnsupportedOperationException(name() + " does not hold live references");
        }

        void put(String key, LargeResponse value);

        /**
         * Empties the cache.
         *
         * <p>Required because the cross-process arms share one long-lived server across cells while only
         * the application restarts. Without this, a cell configured for 10 MB payloads reads back the
         * 1 MB entries the previous cell left behind: the requests succeed, the hit ratio reads 1.0, and
         * every measurement is of the wrong payload size. Observed exactly once, caught by the
         * payload-length check (55,941 hits flagged corrupt), and fixed here rather than by remembering
         * to flush by hand.
         */
        void clear();

        /**
         * Load-on-miss using whatever coalescing this backend provides on its own.
         *
         * <p>The default is no coalescing: load per caller. That is the honest behaviour for a backend
         * that has none, and it is what the cross-process arms get, because {@code OP_REFRESH_LEASE} has
         * no Java client.
         */
        default LargeResponse readThrough(String key, java.util.function.Supplier<LargeResponse> loader) {
            LargeResponse existing = decodesOnHit()
                    ? decodeOrNull(getEncoded(key))
                    : getReference(key);
            if (existing != null) {
                return existing;
            }
            LargeResponse loaded = loader.get();
            put(key, loaded);
            return loaded;
        }

        private static LargeResponse decodeOrNull(byte[] encoded) {
            return encoded == null ? null : Codec.decode(encoded);
        }

        /** True when the cached representation lives outside this JVM's heap. */
        boolean cachesOffHeap();

        /** True when a hit requires decoding a byte[] into an object graph. */
        boolean decodesOnHit();

        long entries();

        long writeRejections();

        /** RSS of a helper process this backend owns, or -1. Counted so memory is not made to vanish. */
        default long externalRssBytes() {
            return -1;
        }

        @Override
        void close();
    }

    // ---------------------------------------------------------------------------------------------------

    /** Arm A. Live object references on the heap, byte-bounded by a weigher. */
    public static final class CaffeineBackend implements Backend {

        private final com.github.benmanes.caffeine.cache.Cache<String, LargeResponse> cache;

        public CaffeineBackend(long maxWeightBytes) {
            this.cache = Caffeine.newBuilder()
                    .maximumWeight(maxWeightBytes)
                    // Weigh by the bulk payload: it is what the bound is actually for, and it is the one
                    // component whose size the benchmark controls.
                    .weigher((String key, LargeResponse value) -> value.payload().length)
                    .build();
        }

        @Override public String name() { return "caffeine"; }
        @Override public LargeResponse getReference(String key) { return cache.getIfPresent(key); }
        @Override public void put(String key, LargeResponse value) { cache.put(key, value); }
        @Override public boolean cachesOffHeap() { return false; }
        @Override public boolean decodesOnHit() { return false; }
        @Override public long entries() { cache.cleanUp(); return cache.estimatedSize(); }
        @Override public long writeRejections() { return 0; }

        /** Caffeine collapses concurrent loads for one key inside {@code get(key, mappingFunction)}. */
        @Override
        public LargeResponse readThrough(String key,
                                         java.util.function.Supplier<LargeResponse> loader) {
            return cache.get(key, k -> loader.get());
        }

        @Override public void clear() { cache.invalidateAll(); cache.cleanUp(); }
        @Override public void close() { cache.invalidateAll(); }
    }

    // ---------------------------------------------------------------------------------------------------

    /**
     * Arm B. In-process FastCache through {@code putReference} — which is what the Spring starter uses.
     *
     * <p>This arm stores the object graph on the Java heap. That is not a misconfiguration: every Spring
     * entry point in the product calls {@code putReference}, whose own Javadoc says it parks the caller's
     * object graph in the shard map with a zero off-heap footprint.
     */
    public static final class FastCacheEmbeddedBackend implements Backend {

        private final ShardedStorageEngine engine;
        private final LongAdder rejections = new LongAdder();

        public FastCacheEmbeddedBackend(long budgetBytes, int maxEntriesPerShard) {
            this.engine = new ShardedStorageEngine(EngineConfig.builder()
                    .maxOffHeapBytes(budgetBytes)
                    .maxEntriesPerShard(maxEntriesPerShard)
                    .maxValueBytes(Integer.MAX_VALUE - 8)
                    .staleGraceMillis(0)
                    .build());
        }

        @Override public String name() { return "fastcache-embedded"; }

        @Override
        public LargeResponse getReference(String key) {
            try (Lease lease = engine.acquire(key)) {
                return lease == null ? null : (LargeResponse) lease.reference();
            }
        }

        @Override
        public void put(String key, LargeResponse value) {
            if (engine.putReference(key, value, 600_000) != WriteStatus.ACCEPTED) {
                rejections.increment();
            }
        }

        @Override public boolean cachesOffHeap() { return false; }
        @Override public boolean decodesOnHit() { return false; }
        @Override public long entries() { return engine.stats().entries(); }
        @Override public long writeRejections() { return rejections.sum(); }

        /**
         * The engine's own read-through, which elects a single refresher through
         * {@code RefreshCoordinator} and parks the rest.
         */
        @Override
        public LargeResponse readThrough(String key,
                                         java.util.function.Supplier<LargeResponse> loader) {
            return engine.readThrough(key, 600_000, loader::get);
        }

        @Override public void clear() { engine.flush(); }
        @Override public void close() { engine.close(); }
    }

    // ---------------------------------------------------------------------------------------------------

    /**
     * Arm C. FastCache sidecar: off-heap at rest, {@code byte[]} on every hit.
     *
     * <p>The second half of that sentence is the hypothesis under test. {@link WireClient#get} allocates a
     * payload-sized array per call, and {@link Codec#decode} then builds the object graph from it, so a
     * hit costs one full-size heap allocation plus a decode — every time.
     */
    public static final class FastCacheSidecarBackend implements Backend {

        private final WireClient client;
        private final long sidecarPid;
        private final LongAdder rejections = new LongAdder();

        public FastCacheSidecarBackend(String host, int port, long sidecarPid) {
            this.client = new WireClient(host, port);
            this.sidecarPid = sidecarPid;
        }

        @Override public String name() { return "fastcache-sidecar"; }

        @Override
        public byte[] getEncoded(String key) {
            return client.get(key);
        }

        @Override
        public void put(String key, LargeResponse value) {
            if (!client.put(key, Codec.encode(value), 600_000)) {
                rejections.increment();
            }
        }

        @Override public boolean cachesOffHeap() { return true; }
        @Override public boolean decodesOnHit() { return true; }
        @Override public long entries() { return client.stats().getOrDefault("entries", -1L); }

        @Override
        public long writeRejections() {
            return client.stats().getOrDefault("write_rejections", rejections.sum());
        }

        @Override
        public long externalRssBytes() {
            return io.fastcache.bench.Probe.rssBytes(sidecarPid);
        }

        @Override public void clear() { client.flush(); }
        @Override public void close() { client.close(); }
    }

    // ---------------------------------------------------------------------------------------------------

    /** Arm D. Redis: the same shape as arm C — off the application heap at rest, byte[] on every hit. */
    public static final class RedisBackend implements Backend {

        private final RespClient client;
        private final long serverPid;
        private final LongAdder rejections = new LongAdder();

        public RedisBackend(String host, int port, long serverPid) {
            this.client = new RespClient(host, port);
            this.serverPid = serverPid;
            if (client.dbSize() < 0) {
                throw new IllegalStateException("cannot reach redis at " + host + ":" + port);
            }
        }

        @Override public String name() { return "redis"; }

        @Override
        public byte[] getEncoded(String key) {
            return client.get(key);
        }

        @Override
        public void put(String key, LargeResponse value) {
            if (!client.set(key, Codec.encode(value), 600_000)) {
                rejections.increment();
            }
        }

        @Override public boolean cachesOffHeap() { return true; }
        @Override public boolean decodesOnHit() { return true; }
        @Override public long entries() { return client.dbSize(); }
        @Override public long writeRejections() { return rejections.sum(); }

        @Override
        public long externalRssBytes() {
            return serverPid > 0 ? io.fastcache.bench.Probe.rssBytes(serverPid) : -1;
        }

        @Override public void clear() { client.flushAll(); }
        @Override public void close() { client.close(); }
    }
}
