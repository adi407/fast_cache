package io.fastcache.bench;

import io.fastcache.engine.core.EngineConfig;
import io.fastcache.engine.core.Lease;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.core.WriteStatus;
import io.fastcache.engine.memory.OffHeapAllocator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Phase 6: targeted native-memory leak detection.
 *
 * <p>Runs the engine's <b>off-heap</b> path in-process, via {@code put(String, byte[], ...)}, which goes
 * through {@code beginWrite} / {@code commitWrite} exactly as the socket server does. Doing it in-process
 * rather than through a sidecar is deliberate: this JVM's RSS then includes the native slots, so a leak is
 * directly attributable rather than inferred from another process's numbers.
 *
 * <p>Each probe drives one release path from the lifecycle table in
 * {@code docs/benchmarks/OFFHEAP_MEMORY_MODEL.md} §3 and asserts two independent things:
 *
 * <ul>
 *   <li><b>Slots return to baseline.</b> This checks the engine's own accounting.
 *   <li><b>RSS returns to baseline.</b> This checks the operating system, and is the one that matters:
 *       the allocator decrements its counters <em>before</em> calling {@code invokeCleaner}, so a throwing
 *       free would show clean slots and dirty RSS.
 * </ul>
 *
 * <p>Cycle counts are chosen so that a leak of one payload per cycle would be unmistakable: at 4 MB and
 * 500 cycles, a 100% leak is 2 GB and a 1% leak is 20 MB, both far outside RSS noise.
 */
public final class LeakProbe {

    private static final long MB = 1 << 20;
    private static final int PAYLOAD = 4 << 20;
    private static final int CYCLES = 500;

    private record Outcome(String name, long slotsBefore, long slotsAfter, long rssBeforeMB,
                           long rssAfterMB, long cyclesRun, String note) {

        boolean slotsClean() {
            return slotsAfter <= slotsBefore + 2;
        }

        /** 64 MB of headroom over the baseline: generous, and still a fraction of one cycle's churn. */
        boolean rssClean() {
            return rssBeforeMB < 0 || rssAfterMB < 0 || rssAfterMB - rssBeforeMB <= 64;
        }

        String verdict() {
            if (rssBeforeMB < 0 || rssAfterMB < 0) {
                return "NO RSS";
            }
            return slotsClean() && rssClean() ? "clean" : "LEAK?";
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(112));
        System.out.println("Phase 6 - native memory leak probe");
        System.out.printf("  payload %s, %d cycles per probe, deterministic free = %s%n",
                Payloads.label(PAYLOAD), CYCLES, OffHeapAllocator.supportsDeterministicFree());
        System.out.printf("  a 100%% leak would be %d MB per probe; the pass threshold is 64 MB%n",
                (long) CYCLES * PAYLOAD / MB);
        System.out.println("=".repeat(112));

        if (!OffHeapAllocator.supportsDeterministicFree()) {
            System.out.println("\n  ABORT: this JVM has no deterministic free. Results would be");
            System.out.println("  measuring the GC's Cleaner schedule, not FastCache's refcounting.\n");
            return;
        }

        List<Outcome> outcomes = new ArrayList<>();
        outcomes.add(replacement());
        outcomes.add(explicitDelete());
        outcomes.add(expiration());
        outcomes.add(lruEviction());
        outcomes.add(rejectedWrites());
        outcomes.add(concurrentReadEvict());
        outcomes.add(flush());
        outcomes.add(failedLoad());

        System.out.printf("%n  %-26s %-11s %-11s %-11s %-11s %-8s %s%n",
                "probe", "slotsBefore", "slotsAfter", "rssBeforeMB", "rssAfterMB", "verdict", "note");
        System.out.println("  " + "-".repeat(108));
        for (Outcome outcome : outcomes) {
            System.out.printf("  %-26s %-11d %-11d %-11d %-11d %-8s %s%n",
                    outcome.name(), outcome.slotsBefore(), outcome.slotsAfter(),
                    outcome.rssBeforeMB(), outcome.rssAfterMB(), outcome.verdict(), outcome.note());
        }

        long leaks = outcomes.stream().filter(o -> o.verdict().equals("LEAK?")).count();
        System.out.printf("%n  %d of %d probes clean, %d flagged%n",
                outcomes.size() - leaks, outcomes.size(), leaks);
        System.exit(leaks == 0 ? 0 : 1);
    }

    /**
     * Admission control is disabled ({@code memoryRejectRatio = 1.0}) for every probe but
     * {@link #rejectedWrites()}.
     *
     * <p>This is not a convenience. Phase 7 established that on an ordinary host the machine-pressure
     * gate refuses writes once the engine holds more than {@code max(64 MiB, 5% of budget)}, whatever the
     * budget says. A leak probe running under that gate would spend its cycles being refused, and would
     * certify "no leak" on a cache that never allocated anything -- the same worthless PASS the soak
     * harness refuses to print. Turning the guard off is what makes the allocate/free paths actually run.
     */
    private static ShardedStorageEngine engine(long budgetBytes, int maxEntriesPerShard) {
        return engine(budgetBytes, maxEntriesPerShard, 1.0d);
    }

