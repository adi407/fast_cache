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
 * <p><b>The hysteresis is applied per ceiling, and the gate has a band of its own.</b> The two ceilings
 * measure different quantities &mdash; a fraction of <em>our</em> budget and a fraction of <em>the
 * machine</em> &mdash; so a single band across whichever one happens to be selected is not hysteresis at
 * all. Rejecting on machine pressure at 0.85 and then relieving because the reservation fell under the
 * gate floor, where the reading switches to a budget ratio of 0.06, skips the 0.78-0.85 band entirely and
 * flips the guard on every crossing of the floor. So each ceiling keeps its own reject/relief state
 * against its own reading, and the gate itself engages at {@link #physicalGateFloorBytes} but does not
 * disengage until the reservation falls below {@code physicalGateReleaseBytes}, after which it stays
 * disarmed for {@link #GATE_REARM_NANOS} so a cache that has just shed its memory gets a window to refill
 * instead of chattering at the boundary.
 *
 * <p><b>No {@code synchronized}.</b> Sampling the OS bean is a syscall; it is guarded by a
 * {@link ReentrantLock} acquired with {@code tryLock}, so a virtual thread never blocks (let alone pins a
 * carrier) to read a number another thread is already refreshing.
 */
public final class MemoryGuard {

    private static final FastCacheLog LOG = FastCacheLog.of(MemoryGuard.class);

    /** 4 Hz sampling: the MXBean call is a syscall and is not free under 10k writes/sec. */
    private static final long SAMPLE_INTERVAL_NANOS = 250_000_000L;

    /**
     * How long the physical gate stays disarmed after the reservation drops below its release floor.
     *
     * <p>The gate's own reject/relief band stops the chatter of a reservation hovering exactly at the
     * floor, but a band alone cannot bound the flip rate of a cache that genuinely drains and refills: it
     * only widens the excursion needed to flip. The dwell is what turns "flips once per crossing" into
     * "flips at most twice per dwell".
     *
     * <p>It is deliberately short, because it is a real hole in the machine ceiling: while the gate is
     * disarmed the engine may grow, and how far it grows depends on the write rate rather than on
     * anything this class controls. Two seconds is a few tens of MB at the write rates this engine is
     * built for, which keeps the post-relief plateau near the gate floor where it belongs, while still
     * collapsing the second-by-second flapping seen in the soak logs. The budget ceiling still applies
     * throughout, and {@code ShardedStorageEngine.beginWrite} still catches a failed native allocation,
     * so the window is bounded rather than unguarded. Lengthening it trades a lower flip rate for a
     * proportionally larger cache excursion &mdash; a bad trade past a few seconds.
     */
    private static final long GATE_REARM_NANOS = 2_000_000_000L;

    private final long budgetBytes;
    private final double rejectRatio;
    private final double reliefRatio;

    /**
     * Reservation below which machine-wide pressure is ignored. Max of 64 MiB and 5% of the budget: enough
     * that a trivially-loaded engine never shuts itself down over someone else's memory usage, small enough
     * that a genuinely large cache is still held accountable for the host.
     */
    private final long physicalGateFloorBytes;

    /**
     * Reservation at which the gate lets go again, at 75% of the floor it engages on. The gap is the
     * gate's own hysteresis band: it is what stops a reservation sitting on the floor from arming and
     * disarming machine pressure on alternate writes.
     */
    private final long physicalGateReleaseBytes;

    /** Which ceiling put us in rejecting mode, and therefore which reading has to recover to get out. */
    private enum RejectionSource { NONE, BUDGET, PHYSICAL }

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
    private volatile RejectionSource rejectionSource = RejectionSource.NONE;

    /** Whether machine pressure currently counts against this engine. Updated only by {@link #isRejecting()}. */
    private volatile boolean physicalGateEngaged;

    /**
     * When the gate last disengaged, for the {@link #GATE_REARM_NANOS} dwell. Seeded one full dwell in the
     * past rather than at zero, for the same reason {@link #lastSampleNanos} is seeded from the clock:
     * {@code nanoTime()} has an arbitrary origin and may be negative, so a zero sentinel would leave the
     * gate either permanently disarmed or armed depending on which way the JVM's origin happened to fall.
     */
    private final AtomicLong gateDisengagedNanos;

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
        this.physicalGateReleaseBytes = (long) (this.physicalGateFloorBytes * 0.75);
        this.probe = probe;
        this.physicalRatio = probe.usedRatio();
        this.lastSampleNanos = new AtomicLong(System.nanoTime());
        this.gateDisengagedNanos = new AtomicLong(System.nanoTime() - GATE_REARM_NANOS);
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
        if (!physicalGateEngaged) {
            return budget;
        }
        return Math.max(budget, refreshPhysicalRatio());
    }

    /**
     * Arms or disarms the machine-pressure ceiling, with hysteresis and a dwell.
     *
     * <p>Engaging and disengaging on the same number would make the gate flip on alternate writes for a
     * reservation sitting at the floor; because the two sides of the gate report different quantities,
     * each of those flips is a full swing of the effective ratio and so a full swing of the guard. The
     * band plus {@link #GATE_REARM_NANOS} bounds that: the reservation has to fall a quarter below the
     * floor to let go, and cannot re-arm the ceiling until the dwell has passed.
     *
     * @return whether machine pressure counts against this engine right now
     */
    private boolean updatePhysicalGate(long held) {
        if (physicalGateEngaged) {
            if (held >= physicalGateReleaseBytes) {
                return true;
            }
            physicalGateEngaged = false;
            gateDisengagedNanos.set(System.nanoTime());
            return false;
        }
        if (held < physicalGateFloorBytes) {
            return false;
        }
        if (System.nanoTime() - gateDisengagedNanos.get() < GATE_REARM_NANOS) {
            return false;
        }
        physicalGateEngaged = true;
        return true;
    }

    /**
     * @return true when the engine is currently refusing writes.
     *
     * <p>Each ceiling is tested against its own reading, with its own reject/relief band, and the reason
     * we are rejecting is remembered so that relief is judged on the same quantity the rejection was.
     * Sharing one band across whichever reading the gate happened to select is what defeated the
     * hysteresis: the value moved between the two ceilings rather than within one of them.
     */
    public boolean isRejecting() {
        long held = reserved.get();
        double budget = (double) held / (double) budgetBytes;
        boolean gate = updatePhysicalGate(held);
        boolean current = rejecting;
        RejectionSource source = rejectionSource;

        // A ceiling already holding us rejects until its own reading falls to the relief ratio; one that
        // is not, only once its own reading reaches the reject ratio.
        boolean budgetRejects = current && source == RejectionSource.BUDGET
                ? budget > reliefRatio
                : budget >= rejectRatio;

        double physical = gate ? refreshPhysicalRatio() : 0.0;
        boolean physicalRejects = gate && (current && source == RejectionSource.PHYSICAL
                ? physical > reliefRatio
                : physical >= rejectRatio);

        RejectionSource next;
        if (current && source == RejectionSource.PHYSICAL && physicalRejects) {
            next = RejectionSource.PHYSICAL; // Keep measuring against the reading we rejected on.
        } else if (budgetRejects) {
            next = RejectionSource.BUDGET;
        } else if (physicalRejects) {
            next = RejectionSource.PHYSICAL;
        } else {
            next = RejectionSource.NONE;
        }

        boolean rejectNow = next != RejectionSource.NONE;
        if (rejectNow == current) {
            rejectionSource = next; // The reason can change without the decision changing.
            return current;
        }

        rejecting = rejectNow;
        rejectionSource = next;
        if (rejectNow) {
            double ratio = next == RejectionSource.BUDGET ? budget : physical;
            LOG.warn("FastCache entering write-rejection mode at {0}% memory utilisation ({1}).",
                    Math.round(ratio * 100), reasonFor(next));
        } else {
            // Naming what recovered matters: a machine-pressure rejection that clears because the engine
            // shed its way below the gate floor used to be logged as "leaving ... at 6%", a budget figure
            // that had nothing to do with why the guard let go.
            String why = source == RejectionSource.PHYSICAL && !gate
                    ? "engine below the gate floor"
                    : "utilisation back under the relief ratio";
            LOG.info("FastCache leaving write-rejection mode at {0}% memory utilisation ({1}).",
                    Math.round(effectiveRatio() * 100), why);
        }
        return rejectNow;
    }

    private static String reasonFor(RejectionSource source) {
        return source == RejectionSource.BUDGET ? "engine budget" : "machine pressure";
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
