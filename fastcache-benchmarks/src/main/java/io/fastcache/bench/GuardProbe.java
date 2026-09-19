package io.fastcache.bench;

import io.fastcache.engine.core.EngineConfig;
import io.fastcache.engine.core.ShardedStorageEngine;
import io.fastcache.engine.core.WriteStatus;
import io.fastcache.engine.memory.PhysicalMemory;

import java.util.Locale;

/**
 * Phase 7: a controlled test of what actually gates a FastCache write.
 *
 * <p>The question this answers is narrow and falsifiable: <b>when the engine starts refusing writes, how
 * much of the configured budget was still free?</b> If the answer is "most of it", then the budget is a
 * ceiling that cannot be reached rather than a capacity that is honoured, and every capacity-planning
 * statement made about FastCache is wrong by whatever that factor turns out to be.
 *
 * <p>The probe fills an engine with off-heap payloads until {@code MemoryGuard} rejects, then reports the
 * reservation at that moment against the budget it was given. It repeats across several budgets because
 * the machine-pressure gate arms at {@code max(64 MiB, 5% of budget)}, so the budget changes when the gate
 * can engage at all.
 *
 * <p>Run it alone, on an otherwise idle machine, and record the physical memory ratio it prints. That
 * number is the independent variable: the same code on a machine with more free memory will behave
 * differently, which is itself the finding.
 */
public final class GuardProbe {

    private static final long MB = 1 << 20;

    public static void main(String[] args) {
        int payloadBytes = args.length > 0 ? Integer.parseInt(args[0]) : (4 << 20);
        long[] budgets = {128 * MB, 512 * MB, 1024 * MB, 4096L * MB};

        System.out.println("=".repeat(100));
        System.out.println("MemoryGuard admission probe");
        System.out.println("=".repeat(100));
        System.out.printf("  host physical total   %d MB%n", PhysicalMemory.totalBytes() / MB);
        System.out.printf("  host physical free    %d MB%n", PhysicalMemory.freeBytes() / MB);
        System.out.printf("  host physical used    %.4f  (guard rejects at 0.85)%n",
                PhysicalMemory.usedRatio());
        System.out.printf("  payload size          %s%n", Payloads.label(payloadBytes));
        System.out.println("=".repeat(100));
        System.out.printf("%n  %-10s %-12s %-14s %-12s %-10s %-12s %s%n",
                "budgetMB", "gateFloorMB", "firstRejectMB", "budgetUsed%", "accepted", "physRatio",
                "verdict");
        System.out.println("  " + "-".repeat(94));

        for (long budget : budgets) {
            probe(budget, payloadBytes);
        }

        System.out.println("""

                  budgetUsed% is the fraction of the CONFIGURED budget that had been reserved at the moment
                  the guard first refused a write. A value near 85% means the budget ceiling did the
                  rejecting, which is the documented and intended behaviour. A value well below 85% means
                  the machine-pressure gate did it, and the configured budget was never reachable.
                """);

        drainTest(payloadBytes);
    }

    /**
     * Does a cache under machine pressure stabilise, or drain?
     *
     * <p>{@code EvictionSweeper.sweepShard} sheds {@code max(1, size * 0.10)} entries per shard per pass
     * while {@code MemoryGuard.isRejecting()} holds. The {@code max(1, ...)} is a floor: a shard holding
     * fewer than ten entries still loses one per pass. For a cache of large values -- few entries, each
     * big, which is the workload FastCache is positioned for -- that floor is the dominant term.
     *
     * <p>Relief is the other half. A budget-sourced rejection clears when the engine sheds below the
     * relief ratio, so it self-corrects. A machine-sourced rejection clears only when whole-machine memory
     * falls, which shedding a few hundred MB on a 16 GB host barely moves. If the gate stays armed, the
     * sweeper keeps shedding with nothing to stop it.
     *
     * <p>This test fills, then stops writing entirely and watches for 30 seconds. Entries falling to zero
     * with no writer and no TTL expiry can only be the pressure sweeper.
     */
    private static void drainTest(int payloadBytes) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("Drain test - fill, then STOP WRITING and watch for 30s");
        System.out.println("  TTL is 10 minutes, so nothing below can be TTL expiry.");
        System.out.println("=".repeat(100));

