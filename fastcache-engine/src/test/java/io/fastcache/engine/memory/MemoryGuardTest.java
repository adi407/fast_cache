package io.fastcache.engine.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admission control.
 *
 * <p>Machine pressure is injected rather than induced: a test that tried to genuinely exhaust the host's
 * RAM would be slow, flaky, and would take the CI runner down with it. The package-private constructor
 * exists precisely so the probe can be driven deterministically.
 */
class MemoryGuardTest {

    private static final long MB = 1024 * 1024;

    /** A probe whose reading the test controls outright. */
    private static final class FakeProbe implements MemoryGuard.PhysicalMemoryProbe {
        volatile double ratio;

        FakeProbe(double ratio) {
            this.ratio = ratio;
        }

        @Override
        public double usedRatio() {
            return ratio;
        }
    }

    @Test
    @DisplayName("accepts writes inside the budget")
    void acceptsWithinBudget() {
        MemoryGuard guard = new MemoryGuard(100 * MB, 0.85, 0.78, new FakeProbe(0.10));

        assertTrue(guard.tryReserve(10 * MB));
        assertEquals(10 * MB, guard.reservedBytes());
        assertFalse(guard.isRejecting());
    }

    @Test
    @DisplayName("rejects the write that would cross the budget, without throwing")
    void rejectsBeyondBudget() {
        MemoryGuard guard = new MemoryGuard(100 * MB, 0.85, 0.78, new FakeProbe(0.10));

        assertTrue(guard.tryReserve(80 * MB));
        assertFalse(guard.tryReserve(40 * MB), "120 MB does not fit in a 100 MB budget");
        assertEquals(80 * MB, guard.reservedBytes(), "a rejected reservation must not be charged");
        assertTrue(guard.rejectionCount() > 0);
    }

    @Test
    @DisplayName("releasing budget lets writes through again")
    void releaseRestoresCapacity() {
        MemoryGuard guard = new MemoryGuard(100 * MB, 0.85, 0.78, new FakeProbe(0.10));

        assertTrue(guard.tryReserve(90 * MB));
        assertFalse(guard.tryReserve(20 * MB));

        guard.release(50 * MB);
        assertTrue(guard.tryReserve(20 * MB), "capacity freed by eviction must become usable");
    }

    @Test
    @DisplayName("hysteresis: rejection starts at 85% and only clears below 78%")
    void hysteresisPreventsOscillation() {
        MemoryGuard guard = new MemoryGuard(100 * MB, 0.85, 0.78, new FakeProbe(0.10));

        guard.tryReserve(86 * MB);
        assertTrue(guard.isRejecting(), "86% of budget is past the 85% reject ratio");

        guard.release(4 * MB); // Down to 82% — above the relief threshold.
        assertTrue(guard.isRejecting(),
                "recovering at the same threshold it rejects at would make the guard oscillate");

        guard.release(10 * MB); // Down to 72% — below relief.
        assertFalse(guard.isRejecting());
    }

    @Test
    @DisplayName("machine pressure is ignored while this engine holds a trivial amount")
    void physicalGateIgnoredBelowTheFloor() {
        // 99% of the machine is in use, but by somebody else: this engine holds almost nothing.
        MemoryGuard guard = new MemoryGuard(1024 * MB, 0.85, 0.78, new FakeProbe(0.99));

        assertTrue(guard.tryReserve(1 * MB),
                "an engine holding 1 MB cannot be the cause of host pressure; refusing writes here frees "
                        + "nothing and silently reduces the cache to a 0% hit rate");
        assertFalse(guard.isRejecting());
    }

    @Test
    @DisplayName("machine pressure applies once this engine holds a material amount")
    void physicalGateAppliesAboveTheFloor() throws InterruptedException {
        FakeProbe probe = new FakeProbe(0.10);
        MemoryGuard guard = new MemoryGuard(1024 * MB, 0.85, 0.78, probe);

        // Cross the gate floor (max of 64 MiB and 5% of budget) while the machine is healthy.
        assertTrue(guard.tryReserve(200 * MB));
        assertFalse(guard.isRejecting());

        probe.ratio = 0.95; // Now the host really is under pressure, and we are a big part of it.
        // The guard samples the OS at 4 Hz on purpose - reading it per write would put a syscall on the
        // hot path - so a change in machine pressure takes up to one sample interval to be observed.
        Thread.sleep(300);
        assertTrue(guard.isRejecting(), "a large cache must be held accountable for the host");
        assertFalse(guard.tryReserve(1 * MB));
    }

    @Test
    @DisplayName("the physical reading is actually re-sampled, not frozen at construction")
    void physicalReadingIsRefreshed() throws InterruptedException {
        // Regression test. lastSampleNanos was seeded with Long.MIN_VALUE, and System.nanoTime() has an
        // arbitrary origin: now - Long.MIN_VALUE overflows negative, which compares as "less than the
        // sample interval" forever. The guard therefore took its early return on every call and never
        // re-read the OS, freezing the machine-pressure ceiling at whatever it was during construction.
        // Everything still looked correct in manual testing because the constructor's sample happened to
        // be representative.
        FakeProbe probe = new FakeProbe(0.10);
        MemoryGuard guard = new MemoryGuard(100 * MB, 0.85, 0.78, probe);

        assertEquals(0.10, guard.snapshot().physicalRatio(), 0.001);

        probe.ratio = 0.91;
        Thread.sleep(300); // Past the 4 Hz sample interval.

        assertEquals(0.91, guard.snapshot().physicalRatio(), 0.001,
                "a guard that never re-samples cannot react to the machine filling up");
    }

