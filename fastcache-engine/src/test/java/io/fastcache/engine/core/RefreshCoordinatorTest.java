package io.fastcache.engine.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single-flight leader election — the stampede defence.
 *
 * <p>The property under test is not "it usually works". It is that under a synchronised burst, the number
 * of backend invocations is exactly one. A defence that lets two calls through under load has not prevented
 * a thundering herd, it has halved it.
 */
class RefreshCoordinatorTest {

    @Test
    @DisplayName("grants the lease to exactly one caller")
    void oneLeaseAtATime() {
        RefreshCoordinator coordinator = new RefreshCoordinator(10_000);

        assertTrue(coordinator.tryAcquireLead("k"));
        assertFalse(coordinator.tryAcquireLead("k"), "a second holder would mean two backend calls");
        assertEquals(1, coordinator.herdSuppressed());

        coordinator.complete("k");
        assertTrue(coordinator.tryAcquireLead("k"), "the lease must be reusable once released");
    }

    @Test
    @DisplayName("different keys do not block each other")
    void leasesAreIndependentPerKey() {
        RefreshCoordinator coordinator = new RefreshCoordinator(10_000);

        assertTrue(coordinator.tryAcquireLead("a"));
        assertTrue(coordinator.tryAcquireLead("b"), "a busy key must not stall an unrelated one");
        assertEquals(2, coordinator.inFlightCount());
    }

    @Test
    @DisplayName("exactly one of 500 concurrent callers wins")
    void concurrentBurstElectsOneLeader() throws Exception {
        RefreshCoordinator coordinator = new RefreshCoordinator(30_000);
        int threads = 500;
        AtomicInteger leaders = new AtomicInteger();
        CyclicBarrier startLine = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        startLine.await(30, TimeUnit.SECONDS); // Release everyone at the same instant.
                        if (coordinator.tryAcquireLead("hot")) {
                            leaders.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // A barrier failure is reported by the leader count assertion below.
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
        }

        assertEquals(1, leaders.get(), "a stampede must collapse to exactly one recomputation");
        assertEquals(threads - 1L, coordinator.herdSuppressed());
    }

    @Test
    @DisplayName("an expired lease is stolen so a crashed leader cannot block a key forever")
    void expiredLeaseIsStolen() throws InterruptedException {
        RefreshCoordinator coordinator = new RefreshCoordinator(100); // Very short lease.

        assertTrue(coordinator.tryAcquireLead("k"));
        assertFalse(coordinator.tryAcquireLead("k"));

        Thread.sleep(200); // The "leader" never completes — simulating a crash or a hung model call.

        assertTrue(coordinator.tryAcquireLead("k"),
                "a leader that blew its lease must not wedge the key permanently");
        assertEquals(1, coordinator.leasesStolen());
    }

    @Test
    @DisplayName("waiters are released when the leader completes")
    void waitersWakeOnCompletion() throws Exception {
        RefreshCoordinator coordinator = new RefreshCoordinator(30_000);
        assertTrue(coordinator.tryAcquireLead("k"));

        CountDownLatch waiterFinished = new CountDownLatch(1);
        AtomicInteger completedNormally = new AtomicInteger();
        Thread waiter = Thread.ofVirtual().start(() -> {
            if (coordinator.awaitCompletion("k", 10_000)) {
                completedNormally.incrementAndGet();
            }
            waiterFinished.countDown();
        });

        Thread.sleep(100); // Let the waiter park.
        coordinator.complete("k");

        assertTrue(waiterFinished.await(10, TimeUnit.SECONDS), "waiter was never released");
        assertEquals(1, completedNormally.get());
        waiter.join();
    }

    @Test
    @DisplayName("awaiting a key with no refresh in flight returns immediately")
    void awaitingAnIdleKeyDoesNotBlock() {
        RefreshCoordinator coordinator = new RefreshCoordinator(30_000);

        long started = System.nanoTime();
        assertFalse(coordinator.awaitCompletion("never-refreshed", 5_000),
                "there is nothing to wait for; the caller should just re-read the cache");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis < 1_000, "returned after " + elapsedMillis + "ms instead of immediately");
    }

    @Test
    @DisplayName("clear releases every parked waiter, so shutdown cannot hang")
    void clearReleasesWaiters() throws Exception {
        RefreshCoordinator coordinator = new RefreshCoordinator(30_000);
        assertTrue(coordinator.tryAcquireLead("k"));

        CountDownLatch released = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            coordinator.awaitCompletion("k", 30_000);
            released.countDown();
        });

        Thread.sleep(100);
        coordinator.clear();

        assertTrue(released.await(10, TimeUnit.SECONDS),
                "an engine shutting down must not leave threads parked on a refresh");
    }
}
