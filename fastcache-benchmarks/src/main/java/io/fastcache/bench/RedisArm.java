package io.fastcache.bench;

import java.util.concurrent.atomic.LongAdder;

/**
 * Redis as a fourth arm, driven through the same scenario harness as the other three.
 *
 * <p>This is the comparison that decides whether FastCache's cross-process capability is worth anything:
 * Caffeine cannot share a cache between processes at all, but Redis can, and has been able to for fifteen
 * years. If Redis matches or beats the sidecar here, FastCache's one remaining technical differentiator
 * does not differentiate.
 *
 * <p>Fairness notes, all of which run against FastCache's interest:
 *
 * <ul>
 *   <li><b>Persistence is off</b> ({@code save ""}, {@code appendonly no}). FastCache is non-durable, so
 *       charging Redis for RDB snapshots would compare a cache against a database.
 *   <li><b>{@code maxmemory-policy allkeys-lru}</b>, so Redis is a bounded LRU cache like the others,
 *       rather than an unbounded store.
 *   <li><b>The client is minimal and symmetric</b> with {@link WireClient} - same socket options, same
 *       64 KiB buffers, same pooling. See {@link RespClient} for why this is not Jedis or Lettuce.
 *   <li><b>Redis runs on the same host over loopback</b>, exactly as the FastCache sidecar does. No
 *       container, no VM, no virtual NIC between them.
 * </ul>
 *
 * <p>The server is assumed to be already running; this arm does not start or stop it, so a failure to
 * connect is reported as a failure rather than silently skipping the comparison.
 */
public final class RedisArm implements CacheArm {

    private final RespClient client;
    private final long serverPid;
    private final LongAdder rejections = new LongAdder();

    public RedisArm(String host, int port, long serverPid) {
        this.client = new RespClient(host, port);
        this.serverPid = serverPid;
        // Fail loudly at construction if the server is not reachable, rather than producing a table of
        // zeroes that looks like a measurement.
        if (client.dbSize() < 0) {
            throw new IllegalStateException("cannot reach redis at " + host + ":" + port);
        }
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public boolean put(String key, byte[] value, long ttlMillis) {
        boolean ok = client.set(key, value, ttlMillis);
        if (!ok) {
            rejections.increment();
        }
        return ok;
    }

    @Override
    public byte[] get(String key) {
        return client.get(key);
    }

    @Override
    public void remove(String key) {
        client.del(key);
    }

    @Override
    public void clear() {
        client.flushAll();
    }

    @Override
    public long reportedBytes() {
        return client.info("memory").getOrDefault("used_memory", -1L);
    }

    @Override
    public long offHeapSlots() {
        return -1;   // Not a slot-based allocator; jemalloc is not exposed this way.
    }

    @Override
    public long offHeapReserved() {
        return client.info("memory").getOrDefault("used_memory", -1L);
    }

    @Override
    public long entries() {
        return client.dbSize();
    }

    @Override
    public long writeRejections() {
        return rejections.sum();
    }

    @Override
    public boolean storesOffHeap() {
        // True in the sense that matters here: the payload is not on this JVM's heap. It is in another
        // process's native memory, exactly as with the FastCache sidecar.
        return true;
    }

    @Override
    public long externalRssBytes() {
        return serverPid > 0 ? Probe.rssBytes(serverPid) : -1;
    }

    @Override
    public void close() {
        client.close();
    }
}