        // A budget far larger than the fill, so the budget ceiling cannot be what rejects.
        try (ShardedStorageEngine engine = new ShardedStorageEngine(EngineConfig.builder()
                .maxOffHeapBytes(4096L * MB)
                .maxValueBytes(Integer.MAX_VALUE - 8)
                .maxEntriesPerShard(1_000_000)
                .defaultTtlMillis(600_000)
                .staleGraceMillis(0)
                .build())) {

            byte[] payload = Payloads.of(payloadBytes, 99);
            int target = (int) (512 * MB / payloadBytes);
            int accepted = 0;
            for (int i = 0; i < target; i++) {
                if (engine.put("drain:" + i, payload, (byte) 0, 600_000) == WriteStatus.ACCEPTED) {
                    accepted++;
                }
            }

            System.out.printf("%n  filled: %d of %d accepted, %d entries, %d MB reserved of 4096 MB%n",
                    accepted, target, engine.stats().entries(),
                    engine.stats().memory().reservedBytes() / MB);
            System.out.printf("  guard rejecting = %s, physical ratio = %.4f%n%n",
                    engine.stats().memory().rejecting(), PhysicalMemory.usedRatio());
            System.out.printf("  %-8s %-10s %-10s %-14s %-12s %s%n",
                    "t(s)", "entries", "slots", "reservedMB", "rejecting", "lruEvictions");
            System.out.println("  " + "-".repeat(72));

            for (int second = 0; second <= 30; second += 5) {
                var stats = engine.stats();
                System.out.printf("  %-8d %-10d %-10d %-14d %-12s %d%n",
                        second, stats.entries(), stats.liveOffHeapSlots(),
                        stats.memory().reservedBytes() / MB, stats.memory().rejecting(),
                        stats.lruEvictions());
                if (second == 30) {
                    break;
                }
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            long finalEntries = engine.stats().entries();
            System.out.printf("%n  VERDICT: %s%n", finalEntries == 0
                    ? "cache DRAINED TO ZERO with no writer and no TTL expiry"
                    : finalEntries < accepted
                    ? "cache shed " + (accepted - finalEntries) + " of " + accepted + " entries"
                    : "cache stable - no pressure eviction observed on this host");
        }
    }

    private static void probe(long budgetBytes, int payloadBytes) {
        EngineConfig config = EngineConfig.builder()
                .maxOffHeapBytes(budgetBytes)
                .maxValueBytes(Integer.MAX_VALUE - 8)
                .maxEntriesPerShard(1_000_000)
                .defaultTtlMillis(600_000)
                .staleGraceMillis(0)
                .build();

        try (ShardedStorageEngine engine = new ShardedStorageEngine(config)) {
            long gateFloor = Math.max(64 * MB, (long) (budgetBytes * 0.05));
            byte[] payload = Payloads.of(payloadBytes, 1);
            long firstRejectReserved = -1;
            double physicalAtReject = -1;
            int accepted = 0;

            // Cap the attempt count so a machine with plenty of free memory finishes rather than filling
            // the budget forever: reaching the cap without a rejection is itself a clean result.
            int maxAttempts = (int) (budgetBytes / payloadBytes) + 16;
            for (int i = 0; i < maxAttempts; i++) {
                WriteStatus status = engine.put("guard:" + i, payload, (byte) 0, 600_000);
                if (status == WriteStatus.ACCEPTED) {
                    accepted++;
                    continue;
                }
                if (status == WriteStatus.REJECTED_MEMORY_PRESSURE) {
                    firstRejectReserved = engine.stats().memory().reservedBytes();
                    physicalAtReject = PhysicalMemory.usedRatio();
                    break;
                }
            }

            String verdict;
            double budgetUsed;
            if (firstRejectReserved < 0) {
                budgetUsed = (double) engine.stats().memory().reservedBytes() / budgetBytes;
                verdict = "no rejection within budget";
                System.out.printf("  %-10d %-12d %-14s %-12.1f %-10d %-12s %s%n",
                        budgetBytes / MB, gateFloor / MB, "none", budgetUsed * 100, accepted,
                        String.format(Locale.ROOT, "%.4f", PhysicalMemory.usedRatio()), verdict);
                return;
            }

            budgetUsed = (double) firstRejectReserved / budgetBytes;
            // 0.80 rather than 0.85: the guard rejects at >= 0.85 but the reading is taken one payload
            // later, so a genuine budget rejection lands just under the ratio at this granularity.
            verdict = budgetUsed >= 0.80
                    ? "BUDGET ceiling (intended)"
                    : "MACHINE gate - budget unreachable";
            System.out.printf("  %-10d %-12d %-14d %-12.1f %-10d %-12s %s%n",
                    budgetBytes / MB, gateFloor / MB, firstRejectReserved / MB, budgetUsed * 100,
                    accepted, String.format(Locale.ROOT, "%.4f", physicalAtReject), verdict);
        }
    }
}