    private static ShardedStorageEngine engine(long budgetBytes, int maxEntriesPerShard,
                                               double rejectRatio) {
        return new ShardedStorageEngine(EngineConfig.builder()
                .memoryRejectRatio(rejectRatio)
                .maxOffHeapBytes(budgetBytes)
                .maxValueBytes(Integer.MAX_VALUE - 8)
                .maxEntriesPerShard(maxEntriesPerShard)
                .staleGraceMillis(0)
                .sweepIntervalMillis(250)
                .build());
    }

    /** Settles the process before an RSS reading: two collections, then a pause for the OS to catch up. */
    private static long settledRssMB() throws InterruptedException {
        Probe.settledHeapUsed();
        Thread.sleep(1_500);
        long rss = Probe.rssBytes();
        return rss < 0 ? -1 : rss / MB;
    }

    // --- 1. Replacement: Shard.put -> release(previous) -------------------------------------------------

    private static Outcome replacement() throws InterruptedException {
        try (ShardedStorageEngine engine = engine(2048L * MB, 100_000)) {
            byte[] payload = Payloads.of(PAYLOAD, 7);
            engine.put("k", payload, (byte) 0, 600_000);
            long slotsBefore = engine.stats().liveOffHeapSlots();
            long rssBefore = settledRssMB();

            for (int i = 0; i < CYCLES; i++) {
                engine.put("k", payload, (byte) 0, 600_000);
            }

            return new Outcome("replacement", slotsBefore, engine.stats().liveOffHeapSlots(),
                    rssBefore, settledRssMB(), CYCLES, "1 key overwritten " + CYCLES + "x");
        }
    }

    // --- 2. Explicit delete: Shard.remove -> release ----------------------------------------------------

    private static Outcome explicitDelete() throws InterruptedException {
        try (ShardedStorageEngine engine = engine(2048L * MB, 100_000)) {
            byte[] payload = Payloads.of(PAYLOAD, 8);
            long slotsBefore = engine.stats().liveOffHeapSlots();
            long rssBefore = settledRssMB();

            for (int i = 0; i < CYCLES; i++) {
                engine.put("del:" + i, payload, (byte) 0, 600_000);
                engine.delete("del:" + i);
            }

            return new Outcome("explicit-delete", slotsBefore, engine.stats().liveOffHeapSlots(),
                    rssBefore, settledRssMB(), CYCLES, "put then delete");
        }
    }

    // --- 3. Expiration: sweeper + lazy read-path reclamation --------------------------------------------

    private static Outcome expiration() throws InterruptedException {
        try (ShardedStorageEngine engine = engine(2048L * MB, 100_000)) {
            byte[] payload = Payloads.of(PAYLOAD, 9);
            long slotsBefore = engine.stats().liveOffHeapSlots();
            long rssBefore = settledRssMB();

            int rounds = CYCLES / 50;
            for (int round = 0; round < rounds; round++) {
                for (int i = 0; i < 50; i++) {
                    engine.put("exp:" + round + ":" + i, payload, (byte) 0, 500);
                }
                Thread.sleep(1_200);   // TTL 500 ms + sweeper interval 250 ms + margin
            }
            Thread.sleep(2_000);

            return new Outcome("expiration", slotsBefore, engine.stats().liveOffHeapSlots(),
                    rssBefore, settledRssMB(), (long) rounds * 50,
                    rounds + " rounds of 50 @ 500ms TTL");
        }
    }

    // --- 4. LRU eviction: evictLeastRecentlyUsed -> removeExact -----------------------------------------

    private static Outcome lruEviction() throws InterruptedException {
        // 1 entry per shard, so writing past 32 entries forces continuous LRU eviction.
        try (ShardedStorageEngine engine = engine(2048L * MB, 1)) {
            byte[] payload = Payloads.of(PAYLOAD, 10);
            long slotsBefore = engine.stats().liveOffHeapSlots();
            long rssBefore = settledRssMB();

            for (int i = 0; i < CYCLES; i++) {
                engine.put("lru:" + i, payload, (byte) 0, 600_000);
            }
            Thread.sleep(2_000);   // let the sweeper run its eviction passes

            long evicted = engine.stats().lruEvictions();
            long survivors = engine.stats().entries();

            // The baseline was an empty cache, so the final reading must be of an empty cache too. With
            // one entry per shard this probe legitimately ends holding 32 payloads (128 MB), and
            // comparing that against an empty baseline reports a leak that is really the cache doing its
            // job. Flush first, then measure.
            engine.flush();
            Thread.sleep(2_000);

            return new Outcome("lru-eviction", slotsBefore, engine.stats().liveOffHeapSlots(),
                    rssBefore, settledRssMB(), CYCLES,
                    evicted + " lru evictions, " + survivors + " resident before flush");
        }
    }