    @Test
    @DisplayName("accounting underflow resets rather than wedging the guard")
    void underflowIsSelfHealing() {
        MemoryGuard guard = new MemoryGuard(100 * MB, 0.85, 0.78, new FakeProbe(0.10));

        guard.tryReserve(10 * MB);
        guard.release(50 * MB); // More than was ever reserved.

        assertEquals(0, guard.reservedBytes(), "a negative reservation must not persist");
        assertTrue(guard.tryReserve(10 * MB), "and must not leave the guard permanently rejecting");
    }

    @Test
    @DisplayName("a zero-byte reservation is always allowed")
    void zeroByteReservation() {
        MemoryGuard guard = new MemoryGuard(100 * MB, 0.85, 0.78, new FakeProbe(0.99));
        assertTrue(guard.tryReserve(0));
    }

    @Test
    @DisplayName("snapshot reports both ceilings and the effective ratio")
    void snapshotShape() {
        MemoryGuard guard = new MemoryGuard(100 * MB, 0.85, 0.78, new FakeProbe(0.42));
        guard.tryReserve(25 * MB);

        MemoryPressure snapshot = guard.snapshot();
        assertEquals(25 * MB, snapshot.reservedBytes());
        assertEquals(100 * MB, snapshot.budgetBytes());
        assertEquals(0.25, snapshot.budgetRatio(), 0.001);
        assertEquals(0.42, snapshot.physicalRatio(), 0.001);
        assertFalse(snapshot.rejecting());
    }

    /**
     * Regression test for the gate-boundary oscillation seen in the 2026-09-18 soak run.
     *
     * <p>{@code effectiveRatio()} used to return two different quantities depending on whether the
     * reservation had crossed {@code physicalGateFloorBytes}: the engine budget ratio below it, the machine
     * ratio above it. The hysteresis band was applied to that single number, so crossing the floor jumped
     * the value straight from ~0.06 to ~0.85 without ever passing through 0.78-0.85 and the band never
     * engaged. Every crossing of the floor therefore flipped the guard, which is exactly the oscillation
     * the class javadoc promises not to do — the soak log shows "leaving ... at 6%" (the gate floor is
     * 6.25% of a 1 GiB budget) followed by "entering ... at 85%" one second later, eight times over.
     */
    @Test
    @DisplayName("crossing the physical gate floor repeatedly does not toggle the guard every time")
    void gateFloorCrossingsDoNotOscillate() {
        FakeProbe probe = new FakeProbe(0.90); // A host that reads "under pressure" continuously.
        MemoryGuard guard = new MemoryGuard(1024 * MB, 0.85, 0.78, probe);

        long floor = guard.physicalGateFloorBytes();
        int transitions = 0;
        boolean previous = guard.isRejecting();

        // Drive the reservation back and forth across the gate floor, the way the eviction sweeper and
        // the write path do when the engine is pinned at the boundary.
        for (int i = 0; i < 200; i++) {
            guard.release(guard.reservedBytes());
            guard.tryReserve(floor + MB); // Above the floor: machine pressure now applies.
            boolean above = guard.isRejecting();
            if (above != previous) {
                transitions++;
                previous = above;
            }

            guard.release(guard.reservedBytes()); // Below the floor: we are no longer part of the problem.
            boolean below = guard.isRejecting();
            if (below != previous) {
                transitions++;
                previous = below;
            }
        }

        // The bound is generous on purpose: the gate re-arm dwell allows two flips per window, so a slow
        // or heavily loaded CI box may legitimately see a handful. The defect being guarded against
        // produces one flip per crossing - 400 of them - so the margin costs nothing in sensitivity.
        assertTrue(transitions <= 20,
                "the guard flipped " + transitions + " times across 200 gate-floor crossings; hysteresis "
                        + "must apply to the gate decision itself, not only to the ratio it selects");
    }

    /**
     * The gate hysteresis must not cost us the deliberate exemption it sits on top of: an engine holding
     * a trivial amount still has to keep serving writes on a host that merely reads busy.
     */
    @Test
    @DisplayName("an engine below the gate floor still accepts writes under machine pressure")
    void belowTheFloorStillAcceptsUnderMachinePressure() {
        FakeProbe probe = new FakeProbe(0.99);
        MemoryGuard guard = new MemoryGuard(1024 * MB, 0.85, 0.78, probe);

        long floor = guard.physicalGateFloorBytes();
        guard.tryReserve(floor + MB);
        assertTrue(guard.isRejecting(), "above the floor the host reading applies");

        guard.release(guard.reservedBytes());
        assertFalse(guard.isRejecting(), "having shed its memory the engine is no longer the cause");
        assertTrue(guard.tryReserve(MB), "and must be allowed to refill");
    }

    /** The budget ceiling is measured against the budget, and is unaffected by the gate's own hysteresis. */
    @Test
    @DisplayName("budget-driven rejection still clears only below the relief ratio")
    void budgetRejectionKeepsItsOwnHysteresis() {
        MemoryGuard guard = new MemoryGuard(1024 * MB, 0.85, 0.78, new FakeProbe(0.10));

        guard.tryReserve(900 * MB);
        assertTrue(guard.isRejecting(), "88% of the budget is past the reject ratio");

        guard.release(60 * MB); // 840 MB - 82%, still above relief.
        assertTrue(guard.isRejecting());

        guard.release(100 * MB); // 740 MB - 72%, below relief.
        assertFalse(guard.isRejecting());
    }

    @Test
    @DisplayName("rejects an invalid configuration at construction")
    void validatesConfiguration() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MemoryGuard(0, 0.85));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MemoryGuard(MB, 1.5));
    }
}
