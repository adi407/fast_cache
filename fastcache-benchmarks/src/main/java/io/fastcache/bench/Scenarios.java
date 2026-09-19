package io.fastcache.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The scenarios. Each one is a pure function of (arm, config) to a {@link Result}: it must not depend on
 * state another scenario left behind, and it clears the arm before it starts.
 *
 * <p>Warmup is explicit everywhere rather than skipped, and the warmup operations are reported so it is
 * visible that they happened.
 */
public final class Scenarios {

    private Scenarios() {
    }

    /** A named set of measurements, rendered as a row in the results tables. */
    public record Result(String scenario, String arm, int payloadBytes, Map<String, Object> values) {

        public static Result of(String scenario, CacheArm arm, int payloadBytes) {
            return new Result(scenario, arm.name(), payloadBytes, new LinkedHashMap<>());
        }

        public Result with(String key, Object value) {
            values.put(key, value);
            return this;
        }

        public String line() {
            StringBuilder builder = new StringBuilder();
            builder.append(String.format("  %-22s %-20s %-6s ", scenario, arm, Payloads.label(payloadBytes)));
            values.forEach((key, value) -> builder.append(key).append('=').append(render(value)).append(' '));
            return builder.toString();
        }

        private static String render(Object value) {
            if (value instanceof Double d) {
                return String.format("%.2f", d);
            }
            return String.valueOf(value);
        }
    }

    private static final long MB = 1 << 20;

    // ---------------------------------------------------------------------------------------------------
    // Scenario A - Fill
    // ---------------------------------------------------------------------------------------------------

