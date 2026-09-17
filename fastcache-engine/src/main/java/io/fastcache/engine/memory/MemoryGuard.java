package io.fastcache.engine.memory;

import io.fastcache.engine.util.FastCacheLog;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Dynamic memory boundary protection: the component that turns an {@code OutOfMemoryError} crash into a
 * well-behaved {@code REJECTED_MEMORY_PRESSURE} status code.
 *
 * <p>Two ceilings are enforced on every write:
 * <ol>
 *   <li><b>Engine budget</b> &mdash; bytes this engine has reserved vs. its configured off-heap budget.
 *       Always enforced.</li>
 *   <li><b>Machine reality</b> &mdash; physical memory used vs. total, read from the platform MXBean
 *       (container-aware on modern JDKs: cgroup limits are reflected in {@code getTotalMemorySize}).
 *       This catches the case where a <em>co-tenant</em> process eats the box: our own budget can be
 *       nowhere near full and the next 50&nbsp;MB allocation would still trip the OOM killer. Enforced
 *       only once this engine holds enough memory to be part of the problem &mdash; see
 *       {@link #effectiveRatio()}, which explains why the unconditional version is a trap.</li>
 * </ol>
 *
 * <p>Crossing {@code rejectRatio} (default 0.85) flips the guard into rejecting mode; it recovers only
 * below {@code reliefRatio} (default 0.78). That hysteresis stops the engine oscillating between accepting
 * and rejecting on every sample while the sweeper is still draining.
 *
 * <p><b>No {@code synchronized}.</b> Sampling the OS bean is a syscall; it is guarded by a
 * {@link ReentrantLock} acquired with {@code tryLock}, so a virtual thread never blocks (let alone pins a
 * carrier) to read a number another thread is already refreshing.
 */
public final class MemoryGuard {

    private static final FastCacheLog LOG = FastCacheLog.of(MemoryGuard.class);

    /** 4 Hz sampling: the MXBean call is a syscall and is not free under 10k writes/sec. */
    private static final long SAMPLE_INTERVAL_NANOS = 250_000_000L;

    private final long budgetBytes;
    private final double rejectRatio;
    private final double reliefRatio;

    /**
     * Reservation below which machine-wide pressure is ignored. Max of 64 MiB and 5% of the budget: enough
     * that a trivially-loaded engine never shuts itself down over someone else's memory usage, small enough
     * that a genuinely large cache is still held accountable for the host.
     */
    private final long physicalGateFloorBytes;

    private final AtomicLong reserved = new AtomicLong();
    private final AtomicLong highWaterMark = new AtomicLong();
    private final LongAdder rejections = new LongAdder();

    private final ReentrantLock sampleLock = new ReentrantLock();

    /**
     * Timestamp of the last OS sample.
     *
     * <p>Seeded from {@code System.nanoTime()} in the constructor, never from a sentinel like
     * {@code Long.MIN_VALUE}. {@code nanoTime()} has an arbitrary origin and its values are meaningful
     * only when subtracted from each other; {@code now - Long.MIN_VALUE} overflows to a negative number,
     * which compares as "less than the sample interval" forever. That froze the physical reading at
     * whatever it happened to be during construction and silently disabled the machine-pressure ceiling
     * for the life of the process.
     */
    private final AtomicLong lastSampleNanos;
    private volatile double physicalRatio;
    private volatile boolean rejecting;

    private final PhysicalMemoryProbe probe;

    public MemoryGuard(long budgetBytes, double rejectRatio) {
        this(budgetBytes, rejectRatio, Math.max(0.05, rejectRatio - 0.07), PhysicalMemoryProbe.platform());
    }

    MemoryGuard(long budgetBytes, double rejectRatio, double reliefRatio, PhysicalMemoryProbe probe) {
        if (budgetBytes <= 0) {
            throw new IllegalArgumentException("off-heap budget must be positive");
        }
        if (rejectRatio <= 0 || rejectRatio > 1.0) {
            throw new IllegalArgumentException("rejectRatio must be in (0, 1]: " + rejectRatio);
        }
        this.budgetBytes = budgetBytes;
        this.rejectRatio = rejectRatio;
        this.reliefRatio = reliefRatio;
        this.physicalGateFloorBytes = Math.max(64L * 1024 * 1024, (long) (budgetBytes * 0.05));
        this.probe = probe;
        this.physicalRatio = probe.usedRatio();
        this.lastSampleNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Admission control. Reserves {@code bytes} against the budget if, and only if, doing so keeps the
     * engine inside both ceilings.
     *
     * @return {@code true} when the caller may proceed to allocate; {@code false} means the write must be
     *         rejected with a status code &mdash; never an exception, never a crash
     */
    public boolean tryReserve(long bytes) {
        if (bytes <= 0) {
            return true;
        }
        if (isRejecting()) {
            rejections.increment();
            return false;
        }
        while (true) {
            long current = reserved.get();
            long next = current + bytes;
            if (next > budgetBytes || next < 0 /* overflow guard */) {
                rejections.increment();
                return false;
            }
            if (reserved.compareAndSet(current, next)) {
                recordWatermark(next);
                return true;
            }
            Thread.onSpinWait();
        }
    }

    /** Returns reserved bytes to the budget. Must be called exactly once per successful reservation. */
    public void release(long bytes) {
        if (bytes <= 0) {
            return;
        }
        long now = reserved.addAndGet(-bytes);
        if (now < 0) {
            // Defensive: an accounting bug must not permanently wedge the guard into rejecting mode.
            reserved.set(0);
            LOG.warn("Memory accounting underflow by {0} bytes; reset to zero.", -now);
        }
    }

    /**
     * Combined utilisation: the engine's own budget, plus machine pressure <em>once we are plausibly part
     * of the problem</em>.
     *
     * <p>The conditional is the important part. Machine-wide pressure is not by itself a reason to refuse
     * a write, because "used physical memory" on an ordinary host routinely sits at 80-90%: the OS counts
     * file-cache and standby pages as used even though it can reclaim them instantly, and
     * {@code getFreeMemorySize} reports only genuinely free pages, never the larger "available" figure.
     * A laptop with 2.6 GiB free of 16 GiB reads as 84% used while being entirely healthy.
     *
     * <p>Gating unconditionally on that number means an engine holding 5&nbsp;MB refuses every write on a
     * normally-loaded machine. Shedding load then frees 5&nbsp;MB — no help to the host — while silently
     * reducing the cache to a 0% hit rate, with stats that still look healthy. So the physical gate engages
     * only once this engine's own reservation crosses {@link #physicalGateFloorBytes}; below that we are
     * not the cause and refusing writes is pure downside.
     *
     * <p>This is not the OOM defence being weakened. The real last line is
     * {@code ShardedStorageEngine.beginWrite}, which catches a failed native allocation and converts it to
     * a clean rejection; the guard's job is to keep us from *driving* the machine there.
     */
    private double effectiveRatio() {
        double budget = budgetRatio();
        if (reserved.get() < physicalGateFloorBytes) {
            return budget;
        }
        return Math.max(budget, refreshPhysicalRatio());
    }

    /** @return true when the engine is currently refusing writes. */
    public boolean isRejecting() {
        double effective = effectiveRatio();
        boolean current = rejecting;
        if (!current && effective >= rejectRatio) {
            rejecting = true;
            LOG.warn("FastCache entering write-rejection mode at {0}% memory utilisation.",
                    Math.round(effective * 100));
            return true;
        }
        if (current && effective <= reliefRatio) {
            rejecting = false;
            LOG.info("FastCache leaving write-rejection mode at {0}% memory utilisation.",
                    Math.round(effective * 100));
            return false;
        }
        return current;
    }

    public MemoryPressure snapshot() {
        return new MemoryPressure(reserved.get(), budgetBytes, budgetRatio(), refreshPhysicalRatio(),
                effectiveRatio(), rejecting);
    }

    /** Reservation at which machine-wide pressure starts counting against this engine. */
    public long physicalGateFloorBytes() {
        return physicalGateFloorBytes;
    }

    public long reservedBytes() {
        return reserved.get();
    }

    public long budgetBytes() {
        return budgetBytes;
    }

    public long rejectionCount() {
        return rejections.sum();
    }

    public long highWaterMarkBytes() {
        return highWaterMark.get();
    }

    private double budgetRatio() {
        return (double) reserved.get() / (double) budgetBytes;
    }

    private void recordWatermark(long value) {
        highWaterMark.accumulateAndGet(value, Math::max);
    }

    /**
     * Returns the cached physical-memory ratio, refreshing at most every {@link #SAMPLE_INTERVAL_NANOS}.
     * Threads that lose the race read the slightly stale volatile instead of blocking.
     */
    private double refreshPhysicalRatio() {
        long now = System.nanoTime();
        if (now - lastSampleNanos.get() < SAMPLE_INTERVAL_NANOS) {
            return physicalRatio;
        }
        if (!sampleLock.tryLock()) {
            return physicalRatio;
        }
        try {
            if (now - lastSampleNanos.get() < SAMPLE_INTERVAL_NANOS) {
                return physicalRatio;
            }
            physicalRatio = probe.usedRatio();
            lastSampleNanos.set(now);
            return physicalRatio;
        } finally {
            sampleLock.unlock();
        }
    }

    /**
     * Indirection over the platform memory readings, so tests can drive pressure deterministically without
     * having to actually exhaust the machine.
     */
    interface PhysicalMemoryProbe {

        double usedRatio();

        /** The production probe: delegates to the shared {@link PhysicalMemory} readings. */
        static PhysicalMemoryProbe platform() {
            return PhysicalMemory::usedRatio;
        }
    }
}