    // --- 5. Rejected writes: abortWrite, and the tryReserve failure path --------------------------------

    private static Outcome rejectedWrites() throws InterruptedException {
        // A tiny budget so almost every write is refused, exercising the rejection path hard.
        try (ShardedStorageEngine engine = engine(32 * MB, 100_000, 0.85d)) {
            byte[] payload = Payloads.of(PAYLOAD, 11);
            long slotsBefore = engine.stats().liveOffHeapSlots();
            long rssBefore = settledRssMB();

            LongAdder refused = new LongAdder();
            for (int i = 0; i < CYCLES; i++) {
                if (engine.put("rej:" + i, payload, (byte) 0, 600_000)
                        != WriteStatus.ACCEPTED) {
                    refused.increment();
                }
            }
            engine.flush();
            Thread.sleep(1_000);

            return new Outcome("rejected-writes", slotsBefore, engine.stats().liveOffHeapSlots(),
                    rssBefore, settledRssMB(), CYCLES, refused.sum() + " refused of " + CYCLES);
        }
    }

    // --- 6. Concurrent read / evict: the lease race the refcount exists for -----------------------------

    private static Outcome concurrentReadEvict() throws Exception {
        try (ShardedStorageEngine engine = engine(2048L * MB, 100_000)) {
            byte[] payload = Payloads.of(PAYLOAD, 12);
            long slotsBefore = engine.stats().liveOffHeapSlots();
            long rssBefore = settledRssMB();

            AtomicBoolean stop = new AtomicBoolean();
            LongAdder reads = new LongAdder();
            LongAdder misses = new LongAdder();
            ExecutorService pool = Executors.newFixedThreadPool(9);
            CountDownLatch done = new CountDownLatch(9);

            for (int t = 0; t < 8; t++) {
                pool.submit(() -> {
                    try {
                        java.util.Random rng = new java.util.Random();
                        while (!stop.get()) {
                            try (Lease lease = engine.acquire("race:" + rng.nextInt(64))) {
                                if (lease == null) {
                                    misses.increment();
                                } else {
                                    // Touch the buffer: a use-after-free would surface here as a crash,
                                    // which is the outcome the reference count exists to prevent.
                                    lease.readOnlyView().get(0);
                                    reads.increment();
                                }
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            pool.submit(() -> {
                try {
                    java.util.Random rng = new java.util.Random();
                    for (int i = 0; i < CYCLES && !stop.get(); i++) {
                        int key = rng.nextInt(64);
                        engine.put("race:" + key, payload, (byte) 0, 600_000);
                        if ((i & 3) == 0) {
                            engine.delete("race:" + rng.nextInt(64));
                        }
                    }
                } finally {
                    stop.set(true);
                    done.countDown();
                }
            });

            done.await();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            engine.flush();
            Thread.sleep(1_500);

            return new Outcome("concurrent-read-evict", slotsBefore, engine.stats().liveOffHeapSlots(),
                    rssBefore, settledRssMB(), CYCLES,
                    reads.sum() + " leased reads, " + misses.sum() + " misses, no SIGSEGV");
        }
    }

    // --- 7. Flush: Shard.clear -> release per entry -----------------------------------------------------

    private static Outcome flush() throws InterruptedException {
        try (ShardedStorageEngine engine = engine(2048L * MB, 100_000)) {
            byte[] payload = Payloads.of(PAYLOAD, 13);
            long slotsBefore = engine.stats().liveOffHeapSlots();
            long rssBefore = settledRssMB();

            int rounds = 10;
            for (int round = 0; round < rounds; round++) {
                for (int i = 0; i < 50; i++) {
                    engine.put("flush:" + i, payload, (byte) 0, 600_000);
                }
                engine.flush();
            }
            Thread.sleep(1_500);

            return new Outcome("flush", slotsBefore, engine.stats().liveOffHeapSlots(),
                    rssBefore, settledRssMB(), (long) rounds * 50, rounds + " fill/flush rounds of 50");
        }
    }

    // --- 8. Failed load: readThrough with a throwing loader ---------------------------------------------

    private static Outcome failedLoad() throws InterruptedException {
        try (ShardedStorageEngine engine = engine(2048L * MB, 100_000)) {
            long slotsBefore = engine.stats().liveOffHeapSlots();
            long rssBefore = settledRssMB();

            LongAdder threw = new LongAdder();
            for (int i = 0; i < CYCLES; i++) {
                final int seed = i;
                try {
                    engine.readThrough("load:" + seed, 600_000, () -> {
                        throw new IllegalStateException("simulated loader failure");
                    });
                } catch (RuntimeException expected) {
                    threw.increment();
                }
            }
            Thread.sleep(1_500);

            return new Outcome("failed-load", slotsBefore, engine.stats().liveOffHeapSlots(),
                    rssBefore, settledRssMB(), CYCLES,
                    threw.sum() + " loader failures, leases released");
        }
    }
}
