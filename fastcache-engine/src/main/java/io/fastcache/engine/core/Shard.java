package io.fastcache.engine.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.StampedLock;

/**
 * One vertical slice of the database: an isolated storage unit with its own map, its own lock and its own
 * counters. Nothing in this class reaches across to a sibling shard, which is what makes 32 of them scale
 * linearly with core count.
 *
 * <h2>Concurrency contract</h2>
 * <ul>
 *   <li><b>Point operations</b> ({@code get}/{@code put}/{@code remove}) rely on the per-bin atomicity of
 *       {@link ConcurrentHashMap}. They take no shard lock at all.</li>
 *   <li><b>Bulk maintenance</b> (expiry sweep, LRU eviction, flush) takes the {@link StampedLock} write
 *       stamp. Its job is to serialise <em>maintenance against maintenance</em>: two evictors racing on the
 *       same shard would double-count capacity and evict twice as much as intended. Point operations
 *       deliberately remain lock-free during a sweep.</li>
 * </ul>
 *
 * <p><b>Why {@link StampedLock} and not {@code synchronized}.</b> Every path here may execute on a virtual
 * thread. A virtual thread that blocks inside a {@code synchronized} block pins its carrier thread for the
 * duration, and with maintenance sweeps touching millions of entries that would starve the carrier pool.
 * {@code StampedLock} parks the virtual thread properly, releasing the carrier.
 *
 * <p><b>On {@code ConcurrentHashMap}'s internal monitors.</b> CHM does take a monitor on a bin during
 * {@code compute}/{@code putIfAbsent}. That is safe here because FastCache never performs a blocking
 * operation inside a remapping function &mdash; the lambdas below only swap references and adjust counters,
 * so a virtual thread can never park while holding a bin monitor.
 */
public final class Shard {

    private final int index;
    private final ConcurrentHashMap<String, CacheEntry> map;
    private final StampedLock maintenanceLock = new StampedLock();
    private final PayloadReleaser releaser;
    private final int maxEntries;

    private final LongAdder hits = new LongAdder();
    private final LongAdder staleHits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder writes = new LongAdder();
    private final LongAdder ttlEvictions = new LongAdder();
    private final LongAdder lruEvictions = new LongAdder();
    private final LongAdder bytes = new LongAdder();

    public Shard(int index, int maxEntries, PayloadReleaser releaser) {
        this.index = index;
        this.maxEntries = maxEntries;
        this.releaser = releaser;
        // Sized to avoid the first few resizes on a warm cache; concurrency level tracks a virtual-thread
        // world where "number of writers" is effectively unbounded.
        this.map = new ConcurrentHashMap<>(1024, 0.75f, 64);
    }

    public int index() {
        return index;
    }

    public int size() {
        return map.size();
    }

    public long byteFootprint() {
        return bytes.sum();
    }

    public boolean overCapacity() {
        return map.size() > maxEntries;
    }

    /**
     * Stores an entry, replacing and releasing any predecessor.
     *
     * @return the displaced entry, or {@code null}
     */
    public CacheEntry put(CacheEntry entry) {
        CacheEntry previous = map.put(entry.key(), entry);
        writes.increment();
        bytes.add(entry.footprintBytes());
        if (previous != null) {
            bytes.add(-previous.footprintBytes());
            releaser.release(previous.payload()); // Drops the map's ownership reference, not any live lease.
        }
        return previous;
    }

    /**
     * Looks up a live entry and takes a reader reference on its payload.
     *
     * @return an entry whose payload is retained (the caller must release it), or {@code null} on miss,
     *         expiry, or a lost race with an evictor
     */
    public CacheEntry acquire(String key, long nowMillis) {
        CacheEntry entry = map.get(key);
        if (entry == null) {
            misses.increment();
            return null;
        }
        if (!entry.isServable(nowMillis)) {
            // Past expiry *and* past the grace window. Lazy reclamation on the read path: the sweeper is a
            // backstop, not the primary mechanism, so a dead entry is never served even if a sweep is late.
            removeExact(key, entry, ttlEvictions);
            misses.increment();
            return null;
        }
        if (!entry.payload().tryRetain()) {
            misses.increment(); // Lost the race with a concurrent eviction; treat as a miss.
            return null;
        }
        entry.touch(nowMillis);
        hits.increment();
        if (entry.isExpired(nowMillis)) {
            // Served from the stale-while-revalidate window. Still a hit — the caller got a value without
            // touching the backend — but tracked separately so the grace window's real usage is visible.
            staleHits.increment();
        }
        return entry;
    }

