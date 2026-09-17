package io.fastcache.engine.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core storage behaviour, including the invariant that matters most: native memory is never leaked.
 *
 * <p>An off-heap cache that leaks slots is worse than no cache, because the leak is invisible to every
 * JVM-level tool — the heap stays small while RSS climbs until the OS kills the process. Several of these
 * tests exist purely to assert that the reference count reaches zero on every path.
 */
class ShardedStorageEngineTest {

    private ShardedStorageEngine engine;

    private ShardedStorageEngine open(EngineConfig config) {
        engine = new ShardedStorageEngine(config);
        return engine;
    }

    private static EngineConfig.Builder testConfig() {
        return EngineConfig.builder()
                .shardCount(32)
                .maxOffHeapBytes(256L * 1024 * 1024)
                .maxEntriesPerShard(10_000)
                .sweepIntervalMillis(200);
    }

    @AfterEach
    void closeEngine() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    @Test
    @DisplayName("round-trips a payload through an off-heap slot")
    void roundTrip() {
        ShardedStorageEngine cache = open(testConfig().build());
        byte[] value = "a context window".getBytes(StandardCharsets.UTF_8);

        assertEquals(WriteStatus.ACCEPTED, cache.put("k", value, (byte) 7, 60_000));
        try (Lease lease = cache.acquire("k")) {
            assertNotNull(lease);
            assertEquals(7, lease.flags(), "client codec flags must survive the round trip verbatim");
            assertArrayEqualsBytes(value, lease.toByteArray());
        }
    }

    @Test
    @DisplayName("stores a multi-megabyte payload without touching the heap")
    void largePayload() {
        ShardedStorageEngine cache = open(testConfig().build());
        byte[] value = new byte[5 * 1024 * 1024];
        Arrays.fill(value, (byte) 'x');

        assertEquals(WriteStatus.ACCEPTED, cache.put("ctx", value, (byte) 0, 60_000));
        try (Lease lease = cache.acquire("ctx")) {
            assertEquals(value.length, lease.length());
            assertEquals('x', lease.readOnlyView().get(0));
        }
    }

    @Test
    @DisplayName("rejects a payload above maxValueBytes with a status, not an exception")
    void oversizedIsRejected() {
        ShardedStorageEngine cache = open(testConfig().maxValueBytes(1024).build());

        assertEquals(WriteStatus.REJECTED_TOO_LARGE,
                cache.put("big", new byte[2048], (byte) 0, 60_000));
        assertNull(cache.acquire("big"));
        // The engine must remain fully usable after a rejection.
        assertEquals(WriteStatus.ACCEPTED, cache.put("small", new byte[16], (byte) 0, 60_000));
    }

    @Test
    @DisplayName("stores live object references without serialization")
    void referencePathStoresTheSameInstance() {
        ShardedStorageEngine cache = open(testConfig().build());
        List<Integer> value = List.of(1, 2, 3);

        assertEquals(WriteStatus.ACCEPTED, cache.putReference("obj", value, 60_000));
        assertTrue(value == cache.getReference("obj"),
                "the in-process path must hand back the identical instance, not a copy");
    }

    @Test
    @DisplayName("an entry is fresh, then stale-servable, then gone")
    void ttlHasThreePhases() throws InterruptedException {
        ShardedStorageEngine cache = open(testConfig().staleGraceMillis(600).build());
        cache.put("k", "v".getBytes(StandardCharsets.UTF_8), (byte) 0, 200);

        try (Lease fresh = cache.acquire("k")) {
            assertNotNull(fresh);
            assertFalse(fresh.isStale(), "within its TTL the entry is fresh");
        }

        Thread.sleep(350);
        try (Lease stale = cache.acquire("k")) {
            assertNotNull(stale, "inside the grace window the entry is still served");
            assertTrue(stale.isStale(), "and is flagged stale so someone refreshes it");
        }

        Thread.sleep(600);
        assertNull(cache.acquire("k"), "past TTL + grace the entry is gone");
    }

