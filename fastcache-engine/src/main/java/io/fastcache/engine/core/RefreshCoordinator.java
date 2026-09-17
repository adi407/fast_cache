package io.fastcache.engine.core;

import io.fastcache.engine.util.FastCacheLog;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Single-flight leader election, per key. The defence against a thundering herd.
 *
 * <h2>The failure this prevents</h2>
 * A 50&nbsp;MB context window expires. A thousand virtual threads are reading that key at that instant.
 * Without coordination, all thousand miss, all thousand call the LLM pipeline, and the backend that was
 * comfortably serving one request per fifteen minutes takes a thousand simultaneous 50&nbsp;MB generations.
 * The cache did not merely fail to help — it converted a steady load into a synchronised spike, which is a
 * worse failure than having no cache at all.
 *
 * <h2>The mechanism</h2>
 * Exactly one caller wins the refresh lease for a key via an atomic {@code putIfAbsent}. Everyone else
 * either receives the stale value immediately (when the entry is inside its grace window) or parks on a
 * latch until the winner publishes, bounded by the grace window.
 *
 * <h2>Why a lease and not just a lock</h2>
 * The winner can crash, hang on a slow model call, or be a <em>different process</em> that got SIGKILLed.
 * A plain lock would leave the key permanently blocked. Each flight therefore carries a deadline: once
 * {@code refreshLeaseMillis} elapses, the next caller steals the lease and retries. A stuck leader costs
 * one lease period, not an outage.
 *
 * <h2>Virtual-thread safety</h2>
 * Waiters park on a {@link CountDownLatch}, which unmounts a virtual thread rather than pinning its
 * carrier. No {@code synchronized} anywhere: a thousand threads parked inside monitors waiting on a slow
 * LLM call would pin a thousand carriers and take the whole scheduler down with them.
 */
public final class RefreshCoordinator {

    private static final FastCacheLog LOG = FastCacheLog.of(RefreshCoordinator.class);

    /** One in-progress refresh. {@code deadlineMillis} is what makes a crashed leader recoverable. */
    private record Flight(CountDownLatch completion, long deadlineMillis, long startedAtMillis) {

        boolean isExpired(long nowMillis) {
            return nowMillis >= deadlineMillis;
        }
    }

    private final ConcurrentHashMap<String, Flight> flights = new ConcurrentHashMap<>();
    private final long leaseMillis;

    private final LongAdder leadsGranted = new LongAdder();
    private final LongAdder herdSuppressed = new LongAdder();
    private final LongAdder leasesStolen = new LongAdder();
    private final LongAdder waitTimeouts = new LongAdder();

    public RefreshCoordinator(long leaseMillis) {
        this.leaseMillis = leaseMillis;
    }

    /**
     * Attempts to become the single refresher for {@code key}.
     *
     * @return {@code true} if this caller now owns the refresh and must call {@link #complete(String)} in a
     *         finally block; {@code false} if someone else is already refreshing
     */
    public boolean tryAcquireLead(String key) {
        long now = System.currentTimeMillis();
        Flight fresh = new Flight(new CountDownLatch(1), now + leaseMillis, now);

        while (true) {
            Flight existing = flights.putIfAbsent(key, fresh);
            if (existing == null) {
                leadsGranted.increment();
                return true;
            }
            if (!existing.isExpired(now)) {
                herdSuppressed.increment();
                return false;
            }
            // The incumbent leader blew its lease — crashed, hung, or was killed. Steal it, but only via
            // an atomic replace so that exactly one of several waiting callers takes over.
            if (flights.replace(key, existing, fresh)) {
                existing.completion().countDown(); // Release anyone parked on the dead flight.
                leasesStolen.increment();
                leadsGranted.increment();
                LOG.warn("Refresh lease for key {0} expired after {1}ms and was stolen; "
                                + "the previous refresher never completed.",
                        key, now - existing.startedAtMillis());
                return true;
            }
            Thread.onSpinWait(); // Lost the steal race; re-read and re-evaluate.
        }
    }

    /**
     * Publishes completion and wakes every waiter. Must be called by the lease holder in a finally block —
     * skipping it on an exception path would make every other caller wait out the full lease.
     */
    public void complete(String key) {
        Flight finished = flights.remove(key);
        if (finished != null) {
            finished.completion().countDown();
        }
    }

    /**
     * Parks until the current refresh for {@code key} finishes.
     *
     * @return {@code true} if the refresh completed within the budget; {@code false} on timeout or if no
     *         refresh was in flight (in which case the caller should simply re-read the cache)
     */
    public boolean awaitCompletion(String key, long timeoutMillis) {
        Flight flight = flights.get(key);
        if (flight == null) {
            return false; // Already finished between our read and this call: re-read and you will hit.
        }
        try {
            // Never wait past the lease: a caller must not block longer than the leader is allowed to hold.
            long budget = Math.min(timeoutMillis, Math.max(0L, flight.deadlineMillis() - System.currentTimeMillis()));
            boolean completed = flight.completion().await(budget, TimeUnit.MILLISECONDS);
            if (!completed) {
                waitTimeouts.increment();
            }
            return completed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isRefreshing(String key) {
        Flight flight = flights.get(key);
        return flight != null && !flight.isExpired(System.currentTimeMillis());
    }

    public int inFlightCount() {
        return flights.size();
    }

    /** Refresh leases granted. One per genuine recompute. */
    public long leadsGranted() {
        return leadsGranted.sum();
    }

    /**
     * Calls that would have been a duplicate recompute and were not. This is the headline number: it is
     * exactly how many redundant LLM invocations the stampede defence prevented.
     */
    public long herdSuppressed() {
        return herdSuppressed.sum();
    }

    public long leasesStolen() {
        return leasesStolen.sum();
    }

    public long waitTimeouts() {
        return waitTimeouts.sum();
    }

    /** Drops every in-flight record, releasing waiters. Called on engine shutdown. */
    public void clear() {
        flights.values().forEach(flight -> flight.completion().countDown());
        flights.clear();
    }
}