    /** Non-retaining peek used by maintenance and stats. Never touches recency. */
    public CacheEntry peek(String key) {
        return map.get(key);
    }

    public boolean remove(String key) {
        CacheEntry removed = map.remove(key);
        if (removed == null) {
            return false;
        }
        bytes.add(-removed.footprintBytes());
        releaser.release(removed.payload());
        return true;
    }

    /**
     * Removes {@code expected} only if it is still the mapped instance. Uses {@code computeIfPresent} with
     * an identity check rather than {@code remove(key, value)}: the latter would invoke value equality, and
     * a naive record equality on an off-heap payload is a full-buffer comparison.
     */
    private boolean removeExact(String key, CacheEntry expected, LongAdder counter) {
        final boolean[] removed = {false};
        map.computeIfPresent(key, (k, current) -> {
            if (current != expected) {
                return current; // Someone re-wrote this key; leave the newer entry alone.
            }
            removed[0] = true;
            return null;
        });
        if (removed[0]) {
            bytes.add(-expected.footprintBytes());
            counter.increment();
            releaser.release(expected.payload());
        }
        return removed[0];
    }

    /**
     * Drops every entry whose TTL has elapsed.
     *
     * @return number of entries reclaimed
     */
    public int purgeExpired(long nowMillis) {
        long stamp = maintenanceLock.writeLock();
        try {
            int reclaimed = 0;
            // Iterating a CHM is weakly consistent by design: entries added mid-sweep are simply picked up
            // on the next pass. That is exactly the semantics a TTL sweeper wants.
            for (CacheEntry entry : map.values()) {
                // isServable, not isExpired: an entry inside its grace window is still being handed to
                // readers while one refresher recomputes it. Reclaiming it here would re-open the
                // thundering herd the grace window exists to close.
                if (!entry.isServable(nowMillis) && removeExact(entry.key(), entry, ttlEvictions)) {
                    reclaimed++;
                }
            }
            return reclaimed;
        } finally {
            maintenanceLock.unlockWrite(stamp);
        }
    }

    /**
     * Evicts the {@code count} least-recently-accessed entries.
     *
     * <p>Selection uses a bounded max-heap rather than sorting the whole shard: at 100k entries per shard a
     * full sort per sweep would dominate the maintenance budget. Cost is O(n log k) with k = count.
     *
     * @return number of entries reclaimed
     */
    public int evictLeastRecentlyUsed(int count) {
        if (count <= 0) {
            return 0;
        }
        long stamp = maintenanceLock.writeLock();
        try {
            PriorityQueue<CacheEntry> coldest =
                    new PriorityQueue<>(count, Comparator.comparingLong(CacheEntry::lastAccess).reversed());
            for (CacheEntry entry : map.values()) {
                if (coldest.size() < count) {
                    coldest.offer(entry);
                } else if (!coldest.isEmpty() && entry.lastAccess() < coldest.peek().lastAccess()) {
                    coldest.poll();
                    coldest.offer(entry);
                }
            }
            int reclaimed = 0;
            for (CacheEntry victim : coldest) {
                if (removeExact(victim.key(), victim, lruEvictions)) {
                    reclaimed++;
                }
            }
            return reclaimed;
        } finally {
            maintenanceLock.unlockWrite(stamp);
        }
    }

    /** Drops every entry, releasing all native slots. */
    public int clear() {
        long stamp = maintenanceLock.writeLock();
        try {
            List<CacheEntry> victims = new ArrayList<>(map.values());
            int reclaimed = 0;
            for (CacheEntry victim : victims) {
                if (removeExact(victim.key(), victim, lruEvictions)) {
                    reclaimed++;
                }
            }
            return reclaimed;
        } finally {
            maintenanceLock.unlockWrite(stamp);
        }
    }

    public ShardStats stats() {
        return new ShardStats(index, map.size(), bytes.sum(), hits.sum(), staleHits.sum(), misses.sum(),
                writes.sum(), ttlEvictions.sum(), lruEvictions.sum());
    }
}
