package io.fastcache.engine.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks what the cache saved, in characters, tokens and dollars.
 *
 * <h2>The accounting model</h2>
 * Every cached value was produced by a call that cost real money. So:
 * <ul>
 *   <li><b>Characters stored</b> — text written into the cache. Each write corresponds to one backend call
 *       that <em>did</em> happen, so this is cost genuinely incurred.</li>
 *   <li><b>Characters served</b> — text read back out of the cache. Each read is a backend call that
 *       <em>did not</em> happen, so this is cost avoided.</li>
 *   <li><b>Characters short-circuited in L1</b> — reads the Python client answered from its own memory
 *       without even crossing the socket. Also avoided cost, reported by clients on their heartbeat, and
 *       tracked separately because it is the hot-key defence proving its worth.</li>
 * </ul>
 *
 * Without a cache, every one of those reads would have been a fresh call. The saving is therefore the
 * difference between {@code (stored + served + L1) } and {@code stored} alone — which is just
 * {@code served + L1}, but the console shows both totals because "you would have spent X, you spent Y" is
 * the number a finance conversation actually needs.
 *
 * <h2>What this is not</h2>
 * An estimate, and labelled as one. It assumes cached text is input-token text, uses ~4 characters per
 * token rather than a real BPE tokenizer, and ignores output tokens entirely. It is meant for
 * order-of-magnitude reasoning about whether the cache is earning its keep, not for invoicing.
 */
public final class SavingsLedger {

    private final LongAdder charactersStored = new LongAdder();
    private final LongAdder charactersServed = new LongAdder();
    private final LongAdder valuesStored = new LongAdder();
    private final LongAdder valuesServed = new LongAdder();

    /**
     * Per-session cumulative client counters. Clients report running totals on each heartbeat, so the map
     * holds the latest value per session rather than summing deltas — a dropped heartbeat then costs
     * nothing, where accumulating deltas would permanently lose a chunk of the count.
     */
    private final ConcurrentHashMap<Long, ClientTelemetry> liveClients = new ConcurrentHashMap<>();

    /** Totals folded in from sessions that have since disconnected, so the map cannot grow without bound. */
    private final LongAdder retiredL1Hits = new LongAdder();
    private final LongAdder retiredL1Characters = new LongAdder();

    /** One client's self-reported L1 activity. */
    public record ClientTelemetry(long l1Hits, long l1Characters, long reportedAtMillis) {
    }

    /** Records a value entering the cache: cost that was genuinely paid. */
    public void recordStore(int sourceCharacters) {
        valuesStored.increment();
        if (sourceCharacters > 0) {
            charactersStored.add(sourceCharacters);
        }
    }

    /** Records a value served from the cache: cost avoided. */
    public void recordServe(int sourceCharacters) {
        valuesServed.increment();
        if (sourceCharacters > 0) {
            charactersServed.add(sourceCharacters);
        }
    }

    /** Absorbs a client's heartbeat telemetry, replacing that session's previous report. */
    public void reportClient(long sessionId, long l1Hits, long l1Characters) {
        liveClients.put(sessionId, new ClientTelemetry(
                Math.max(0, l1Hits), Math.max(0, l1Characters), System.currentTimeMillis()));
    }

    /** Folds a departing session's final numbers into the retired totals and forgets the session. */
    public void retireClient(long sessionId) {
        ClientTelemetry last = liveClients.remove(sessionId);
        if (last != null) {
            retiredL1Hits.add(last.l1Hits());
            retiredL1Characters.add(last.l1Characters());
        }
    }

    public long charactersStored() {
        return charactersStored.sum();
    }

    public long charactersServed() {
        return charactersServed.sum();
    }

    public long valuesStored() {
        return valuesStored.sum();
    }

    public long valuesServed() {
        return valuesServed.sum();
    }

    public int connectedClients() {
        return liveClients.size();
    }

    /** Local L1 short-circuits across every reporting client, live and departed. */
    public long l1Hits() {
        long live = liveClients.values().stream().mapToLong(ClientTelemetry::l1Hits).sum();
        return live + retiredL1Hits.sum();
    }

    public long l1Characters() {
        long live = liveClients.values().stream().mapToLong(ClientTelemetry::l1Characters).sum();
        return live + retiredL1Characters.sum();
    }

    /** Characters that never reached a backend: engine hits plus client-side L1 short-circuits. */
    public long charactersAvoided() {
        return charactersServed() + l1Characters();
    }

    /** Immutable costing of the current ledger under one price profile. */
    public Savings computeSavings(CostProfile profile) {
        long avoided = charactersAvoided();
        long paid = charactersStored();
        return new Savings(
                profile,
                paid,
                avoided,
                profile.tokensFor(paid),
                profile.tokensFor(avoided),
                profile.usdFor(paid),
                profile.usdFor(paid + avoided),
                profile.usdFor(avoided));
    }

    /**
     * @param actualCostUsd what the backend calls that did happen cost
     * @param projectedCostUsd what every request would have cost with no cache at all
     * @param savedUsd the difference — the headline number
     */
    public record Savings(
            CostProfile profile,
            long charactersPaid,
            long charactersAvoided,
            double tokensPaid,
            double tokensAvoided,
            double actualCostUsd,
            double projectedCostUsd,
            double savedUsd) {

        /** Fraction of the no-cache bill that the cache eliminated. */
        public double savingsRatio() {
            return projectedCostUsd <= 0 ? 0.0 : savedUsd / projectedCostUsd;
        }
    }

    public void reset() {
        charactersStored.reset();
        charactersServed.reset();
        valuesStored.reset();
        valuesServed.reset();
        retiredL1Hits.reset();
        retiredL1Characters.reset();
        liveClients.clear();
    }
}