    /**
     * Insert until a known total payload volume is resident, then measure where that memory lives.
     *
     * <p>Heap is read after two forced collections so the figure is settled occupancy rather than
     * whatever happened to be uncollected. The same treatment is applied to every arm.
     */
    public static Result fill(CacheArm arm, int payloadBytes, long targetBytes, Probe probe) {
        arm.clear();
        long heapBefore = Probe.settledHeapUsed();
        long rssBefore = Probe.rssBytes();
        Probe.Mark mark = probe.mark();

        int count = (int) Math.max(1, targetBytes / payloadBytes);
        int accepted = 0;
        long started = System.nanoTime();
        for (int i = 0; i < count; i++) {
            if (arm.put("fill:" + i, Payloads.of(payloadBytes, i), 600_000)) {
                accepted++;
            }
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        long heapAfter = Probe.settledHeapUsed();
        long rssAfter = Probe.rssBytes();
        Probe.GcDelta gc = probe.since(mark);

        return Result.of("A-fill", arm, payloadBytes)
                .with("attempted", count)
                .with("accepted", accepted)
                .with("rejected", count - accepted)
                .with("residentMB", accepted * (long) payloadBytes / MB)
                .with("heapUsedMB", (heapAfter - heapBefore) / MB)
                .with("heapCommittedMB", Probe.heapCommitted() / MB)
                .with("oldGenMB", Probe.oldGenUsed() / MB)
                .with("nonHeapMB", Probe.nonHeapUsed() / MB)
                .with("rssDeltaMB", rssAfter < 0 || rssBefore < 0 ? -1 : (rssAfter - rssBefore) / MB)
                .with("offHeapReservedMB", arm.offHeapReserved() < 0 ? -1 : arm.offHeapReserved() / MB)
                .with("offHeapSlots", arm.offHeapSlots())
                .with("sidecarRssMB", arm.externalRssBytes() < 0 ? -1 : arm.externalRssBytes() / MB)
                .with("entries", arm.entries())
                .with("fillMillis", elapsedMillis)
                .with("gcCollections", gc.collections())
                .with("gcPauseMillis", gc.totalPauseMillis())
                .with("allocMB", gc.allocatedBytes() / MB);
    }

    // ---------------------------------------------------------------------------------------------------
    // Scenario B - Read
    // ---------------------------------------------------------------------------------------------------

    /** Repeated reads of a filled cache. Latency here is client-observed and labelled as such. */
    public static Result read(CacheArm arm, int payloadBytes, long targetBytes, int operations,
                              int concurrency, Probe probe) throws InterruptedException {
        arm.clear();
        int keys = (int) Math.max(1, targetBytes / payloadBytes);
        for (int i = 0; i < keys; i++) {
            arm.put("read:" + i, Payloads.of(payloadBytes, i), 600_000);
        }

        // Warmup must touch every key several times and give the JIT enough iterations to compile the
        // client path, or run 1 measures compilation. The first pass capped this at 200 operations and
        // run 1 came out 10-47% slower than run 3 for every arm - that gap is warmup leaking into the
        // measurement, not a property of any cache.
        int warmup = Math.max(200, Math.min(operations, keys * 5));
        for (int i = 0; i < warmup; i++) {
            arm.get("read:" + (i % keys));
        }

        long[] samples = new long[operations];
        AtomicInteger cursor = new AtomicInteger();
        AtomicLong hits = new AtomicLong();
        AtomicLong corrupt = new AtomicLong();
        Probe.Mark mark = probe.mark();
        long cpuBefore = processCpuNanos();
        long started = System.nanoTime();

        runConcurrently(concurrency, () -> {
            int index;
            while ((index = cursor.getAndIncrement()) < operations) {
                int key = index % keys;
                long t0 = System.nanoTime();
                byte[] value = arm.get("read:" + key);
                samples[index] = System.nanoTime() - t0;
                if (value != null) {
                    hits.incrementAndGet();
                    // Length as well as the seed stamp. The stamp lives in the first four bytes, so a
                    // truncated payload would pass a stamp-only check -- and a transport that silently
                    // returned short reads would then look fast rather than broken. Full array equality
                    // is deliberately not used here: it would add a payload-sized memcmp to every
                    // iteration of a timed loop and measure the check rather than the cache.
                    if (value.length != payloadBytes || Payloads.seedOf(value) != key) {
                        corrupt.incrementAndGet();
                    }
                }
            }
        });

        long wallMillis = (System.nanoTime() - started) / 1_000_000;
        long cpuMillis = (processCpuNanos() - cpuBefore) / 1_000_000;
        Probe.GcDelta gc = probe.since(mark);
        Stats latency = Stats.of(samples, operations).toMicros();

        return Result.of("B-read", arm, payloadBytes)
                .with("warmupOps", warmup)
                .with("operations", operations)
                .with("concurrency", concurrency)
                .with("hits", hits.get())
                .with("corrupt", corrupt.get())
                .with("throughputOpsPerSec", wallMillis == 0 ? -1 : operations * 1000L / wallMillis)
                .with("clientP50us", latency.p50())
                .with("clientP95us", latency.p95())
                .with("clientP99us", latency.p99())
                .with("clientMaxus", latency.max())
                .with("cpuMillis", cpuMillis)
                .with("wallMillis", wallMillis)
                .with("gcCollections", gc.collections())
                .with("gcPauseMillis", gc.totalPauseMillis())
                .with("gcPauseP99us", gc.pauseMicros().p99())
                .with("allocMB", gc.allocatedBytes() / MB);
    }

    // ---------------------------------------------------------------------------------------------------
    // Scenario M - Mixed read/write, with GET and SET reported separately
    // ---------------------------------------------------------------------------------------------------

    /**
     * A read/write mix with the two operation types timed independently.
     *
     * <p>Scenario B measures GET alone, which is where the zero-copy hypothesis predicts an advantage.
     * That is not enough to characterise a cache: if the advantage exists only on the read path and the
     * write path is worse, a write-heavy workload could erase it. This scenario answers that by keeping
     * two separate latency distributions rather than one blended number, because a blended percentile
     * over two populations with different means describes neither.
     *
     * @param readRatio share of operations that are GETs: 1.0 pure read, 0.0 pure write, 0.9 read-heavy
     */
    public static Result mixed(CacheArm arm, int payloadBytes, long targetBytes, int operations,
                               int concurrency, double readRatio, Probe probe)
            throws InterruptedException {
        arm.clear();
        int keys = (int) Math.max(1, targetBytes / payloadBytes);
        for (int i = 0; i < keys; i++) {
            arm.put("mix:" + i, Payloads.of(payloadBytes, i), 600_000);
        }

        int warmup = Math.max(200, Math.min(operations, keys * 5));
        for (int i = 0; i < warmup; i++) {
            int key = i % keys;
            if (i % 10 < readRatio * 10) {
                arm.get("mix:" + key);
            } else {
                arm.put("mix:" + key, Payloads.of(payloadBytes, key), 600_000);
            }
        }

        long[] reads = new long[operations];
        long[] writes = new long[operations];
        AtomicInteger readCount = new AtomicInteger();
        AtomicInteger writeCount = new AtomicInteger();
        AtomicInteger cursor = new AtomicInteger();
        AtomicLong hits = new AtomicLong();
        AtomicLong corrupt = new AtomicLong();
        AtomicLong refused = new AtomicLong();

        Probe.Mark mark = probe.mark();
        long cpuBefore = processCpuNanos();
        long started = System.nanoTime();

        runConcurrently(concurrency, () -> {
            java.util.Random rng = new java.util.Random(Thread.currentThread().threadId());
            int index;
            while ((index = cursor.getAndIncrement()) < operations) {
                int key = rng.nextInt(keys);
                if (rng.nextDouble() < readRatio) {
                    long t0 = System.nanoTime();
                    byte[] value = arm.get("mix:" + key);
                    long elapsed = System.nanoTime() - t0;
                    int slot = readCount.getAndIncrement();
                    if (slot < reads.length) {
                        reads[slot] = elapsed;
                    }
                    if (value != null) {
                        hits.incrementAndGet();
                        if (value.length != payloadBytes || Payloads.seedOf(value) != key) {
                            corrupt.incrementAndGet();
                        }
                    }
                } else {
                    byte[] payload = Payloads.of(payloadBytes, key);
                    long t0 = System.nanoTime();
                    boolean accepted = arm.put("mix:" + key, payload, 600_000);
                    long elapsed = System.nanoTime() - t0;
                    int slot = writeCount.getAndIncrement();
                    if (slot < writes.length) {
                        writes[slot] = elapsed;
                    }
                    if (!accepted) {
                        refused.incrementAndGet();
                    }
                }
            }
        });

        long wallMillis = (System.nanoTime() - started) / 1_000_000;
        long cpuMillis = (processCpuNanos() - cpuBefore) / 1_000_000;
        Probe.GcDelta gc = probe.since(mark);

        int nr = Math.min(readCount.get(), reads.length);
        int nw = Math.min(writeCount.get(), writes.length);
        Stats readStats = Stats.of(reads, nr).toMicros();
        Stats writeStats = Stats.of(writes, nw).toMicros();

        double readMbPerSec = nr == 0 || readStats.p50() == 0
                ? -1 : (payloadBytes / (double) MB) / (readStats.p50() / 1e6);
        double writeMbPerSec = nw == 0 || writeStats.p50() == 0
                ? -1 : (payloadBytes / (double) MB) / (writeStats.p50() / 1e6);

        return Result.of("M-mixed", arm, payloadBytes)
                .with("readRatio", readRatio)
                .with("concurrency", concurrency)
                .with("warmupOps", warmup)
                .with("operations", operations)
                .with("gets", nr)
                .with("sets", nw)
                .with("hits", hits.get())
                .with("corrupt", corrupt.get())
                .with("writesRefused", refused.get())
                .with("opsPerSec", wallMillis == 0 ? -1 : operations * 1000L / wallMillis)
                .with("getP50us", readStats.p50())
                .with("getP95us", readStats.p95())
                .with("getP99us", readStats.p99())
                .with("getMaxus", readStats.max())
                .with("getMBps", readMbPerSec)
                .with("setP50us", writeStats.p50())
                .with("setP95us", writeStats.p95())
                .with("setP99us", writeStats.p99())
                .with("setMaxus", writeStats.max())
                .with("setMBps", writeMbPerSec)
                .with("cpuMillis", cpuMillis)
                .with("wallMillis", wallMillis)
                .with("gcCollections", gc.collections())
                .with("gcPauseMillis", gc.totalPauseMillis())
                .with("allocMB", gc.allocatedBytes() / MB);
    }

    // ---------------------------------------------------------------------------------------------------
    // Scenario C - Churn (the important one)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Sustained PUT / GET / REPLACE / EVICT against a bounded working set.
     *
     * <p>This is the scenario that stresses the lifecycle rather than storage. Steady-state allocation and
     * collection behaviour here is what H2 turns on.
     */
    public static Result churn(CacheArm arm, int payloadBytes, long targetBytes, long durationMillis,
                               int concurrency, Probe probe) throws InterruptedException {
        arm.clear();
        int keys = (int) Math.max(4, targetBytes / payloadBytes);

        // Warm up the working set so the measured window is steady state, not fill.
        for (int i = 0; i < keys; i++) {
            arm.put("churn:" + i, Payloads.of(payloadBytes, i), 600_000);
        }

        LongAdder puts = new LongAdder();
        LongAdder gets = new LongAdder();
        LongAdder replaces = new LongAdder();
        LongAdder evicts = new LongAdder();
        LongAdder refused = new LongAdder();
        List<long[]> rssTrace = new ArrayList<>();

        Probe.Mark mark = probe.mark();
        long cpuBefore = processCpuNanos();
        long heapBefore = Probe.settledHeapUsed();
        long deadline = System.currentTimeMillis() + durationMillis;
        AtomicLong sampleClock = new AtomicLong(System.currentTimeMillis());
        long started = System.nanoTime();

        runConcurrently(concurrency, () -> {
            java.util.Random rng = new java.util.Random(Thread.currentThread().threadId());
            while (System.currentTimeMillis() < deadline) {
                int key = rng.nextInt(keys);
                int roll = rng.nextInt(100);
                if (roll < 40) {
                    arm.get("churn:" + key);
                    gets.increment();
                } else if (roll < 70) {
                    if (!arm.put("churn:" + key, Payloads.of(payloadBytes, key), 600_000)) {
                        refused.increment();
                    }
                    replaces.increment();
                } else if (roll < 90) {
                    if (!arm.put("churn:new:" + rng.nextInt(keys * 2), Payloads.of(payloadBytes, key),
                            600_000)) {
                        refused.increment();
                    }
                    puts.increment();
                } else {
                    arm.remove("churn:new:" + rng.nextInt(keys * 2));
                    evicts.increment();
                }
            }
        }, () -> {
            // Sampler thread: RSS every 5 s. Out-of-process on Windows, so never inside a timed loop.
            while (System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now - sampleClock.get() >= 5_000) {
                    sampleClock.set(now);
                    long rss = Probe.rssBytes();
                    synchronized (rssTrace) {
                        rssTrace.add(new long[]{now, rss, Probe.heapUsed(), arm.offHeapSlots()});
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        long wallMillis = (System.nanoTime() - started) / 1_000_000;
        long cpuMillis = (processCpuNanos() - cpuBefore) / 1_000_000;
        Probe.GcDelta gc = probe.since(mark);
        long heapAfter = Probe.settledHeapUsed();

        long[] rssValues;
        synchronized (rssTrace) {
            rssValues = rssTrace.stream().mapToLong(row -> row[1]).filter(v -> v > 0).toArray();
        }
        long total = puts.sum() + gets.sum() + replaces.sum() + evicts.sum();

        return Result.of("C-churn", arm, payloadBytes)
                .with("durationMillis", wallMillis)
                .with("concurrency", concurrency)
                .with("workingSetKeys", keys)
                .with("operations", total)
                .with("opsPerSec", wallMillis == 0 ? -1 : total * 1000L / wallMillis)
                .with("puts", puts.sum())
                .with("gets", gets.sum())
                .with("replaces", replaces.sum())
                .with("removes", evicts.sum())
                .with("writesRefused", refused.sum())
                .with("heapDeltaMB", (heapAfter - heapBefore) / MB)
                .with("rssSamples", rssValues.length)
                .with("rssFloorMB", rssValues.length == 0 ? -1 : floor(rssValues) / MB)
                .with("rssMaxMB", rssValues.length == 0 ? -1
                        : java.util.Arrays.stream(rssValues).max().orElse(0) / MB)
                .with("offHeapSlots", arm.offHeapSlots())
                .with("sidecarRssMB", arm.externalRssBytes() < 0 ? -1 : arm.externalRssBytes() / MB)
                .with("cpuMillis", cpuMillis)
                .with("cpuRatio", wallMillis == 0 ? -1.0 : (double) cpuMillis / wallMillis)
                .with("gcCollections", gc.collections())
                .with("gcPauseMillis", gc.totalPauseMillis())
                .with("gcPauseP50us", gc.pauseMicros().p50())
                .with("gcPauseP95us", gc.pauseMicros().p95())
                .with("gcPauseP99us", gc.pauseMicros().p99())
                .with("gcPauseMaxus", gc.pauseMicros().max())
                .with("allocMB", gc.allocatedBytes() / MB)
                .with("allocMBPerSec", wallMillis == 0 ? -1
                        : gc.allocatedBytes() / MB * 1000L / wallMillis);
    }

    // ---------------------------------------------------------------------------------------------------
    // Scenario D - Eviction
    // ---------------------------------------------------------------------------------------------------

    /** Fill past the configured bound and measure whether memory comes back. */
    public static Result eviction(CacheArm arm, int payloadBytes, long budgetBytes, Probe probe) {
        arm.clear();
        long baselineRss = Probe.rssBytes();
        long baselineSlots = arm.offHeapSlots();

        int overfill = (int) Math.max(2, (budgetBytes * 3) / payloadBytes);
        int accepted = 0;
        for (int i = 0; i < overfill; i++) {
            if (arm.put("evict:" + i, Payloads.of(payloadBytes, i), 600_000)) {
                accepted++;
            }
        }

        long heapAtPeak = Probe.settledHeapUsed();
        long rssAtPeak = Probe.rssBytes();
        long entriesAtPeak = arm.entries();
        long slotsAtPeak = arm.offHeapSlots();

        arm.clear();
        long heapAfter = Probe.settledHeapUsed();
        long rssAfter = Probe.rssBytes();

        return Result.of("D-eviction", arm, payloadBytes)
                .with("attempted", overfill)
                .with("accepted", accepted)
                .with("refused", overfill - accepted)
                .with("budgetMB", budgetBytes / MB)
                .with("offeredMB", (long) overfill * payloadBytes / MB)
                .with("entriesRetained", entriesAtPeak)
                .with("retainedMB", entriesAtPeak * (long) payloadBytes / MB)
                .with("boundedByBytes", entriesAtPeak * (long) payloadBytes <= budgetBytes * 1.2)
                .with("heapAtPeakMB", heapAtPeak / MB)
                .with("rssAtPeakMB", rssAtPeak < 0 ? -1 : rssAtPeak / MB)
                .with("slotsAtPeak", slotsAtPeak)
                .with("heapAfterClearMB", heapAfter / MB)
                .with("rssAfterClearMB", rssAfter < 0 ? -1 : rssAfter / MB)
                .with("rssRecoveredMB", rssAtPeak < 0 || rssAfter < 0 ? -1 : (rssAtPeak - rssAfter) / MB)
                .with("slotsAfterClear", arm.offHeapSlots())
                .with("sidecarRssAfterMB", arm.externalRssBytes() < 0 ? -1 : arm.externalRssBytes() / MB)
                .with("slotsReturnedToBaseline", arm.offHeapSlots() <= baselineSlots + 5)
                .with("baselineRssMB", baselineRss < 0 ? -1 : baselineRss / MB);
    }

    // ---------------------------------------------------------------------------------------------------
    // Scenario E - Expiration
    // ---------------------------------------------------------------------------------------------------

    /** Short TTLs, then wait past expiry and the sweeper interval, and see whether memory returns. */
    public static Result expiration(CacheArm arm, int payloadBytes, long targetBytes, Probe probe)
            throws InterruptedException {
        arm.clear();
        int count = (int) Math.max(1, targetBytes / payloadBytes);
        for (int i = 0; i < count; i++) {
            arm.put("expire:" + i, Payloads.of(payloadBytes, i), 2_000);
        }
        long entriesBefore = arm.entries();
        long slotsBefore = arm.offHeapSlots();
        long rssBefore = Probe.rssBytes();

        // 2 s TTL + the 1 s sweeper interval + margin. The read below also triggers lazy reclamation.
        Thread.sleep(6_000);
        int stillVisible = 0;
        for (int i = 0; i < count; i++) {
            if (arm.get("expire:" + i) != null) {
                stillVisible++;
            }
        }
        Thread.sleep(2_000);

        long heapAfter = Probe.settledHeapUsed();
        long rssAfter = Probe.rssBytes();

        return Result.of("E-expiration", arm, payloadBytes)
                .with("inserted", count)
                .with("entriesBeforeExpiry", entriesBefore)
                .with("slotsBeforeExpiry", slotsBefore)
                .with("visibleAfterTtl", stillVisible)
                .with("entriesAfter", arm.entries())
                .with("slotsAfter", arm.offHeapSlots())
                .with("slotsReclaimed", slotsBefore < 0 ? -1 : slotsBefore - arm.offHeapSlots())
                .with("sidecarRssMB", arm.externalRssBytes() < 0 ? -1 : arm.externalRssBytes() / MB)
                .with("heapAfterMB", heapAfter / MB)
                .with("rssBeforeMB", rssBefore < 0 ? -1 : rssBefore / MB)
                .with("rssAfterMB", rssAfter < 0 ? -1 : rssAfter / MB)
                .with("rssReclaimedMB", rssBefore < 0 || rssAfter < 0 ? -1 : (rssBefore - rssAfter) / MB);
    }

    // ---------------------------------------------------------------------------------------------------
    // Scenario F - Replacement (stresses reference counting)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Overwrite one key repeatedly. Every write displaces a predecessor whose slot must be released by
     * {@code Shard.put} -> {@code release(previous)}. A missed release here is invisible on the heap and
     * shows only in RSS and the slot count, which is why both are recorded.
     */
    public static Result replacement(CacheArm arm, int payloadBytes, int cycles, Probe probe) {
        arm.clear();
        long slotsBefore = arm.offHeapSlots();
        long rssBefore = Probe.rssBytes();
        Probe.Mark mark = probe.mark();

        long refused = 0;
        for (int i = 0; i < cycles; i++) {
            if (!arm.put("replace:single", Payloads.of(payloadBytes, i), 600_000)) {
                refused++;
            }
            if ((i & 7) == 0) {
                arm.get("replace:single");   // interleave a reader, so leases overlap replacements
            }
        }

        long slotsAfter = arm.offHeapSlots();
        long heapAfter = Probe.settledHeapUsed();
        long rssAfter = Probe.rssBytes();
        Probe.GcDelta gc = probe.since(mark);

        return Result.of("F-replacement", arm, payloadBytes)
                .with("cycles", cycles)
                .with("refused", refused)
                .with("bytesWritten", (long) cycles * payloadBytes / MB + "MB")
                .with("entries", arm.entries())
                .with("slotsBefore", slotsBefore)
                .with("slotsAfter", slotsAfter)
                .with("expectedSlots", arm.storesOffHeap() ? 1 : -1)
                .with("slotLeak", slotsAfter < 0 ? -1 : Math.max(0, slotsAfter - 1))
                .with("sidecarRssMB", arm.externalRssBytes() < 0 ? -1 : arm.externalRssBytes() / MB)
                .with("heapAfterMB", heapAfter / MB)
                .with("rssBeforeMB", rssBefore < 0 ? -1 : rssBefore / MB)
                .with("rssAfterMB", rssAfter < 0 ? -1 : rssAfter / MB)
                .with("rssGrowthMB", rssBefore < 0 || rssAfter < 0 ? -1 : (rssAfter - rssBefore) / MB)
                .with("gcCollections", gc.collections())
                .with("gcPauseMillis", gc.totalPauseMillis())
                .with("allocMB", gc.allocatedBytes() / MB);
    }

    // ---------------------------------------------------------------------------------------------------
    // Scenario G - Clear
    // ---------------------------------------------------------------------------------------------------

    /** Fill, clear, and measure how much memory the process actually gives back. */
    public static Result clear(CacheArm arm, int payloadBytes, long targetBytes, Probe probe)
            throws InterruptedException {
        arm.clear();
        long rssBaseline = Probe.rssBytes();
        long heapBaseline = Probe.settledHeapUsed();

        int count = (int) Math.max(1, targetBytes / payloadBytes);
        for (int i = 0; i < count; i++) {
            arm.put("clear:" + i, Payloads.of(payloadBytes, i), 600_000);
        }
        long heapFilled = Probe.settledHeapUsed();
        long rssFilled = Probe.rssBytes();
        long slotsFilled = arm.offHeapSlots();

        arm.clear();
        Thread.sleep(2_000);
        long heapCleared = Probe.settledHeapUsed();
        long rssCleared = Probe.rssBytes();

        return Result.of("G-clear", arm, payloadBytes)
                .with("inserted", count)
                .with("residentMB", (long) count * payloadBytes / MB)
                .with("heapBaselineMB", heapBaseline / MB)
                .with("heapFilledMB", heapFilled / MB)
                .with("heapClearedMB", heapCleared / MB)
                .with("heapRecoveredMB", (heapFilled - heapCleared) / MB)
                .with("rssBaselineMB", rssBaseline < 0 ? -1 : rssBaseline / MB)
                .with("rssFilledMB", rssFilled < 0 ? -1 : rssFilled / MB)
                .with("rssClearedMB", rssCleared < 0 ? -1 : rssCleared / MB)
                .with("rssRecoveredMB", rssFilled < 0 || rssCleared < 0 ? -1 : (rssFilled - rssCleared) / MB)
                .with("slotsFilled", slotsFilled)
                .with("slotsCleared", arm.offHeapSlots())
                .with("sidecarRssAfterMB", arm.externalRssBytes() < 0 ? -1 : arm.externalRssBytes() / MB)
                .with("entriesAfter", arm.entries());
    }

    // ---------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------

    /** The trough a sawtooth returns to, as the 10th percentile. Matches the soak harness convention. */
    static long floor(long[] values) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[(int) Math.round(0.10 * (sorted.length - 1))];
    }

    private static void runConcurrently(int threads, Runnable work, Runnable... extras)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads + extras.length);
        CountDownLatch done = new CountDownLatch(threads + extras.length);
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        work.run();
                    } finally {
                        done.countDown();
                    }
                });
            }
            for (Runnable extra : extras) {
                executor.submit(() -> {
                    try {
                        extra.run();
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            return extended.getProcessCpuTime();
        }
        return 0;
    }
}
