package io.fastcache.engine.core;

import io.fastcache.engine.memory.MemoryGuard;
import io.fastcache.engine.memory.MemoryPressure;
import io.fastcache.engine.memory.OffHeapAllocator;
import io.fastcache.engine.metrics.LatencyHistogram;
import io.fastcache.engine.metrics.SavingsLedger;
import io.fastcache.engine.util.FastCacheLog;
import io.fastcache.engine.util.TimeSpec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The database. An array of {@link Shard}s fronted by a hash router, wired to the off-heap allocator and
 * the memory guard, with a background eviction sweeper running on a virtual thread.
 *
 * <h2>Vertical sharding</h2>
 * A key routes to exactly one shard via {@code Math.abs(fmix32(key.hashCode()) % shardCount)} — the
 * murmur3 32-bit finalizer applied before the modulo. See {@link #shardIndexFor(String)} for the measured
 * skew table and why the raw {@code String.hashCode} placement was retired as the default.
 *
 * <h2>Stampede defence</h2>
 * Entries stay servable for {@code staleGraceMillis} past their TTL. When a hot key expires, exactly one
 * caller wins a refresh lease from the {@link RefreshCoordinator} and recomputes; every other caller is
 * handed the slightly-stale value immediately, or parks on the leader for at most the grace window. See
 * {@link #readThrough(String, long, java.util.function.Supplier)}.
 *
 * <h2>Off-heap write path</h2>
 * The socket server does not build a {@code byte[]} and hand it over. It calls {@link #beginWrite(int)} to
 * reserve budget and obtain a native slot, reads the payload from the socket <em>directly into that
 * slot</em>, then calls {@link #commitWrite}. A 50&nbsp;MB context window therefore never touches the Java
 * heap at any point between the network card and the cache.
 */
public final class ShardedStorageEngine implements PayloadReleaser, AutoCloseable {

    private static final FastCacheLog LOG = FastCacheLog.of(ShardedStorageEngine.class);

    private final EngineConfig config;
    private final Shard[] shards;
    private final OffHeapAllocator allocator;
    private final MemoryGuard memoryGuard;
    private final EvictionSweeper sweeper;
    private final RefreshCoordinator refreshCoordinator;
    private final SavingsLedger savingsLedger = new SavingsLedger();

    // Server-side operation latency. One nanoTime pair per call (~25ns) plus an allocation-free bucket
    // increment: cheap enough to leave on permanently, which matters because latency you only measure
    // when you suspect a problem is latency you find out about too late.
    private final LatencyHistogram getLatency = new LatencyHistogram("get");
    private final LatencyHistogram putLatency = new LatencyHistogram("put");
    private final LatencyHistogram deleteLatency = new LatencyHistogram("delete");
    private final AtomicBoolean closed = new AtomicBoolean();

    public ShardedStorageEngine() {
        this(EngineConfig.defaults());
    }

    public ShardedStorageEngine(EngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.allocator = new OffHeapAllocator();
        this.memoryGuard = new MemoryGuard(config.maxOffHeapBytes(), config.memoryRejectRatio());
        this.shards = new Shard[config.shardCount()];
        for (int i = 0; i < shards.length; i++) {
            shards[i] = new Shard(i, config.maxEntriesPerShard(), this);
        }
        this.refreshCoordinator = new RefreshCoordinator(config.refreshLeaseMillis());
        this.sweeper = new EvictionSweeper(this, config);
        this.sweeper.start();
        LOG.info("FastCache engine online: {0} shards, {1} MiB off-heap budget, reject at {2}%, "
                        + "deterministic free={3}",
                config.shardCount(), config.maxOffHeapBytes() / (1024 * 1024),
                Math.round(config.memoryRejectRatio() * 100), OffHeapAllocator.supportsDeterministicFree());
    }

    // ---------------------------------------------------------------------------------------------------
    // Routing
    // ---------------------------------------------------------------------------------------------------

    /**
     * The sharding function. Kept public so tests and tooling can reason about placement.
     *
     * <p><b>Murmur3 finalizer is the default.</b> {@code String.hashCode} avalanches poorly, so keys that
     * differ only in a numeric suffix cluster badly under a plain modulo. Routing therefore runs the hash
     * through the murmur3 32-bit finalizer ({@code fmix32}) first. Measured over 2,000 keys across 32
     * shards (max shard / mean shard, 1.00 = perfect):
     *
     * <pre>
     *   key pattern       raw hashCode   murmur3 (default)
     *   "spread:N"             2.29              1.22
     *   "prompt-N-v2"          2.43              1.41
     *   "session:N"            1.62              1.34
     *   "user_N_ctx"           1.52              1.18
     * </pre>
     *
     * Setting {@code fastcache.hash-spreading=false} restores the raw {@code key.hashCode()} placement,
     * which exists only to reproduce a legacy key layout &mdash; there is no performance reason to choose
     * it, since {@code fmix32} is four multiply/shift ops on an already-computed hash.
     *
     * <p>Note that the cheaper {@code h ^ (h >>> 16)} spread used by {@code ConcurrentHashMap} does
     * <em>not</em> work here: measured at 2.26 for the first pattern, i.e. no better than doing nothing.
     * A single xor-shift does not avalanche a structured {@code String.hashCode}; the full finalizer does.
     *
     * <p>The {@code Math.abs} is safe even for {@code Integer.MIN_VALUE}: the remainder is taken first and
     * {@code x % n} always lands in {@code (-n, n)}, so its absolute value cannot overflow. Writing
     * {@code Math.abs(key.hashCode()) % shardCount} instead would be a genuine bug.
     */
    public int shardIndexFor(String key) {
        int hash = config.hashSpreading() ? fmix32(key.hashCode()) : key.hashCode();
        return Math.abs(hash % shards.length);
    }

    /** murmur3 32-bit finalizer: four multiply/shift steps that fully avalanche the input bits. */
    private static int fmix32(int hash) {
        hash ^= hash >>> 16;
        hash *= 0x85ebca6b;
        hash ^= hash >>> 13;
        hash *= 0xc2b2ae35;
        hash ^= hash >>> 16;
        return hash;
    }

    Shard shardFor(String key) {
        return shards[shardIndexFor(key)];
    }

    Shard[] shards() {
        return shards;
    }

    public EngineConfig config() {
        return config;
    }

    public MemoryGuard memoryGuard() {
        return memoryGuard;
    }

    /** Single-flight leader election, exposed so the socket layer can broker it across processes. */
    public RefreshCoordinator refreshCoordinator() {
        return refreshCoordinator;
    }

    /** Token and dollar accounting, surfaced by the metrics console. */
    public SavingsLedger savingsLedger() {
        return savingsLedger;
    }

    /** Read-path latency, in the engine itself — excludes socket transfer. */
    public LatencyHistogram getLatency() {
        return getLatency;
    }

    /** Write-path latency, measured from admission control through publication. */
    public LatencyHistogram putLatency() {
        return putLatency;
    }

    public LatencyHistogram deleteLatency() {
        return deleteLatency;
    }

    // ---------------------------------------------------------------------------------------------------
    // Zero-copy write path (used by the socket server)
    // ---------------------------------------------------------------------------------------------------

    /**
     * A reserved-but-not-yet-published native slot. Exactly one of {@link #commitWrite} or
     * {@link #abortWrite} must be called for every successful {@code beginWrite}, or the reservation leaks.
     */
    public record WriteTicket(ByteBuffer slot, int capacity) {
    }

    /**
     * Reserves budget and allocates a native slot of {@code length} bytes.
     *
     * @return a ticket, or {@code null} when the write must be rejected. Callers inspect
     *         {@link #classifyRejection(int)} for the precise status code.
     */
    public WriteTicket beginWrite(int length) {
        if (closed.get() || length < 0 || length > config.maxValueBytes()) {
            return null;
        }
        if (!memoryGuard.tryReserve(length)) {
            return null;
        }
        try {
            return new WriteTicket(allocator.allocate(length), length);
        } catch (OutOfMemoryError | RuntimeException e) {
            // Admission control passed but the OS refused the mapping: hand the budget back and reject
            // cleanly. This is the last line of defence before a hard OOM.
            memoryGuard.release(length);
            LOG.warn("Native allocation of {0} bytes failed despite admission control: {1}", length, e.toString());
            return null;
        }
    }

    /** Explains why {@link #beginWrite(int)} returned null, for status reporting. */
    public WriteStatus classifyRejection(int length) {
        if (closed.get()) {
            return WriteStatus.REJECTED_SHUTDOWN;
        }
        if (length > config.maxValueBytes()) {
            return WriteStatus.REJECTED_TOO_LARGE;
        }
        return WriteStatus.REJECTED_MEMORY_PRESSURE;
    }

    /**
     * Publishes a filled ticket into its shard. The slot's bytes must already be written; position and
     * limit are ignored (the recorded length is authoritative).
     */
    public WriteStatus commitWrite(String key, WriteTicket ticket, byte flags, long ttlMillis) {
        return commitWrite(key, ticket, flags, ttlMillis, 0);
    }

    /**
     * @param sourceCharacters pre-compression character count of the original text, or 0 when the value is
     *                         not text. Used only for token/cost accounting, never for storage decisions.
     */
    public WriteStatus commitWrite(String key, WriteTicket ticket, byte flags, long ttlMillis,
                                   int sourceCharacters) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ticket, "ticket");
        if (closed.get()) {
            abortWrite(ticket);
            return WriteStatus.REJECTED_SHUTDOWN;
        }
        long started = System.nanoTime();
        try {
            CachePayload payload =
                    CachePayload.OffHeap.owned(ticket.slot(), ticket.capacity(), flags, sourceCharacters);
            CacheEntry entry = CacheEntry.create(key, payload, effectiveTtl(ttlMillis),
                    config.staleGraceMillis(), System.currentTimeMillis());
            shardFor(key).put(entry);
            savingsLedger.recordStore(sourceCharacters);
            return WriteStatus.ACCEPTED;
        } finally {
            putLatency.record(System.nanoTime() - started);
        }
    }

    /** Discards an unpublished ticket, freeing the slot and returning its budget. */
    public void abortWrite(WriteTicket ticket) {
        if (ticket == null) {
            return;
        }
        allocator.free(ticket.slot(), ticket.capacity());
        memoryGuard.release(ticket.capacity());
    }

    // ---------------------------------------------------------------------------------------------------
    // Convenience write paths
    // ---------------------------------------------------------------------------------------------------

    /** Copies a heap array into an off-heap slot. Used by tests and by in-process byte-oriented callers. */
    public WriteStatus put(String key, byte[] value, byte flags, long ttlMillis) {
        Objects.requireNonNull(value, "value");
        WriteTicket ticket = beginWrite(value.length);
        if (ticket == null) {
            return classifyRejection(value.length);
        }
        try {
            ticket.slot().put(0, value);
        } catch (RuntimeException e) {
            abortWrite(ticket);
            LOG.error("Failed to stage payload for key " + key, e);
            return WriteStatus.REJECTED_ALLOCATION_FAILED;
        }
        return commitWrite(key, ticket, flags, ttlMillis);
    }

    /**
     * Stores a <em>live JVM object reference</em>. This is the JVM-native path used by the Spring aspect:
     * no serialization, no copy, no off-heap accounting &mdash; the caller's object graph is simply parked
     * in the shard map.
     *
     * <p><b>This path deliberately does not consult {@link MemoryGuard}.</b> The guard protects the
     * <em>native</em> budget and watches machine-wide physical memory, and neither is a valid signal for an
     * on-heap reference: storing one allocates no native memory and adds no pages the OS did not already
     * have. Gating it on physical memory produces a silent, near-undebuggable failure &mdash; on any
     * ordinarily busy host (a developer laptop at 86% RAM, a well-packed container) every write would be
     * refused, the cache would report a healthy engine with a 0% hit rate, and every {@code @FastCache}
     * method would quietly run uncached forever.
     *
     * <p>On-heap entries are instead bounded where they actually consume resources: the per-shard entry
     * ceiling with LRU eviction, plus the garbage collector. This is the same bound Spring's own
     * {@code ConcurrentMapCache} and Caffeine's bounded caches use.
     */
    public WriteStatus putReference(String key, Object value, long ttlMillis) {
        Objects.requireNonNull(key, "key");
        if (closed.get()) {
            return WriteStatus.REJECTED_SHUTDOWN;
        }
        long started = System.nanoTime();
        try {
            CachePayload payload = CachePayload.Reference.of(value);
            CacheEntry entry = CacheEntry.create(key, payload, effectiveTtl(ttlMillis),
                    config.staleGraceMillis(), System.currentTimeMillis());
            shardFor(key).put(entry);
            savingsLedger.recordStore(payload.sourceCharacters());
            return WriteStatus.ACCEPTED;
        } finally {
            putLatency.record(System.nanoTime() - started);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Read path
    // ---------------------------------------------------------------------------------------------------

    /**
     * Acquires a reference-counted lease on a key.
     *
     * @return a lease the caller must close, or {@code null} on miss
     */
    public Lease acquire(String key) {
        if (key == null || closed.get()) {
            return null;
        }
        long started = System.nanoTime();
        try {
            CacheEntry entry = shardFor(key).acquire(key, System.currentTimeMillis());
            if (entry == null) {
                return null;
            }
            // One choke point for cost accounting: every read path in both runtimes funnels through here.
            savingsLedger.recordServe(entry.payload().sourceCharacters());
            return new Lease(entry, this);
        } finally {
            // Misses are timed too. A cache whose misses are slow is a cache that has a problem, and
            // timing only hits would hide exactly that.
            getLatency.record(System.nanoTime() - started);
        }
    }

    /**
     * Reads a live object reference. No lease needed: on-heap values are kept alive by the returned
     * reference itself.
     *
     * @return the cached object, or {@code null} on miss
     */
    public Object getReference(String key) {
        try (Lease lease = acquire(key)) {
            return lease == null ? null : lease.reference();
        }
    }

    /**
     * Stampede-safe read-through for in-process (reference) values.
     *
     * <p>This is the method that turns a thundering herd into a single backend call. Under a thousand
     * concurrent readers of one expiring key:
     * <ol>
     *   <li>A fresh hit returns immediately — the overwhelmingly common path, with no coordination cost.</li>
     *   <li>On expiry, exactly one caller wins the refresh lease and invokes {@code loader}.</li>
     *   <li>Callers that find a <em>stale but servable</em> entry return it at once. They never block, and
     *       they never call the backend. This is what keeps p99 flat through a refresh.</li>
     *   <li>Callers that find nothing at all (cold key, or grace already elapsed) park on the leader for at
     *       most the grace window, then re-read.</li>
     *   <li>If the leader fails or overruns, the waiter computes the value itself rather than failing. A
     *       stampede defence that can deadlock a request path is worse than the stampede.</li>
     * </ol>
     *
     * @param loader the expensive computation — an LLM call, an embedding, a database query
     */
    public <T> T readThrough(String key, long ttlMillis, java.util.function.Supplier<T> loader) {
        Objects.requireNonNull(loader, "loader");
        Lease lease = acquire(key);
        try {
            if (lease != null && !lease.isStale()) {
                @SuppressWarnings("unchecked")
                T fresh = (T) lease.reference();
                return fresh;
            }

            if (refreshCoordinator.tryAcquireLead(key)) {
                try {
                    T value = loader.get();
                    putReference(key, value, ttlMillis);
                    return value;
                } finally {
                    // Finally, not after the return: an exception from the loader must still release the
                    // lease, or every other caller waits out the full lease period for nothing.
                    refreshCoordinator.complete(key);
                }
            }

            if (lease != null) {
                @SuppressWarnings("unchecked")
                T stale = (T) lease.reference(); // Serve stale rather than queue behind the refresher.
                return stale;
            }
        } finally {
            if (lease != null) {
                lease.close();
            }
        }

        // Cold miss while another caller refreshes: park on them rather than piling onto the backend.
        refreshCoordinator.awaitCompletion(key, Math.max(1L, config.staleGraceMillis()));
        try (Lease published = acquire(key)) {
            if (published != null) {
                @SuppressWarnings("unchecked")
                T value = (T) published.reference();
                return value;
            }
        }
        // The leader failed or overran its lease. Compute locally without storing: storing here would let
        // a slow straggler overwrite whatever the (possibly recovered) leader just published.
        return loader.get();
    }

    /** Copies a value onto the heap. Prefer {@link #acquire(String)} for large payloads. */
    public byte[] get(String key) {
        try (Lease lease = acquire(key)) {
            return lease == null ? null : lease.toByteArray();
        }
    }

    public boolean delete(String key) {
        if (key == null) {
            return false;
        }
        long started = System.nanoTime();
        try {
            return shardFor(key).remove(key);
        } finally {
            deleteLatency.record(System.nanoTime() - started);
        }
    }

    public long flush() {
        long reclaimed = 0;
        for (Shard shard : shards) {
            reclaimed += shard.clear();
        }
        LOG.info("FastCache flushed {0} entries.", reclaimed);
        return reclaimed;
    }

    // ---------------------------------------------------------------------------------------------------
    // PayloadReleaser
    // ---------------------------------------------------------------------------------------------------

    /**
     * Drops one reference and, if it was the last, unmaps the native slot and refunds the budget. Never
     * throws: this runs inside eviction sweeps and inside {@code Lease.close()} within finally blocks.
     */
    @Override
    public void release(CachePayload payload) {
        try {
            if (!payload.releaseReference()) {
                return;
            }
            if (payload instanceof CachePayload.OffHeap offHeap) {
                allocator.free(offHeap.buffer(), offHeap.length());
                memoryGuard.release(offHeap.length());
            }
        } catch (RuntimeException e) {
            LOG.error("Payload release failed; this leaks native memory", e);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Observability & lifecycle
    // ---------------------------------------------------------------------------------------------------

    public EngineStats stats() {
        List<ShardStats> perShard = new ArrayList<>(shards.length);
        long entries = 0, bytes = 0, hits = 0, staleHits = 0, misses = 0, writes = 0, ttl = 0, lru = 0;
        for (Shard shard : shards) {
            ShardStats s = shard.stats();
            perShard.add(s);
            entries += s.entries();
            bytes += s.bytes();
            hits += s.hits();
            staleHits += s.staleHits();
            misses += s.misses();
            writes += s.writes();
            ttl += s.ttlEvictions();
            lru += s.lruEvictions();
        }
        MemoryPressure pressure = memoryGuard.snapshot();
        return new EngineStats(shards.length, entries, bytes, hits, staleHits, misses, writes, ttl, lru,
                memoryGuard.rejectionCount(), allocator.liveSlots(),
                refreshCoordinator.leadsGranted(), refreshCoordinator.herdSuppressed(),
                refreshCoordinator.inFlightCount(), pressure, perShard);
    }

    public boolean isClosed() {
        return closed.get();
    }

    private long effectiveTtl(long requested) {
        if (requested == TimeSpec.NEVER) {
            return TimeSpec.NEVER;
        }
        return requested > 0 ? requested : config.defaultTtlMillis();
    }

    /**
     * Stops the sweeper and releases every native slot. Idempotent.
     *
     * <p>Ordering matters: {@code closed} is set first so no new writes can allocate, then the sweeper is
     * stopped so it cannot race the final flush, then the slots are freed.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sweeper.stop();
        refreshCoordinator.clear(); // Wake anyone parked on a refresh before we tear the shards down.
        long reclaimed = flush();
        LOG.info("FastCache engine closed: {0} entries released, {1} native slots outstanding.",
                reclaimed, allocator.liveSlots());
    }
}
