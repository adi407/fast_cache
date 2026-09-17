package io.fastcache.engine.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cost model behind the console.
 *
 * <p>These numbers get shown to people in dollars, so the arithmetic identity
 * {@code projected - actual == saved} has to hold exactly rather than approximately — a console whose
 * three figures do not add up is a console nobody trusts again.
 */
class SavingsLedgerTest {

    @Test
    @DisplayName("tokens are characters divided by four")
    void tokenEstimate() {
        assertEquals(250_000.0, CostProfile.GPT_4O.tokensFor(1_000_000), 0.001);
    }

    @Test
    @DisplayName("published input rates")
    void builtInProfiles() {
        assertEquals(2.50, CostProfile.GPT_4O.usdPerMillionInputTokens(), 1e-9);
        assertEquals(3.00, CostProfile.CLAUDE_35_SONNET.usdPerMillionInputTokens(), 1e-9);
        assertEquals(2, CostProfile.BUILT_INS.size());
    }

    @Test
    @DisplayName("one million characters of GPT-4o input costs 62.5 cents")
    void costArithmetic() {
        // 1,000,000 chars / 4 = 250,000 tokens; 250,000 / 1e6 * $2.50 = $0.625
        assertEquals(0.625, CostProfile.GPT_4O.usdFor(1_000_000), 1e-9);
    }

    @Test
    @DisplayName("projected minus actual equals saved, exactly")
    void savingsIdentity() {
        SavingsLedger ledger = new SavingsLedger();
        ledger.recordStore(400_000);   // cost genuinely paid
        ledger.recordServe(1_600_000); // cost avoided

        SavingsLedger.Savings savings = ledger.computeSavings(CostProfile.GPT_4O);

        assertEquals(400_000, savings.charactersPaid());
        assertEquals(1_600_000, savings.charactersAvoided());
        assertEquals(savings.savedUsd(), savings.projectedCostUsd() - savings.actualCostUsd(), 1e-9);
        assertEquals(0.80, savings.savingsRatio(), 0.001, "4 of every 5 characters came from cache");
    }

    @Test
    @DisplayName("switching model re-prices the same traffic proportionally")
    void modelSwitchIsProportional() {
        SavingsLedger ledger = new SavingsLedger();
        ledger.recordStore(100_000);
        ledger.recordServe(900_000);

        SavingsLedger.Savings gpt = ledger.computeSavings(CostProfile.GPT_4O);
        SavingsLedger.Savings claude = ledger.computeSavings(CostProfile.CLAUDE_35_SONNET);

        assertEquals(gpt.tokensAvoided(), claude.tokensAvoided(), 0.001, "token count is model-agnostic");
        assertEquals(3.00 / 2.50, claude.savedUsd() / gpt.savedUsd(), 0.001);
    }

    @Test
    @DisplayName("non-text values contribute no tokens")
    void nonTextIsNotCounted() {
        SavingsLedger ledger = new SavingsLedger();
        ledger.recordStore(0);  // e.g. a numpy buffer
        ledger.recordServe(0);

        SavingsLedger.Savings savings = ledger.computeSavings(CostProfile.GPT_4O);
        assertEquals(0.0, savings.savedUsd(), 1e-9);
        assertEquals(0.0, savings.savingsRatio(), 1e-9, "no traffic must not divide by zero");
        assertEquals(1, ledger.valuesStored());
        assertEquals(1, ledger.valuesServed());
    }

    @Test
    @DisplayName("client L1 telemetry counts toward avoided cost")
    void l1TelemetryCounts() {
        SavingsLedger ledger = new SavingsLedger();
        ledger.recordStore(1_000);
        ledger.reportClient(1L, 500, 250_000);

        assertEquals(500, ledger.l1Hits());
        assertEquals(250_000, ledger.l1Characters());
        assertEquals(250_000, ledger.charactersAvoided(),
                "L1 hits never cross the socket, so the heartbeat is the only way they can be counted");
        assertEquals(1, ledger.connectedClients());
    }

    @Test
    @DisplayName("a heartbeat replaces that client's previous report rather than accumulating")
    void heartbeatsAreCumulativeNotIncremental() {
        SavingsLedger ledger = new SavingsLedger();

        ledger.reportClient(1L, 100, 1_000);
        ledger.reportClient(1L, 250, 2_500); // Same session, a later beat.

        assertEquals(250, ledger.l1Hits(),
                "clients report running totals; summing deltas would double-count");
        assertEquals(2_500, ledger.l1Characters());
    }

    @Test
    @DisplayName("a disconnecting client's totals are retained, not lost")
    void retiredClientsStillCount() {
        SavingsLedger ledger = new SavingsLedger();
        ledger.reportClient(1L, 100, 5_000);
        ledger.reportClient(2L, 200, 7_000);

        ledger.retireClient(1L);

        assertEquals(1, ledger.connectedClients());
        assertEquals(300, ledger.l1Hits(), "the savings figure must never go backwards on a disconnect");
        assertEquals(12_000, ledger.l1Characters());
    }

    @Test
    @DisplayName("a custom profile can express a negotiated rate")
    void customProfile() {
        CostProfile custom = CostProfile.parse("in-house:1.25");
        assertEquals(1.25, custom.usdPerMillionInputTokens(), 1e-9);
        assertEquals("in-house", custom.id());
    }

    @Test
    @DisplayName("an unparseable profile falls back to a built-in rather than failing")
    void malformedProfileFallsBack() {
        assertEquals(CostProfile.GPT_4O.id(), CostProfile.parse("").id());
        assertEquals(CostProfile.GPT_4O.id(), CostProfile.parse("nonsense:not-a-number").id());
        assertEquals(CostProfile.CLAUDE_35_SONNET.id(), CostProfile.parse("claude-3-5-sonnet").id());
    }

    @Test
    @DisplayName("reset clears the ledger")
    void reset() {
        SavingsLedger ledger = new SavingsLedger();
        ledger.recordServe(1_000);
        ledger.reportClient(1L, 10, 100);

        ledger.reset();

        assertEquals(0, ledger.charactersAvoided());
        assertEquals(0, ledger.connectedClients());
        assertTrue(ledger.computeSavings(CostProfile.GPT_4O).savedUsd() == 0.0);
    }
}
