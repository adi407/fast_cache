package io.fastcache.engine.core;

import io.fastcache.engine.util.FastCacheLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Background reclamation, entirely on virtual threads.
 *
 * <p>One supervisor virtual thread wakes on a fixed cadence and fans the work out across a
 * virtual-thread-per-task executor, one task per shard, so a 32-shard sweep runs 32-way parallel without
 * reserving 32 platform threads. Each pass does three things, in order:
 *
 * <ol>
 *   <li><b>TTL purge</b> &mdash; drop everything past its expiry. This is a backstop; the read path already
 *       expires lazily, so a sweep that falls behind never causes a stale read, only delayed reclamation.</li>
 *   <li><b>Capacity LRU</b> &mdash; any shard over {@code maxEntriesPerShard} sheds its coldest entries.</li>
 *   <li><b>Pressure LRU</b> &mdash; if the memory guard is rejecting writes, every shard sheds a slice of
 *       its coldest entries so the engine actively digs itself out instead of staying wedged until TTLs
 *       happen to elapse.</li>
 * </ol>
 *
 * <p><b>Why this must not be a platform thread pool.</b> Reclamation of a 50&nbsp;MB slot is an unmap
 * syscall. On a platform-thread scheduler a burst of those blocks real OS threads that request handling
 * needs. On virtual threads, a sweeper that parks costs nothing but a continuation.
 *
 * <p><b>Why no {@code synchronized} anywhere in this path.</b> The sweeper acquires each shard's
 * {@link java.util.concurrent.locks.StampedLock}. A virtual thread that parks on a {@code StampedLock}
 * releases its carrier; one that parks inside a monitor pins it, and a pinned carrier during a
 * multi-second sweep is exactly the stall this engine exists to avoid.
 */
final class EvictionSweeper {

    private static final FastCacheLog LOG = FastCacheLog.of(EvictionSweeper.class);

    /** Fraction of a shard shed per pass while the engine is under memory pressure. */
    private static final double PRESSURE_EVICTION_FRACTION = 0.10d;

    private final ShardedStorageEngine engine;
    private final EngineConfig config;
    private final AtomicBoolean running = new AtomicBoolean();
    private final LongAdder passes = new LongAdder();
    private final LongAdder reclaimed = new LongAdder();

    private volatile Thread supervisor;
    private volatile ExecutorService fanOut;

    EvictionSweeper(ShardedStorageEngine engine, EngineConfig config) {
        this.engine = engine;
        this.config = config;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        this.fanOut = Executors.newVirtualThreadPerTaskExecutor();
        this.supervisor = Thread.ofVirtual()
                .name("fastcache-sweeper")
                .start(this::loop);
    }

    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread current = supervisor;
        if (current != null) {
            current.interrupt();
        }
        ExecutorService executor = fanOut;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warn("Eviction fan-out did not drain within 5s; continuing shutdown.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("Eviction sweeper stopped after {0} passes, {1} entries reclaimed.",
                passes.sum(), reclaimed.sum());
    }

    private void loop() {
        while (running.get()) {
            try {
                Thread.sleep(config.sweepIntervalMillis());
                if (!running.get()) {
                    return;
                }
                runPass();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // Normal shutdown path.
            } catch (Throwable t) {
                // A sweeper that dies leaks the whole cache. Absorb everything and try again next tick.
                LOG.error("Eviction pass failed; sweeper continuing", t instanceof Exception e
                        ? e : new RuntimeException(t));
            }
        }
    }

    private void runPass() throws InterruptedException {
        long now = System.currentTimeMillis();
        boolean underPressure = engine.memoryGuard().isRejecting();
        ExecutorService executor = fanOut;
        if (executor == null || executor.isShutdown()) {
            return;
        }

        List<Future<Integer>> futures = new ArrayList<>(engine.shards().length);
        for (Shard shard : engine.shards()) {
            futures.add(executor.submit(() -> sweepShard(shard, now, underPressure)));
        }

        int total = 0;
        for (Future<Integer> future : futures) {
            try {
                total += future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                LOG.warn("Shard sweep task failed: {0}", e.toString());
            }
        }
        passes.increment();
        reclaimed.add(total);
        if (total > 0 && underPressure) {
            LOG.info("Pressure sweep reclaimed {0} entries.", total);
        }
    }

    private int sweepShard(Shard shard, long now, boolean underPressure) {
        int total = shard.purgeExpired(now);

        if (config.lruEnabled()) {
            int overflow = shard.size() - config.maxEntriesPerShard();
            if (overflow > 0) {
                total += shard.evictLeastRecentlyUsed(overflow);
            }
            if (underPressure) {
                int slice = Math.max(1, (int) (shard.size() * PRESSURE_EVICTION_FRACTION));
                total += shard.evictLeastRecentlyUsed(slice);
            }
        }
        return total;
    }
}