    @Test
    @DisplayName("a zero grace window restores strict TTL semantics")
    void zeroGraceExpiresImmediately() throws InterruptedException {
        ShardedStorageEngine cache = open(testConfig().staleGraceMillis(0).build());
        cache.put("k", "v".getBytes(StandardCharsets.UTF_8), (byte) 0, 100);

        Thread.sleep(250);
        assertNull(cache.acquire("k"));
    }

    @Test
    @DisplayName("delete removes the entry and releases its slot")
    void deleteReleases() {
        ShardedStorageEngine cache = open(testConfig().build());
        cache.put("k", new byte[4096], (byte) 0, 60_000);
        long reservedWithEntry = cache.memoryGuard().reservedBytes();

        assertTrue(cache.delete("k"));
        assertNull(cache.acquire("k"));
        assertFalse(cache.delete("k"), "deleting an absent key reports false");
        assertTrue(cache.memoryGuard().reservedBytes() < reservedWithEntry,
                "the native reservation must be refunded on delete");
    }

    @Test
    @DisplayName("a reader holding a lease survives concurrent eviction of its key")
    void leaseOutlivesEviction() {
        ShardedStorageEngine cache = open(testConfig().build());
        cache.put("k", "payload".getBytes(StandardCharsets.UTF_8), (byte) 0, 60_000);

        try (Lease lease = cache.acquire("k")) {
            assertNotNull(lease);
            cache.delete("k"); // Drops the map's ownership reference while a reader still holds one.
            // Reading here would be a SIGSEGV if the slot had actually been unmapped.
            assertEquals("payload", new String(lease.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("5,000 concurrent virtual threads leave no native slot behind")
    void concurrentLoadLeaksNothing() throws InterruptedException {
        ShardedStorageEngine cache = open(testConfig().maxEntriesPerShard(64).build());
        int tasks = 5_000;
        CountDownLatch done = new CountDownLatch(tasks);
        AtomicInteger failures = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                final int n = i;
                pool.execute(() -> {
                    try {
                        String key = "k:" + (n % 4000);
                        cache.put(key, ("v" + n).getBytes(StandardCharsets.UTF_8), (byte) 0, 5_000);
                        try (Lease lease = cache.acquire(key)) {
                            if (lease != null) {
                                lease.toByteArray();
                            }
                        }
                        cache.delete("k:" + (n % 97));
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(120, TimeUnit.SECONDS), "concurrent load did not finish in time");
        }

        assertEquals(0, failures.get(), "no operation may fail under concurrency");
        Thread.sleep(600); // Let the sweeper enforce the per-shard cap.

        cache.close();
        assertEquals(0, cache.stats().liveOffHeapSlots(),
                "every native slot must be released once the engine closes");
        engine = null; // Already closed; stop the @AfterEach double-closing it.
    }

    @Test
    @DisplayName("flush drops everything and refunds the whole budget")
    void flushReleasesEverything() {
        ShardedStorageEngine cache = open(testConfig().build());
        for (int i = 0; i < 500; i++) {
            cache.put("k" + i, new byte[1024], (byte) 0, 60_000);
        }
        assertTrue(cache.memoryGuard().reservedBytes() > 0);

        assertEquals(500, cache.flush());
        assertEquals(0, cache.memoryGuard().reservedBytes(), "flush must refund every reserved byte");
        assertEquals(0, cache.stats().entries());
    }

    @Test
    @DisplayName("stats report hits, misses and stale hits separately")
    void statsShape() throws InterruptedException {
        ShardedStorageEngine cache = open(testConfig().staleGraceMillis(600).build());
        cache.put("k", "v".getBytes(StandardCharsets.UTF_8), (byte) 0, 200);

        cache.acquire("k").close();          // fresh hit
        assertNull(cache.acquire("absent")); // miss
        Thread.sleep(350);
        cache.acquire("k").close();          // stale hit

        EngineStats stats = cache.stats();
        assertEquals(2, stats.hits(), "a stale serve still counts as a hit");
        assertEquals(1, stats.staleHits());
        assertEquals(1, stats.misses());
        assertTrue(stats.toWireString().contains("stale_hits=1"));
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual),
                () -> "expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual));
    }
}
