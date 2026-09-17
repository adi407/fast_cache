package io.fastcache.engine.metrics;

import java.util.List;
import java.util.Locale;

/**
 * A model's input-token price, used to put a dollar figure on what the cache avoided.
 *
 * @param id                        stable identifier used in the API and the dashboard selector
 * @param displayName               human label
 * @param usdPerMillionInputTokens  published input-token price
 * @param charactersPerToken        divisor for the character-to-token estimate
 */
public record CostProfile(String id, String displayName, double usdPerMillionInputTokens,
                          double charactersPerToken) {

    /**
     * The industry rule of thumb: ~4 characters per token for English prose. It is an estimate, not a
     * tokenizer — running a real BPE tokenizer over every cached value would cost more CPU than the cache
     * saves, and would still be wrong for the other model. Expect roughly ±15% against a real tokenizer on
     * English text, more on code or CJK.
     */
    public static final double DEFAULT_CHARACTERS_PER_TOKEN = 4.0;

    public CostProfile {
        if (usdPerMillionInputTokens < 0) {
            throw new IllegalArgumentException("price must not be negative: " + usdPerMillionInputTokens);
        }
        if (charactersPerToken <= 0) {
            throw new IllegalArgumentException("charactersPerToken must be positive: " + charactersPerToken);
        }
    }

    public static final CostProfile GPT_4O =
            new CostProfile("gpt-4o", "GPT-4o", 2.50, DEFAULT_CHARACTERS_PER_TOKEN);

    public static final CostProfile CLAUDE_35_SONNET =
            new CostProfile("claude-3-5-sonnet", "Claude 3.5 Sonnet", 3.00, DEFAULT_CHARACTERS_PER_TOKEN);

    /** Built-in tiers offered by the console. */
    public static final List<CostProfile> BUILT_INS = List.of(GPT_4O, CLAUDE_35_SONNET);

    public static CostProfile byId(String id) {
        if (id != null) {
            for (CostProfile profile : BUILT_INS) {
                if (profile.id().equalsIgnoreCase(id)) {
                    return profile;
                }
            }
        }
        return GPT_4O;
    }

    /**
     * Parses a custom profile from {@code name:usdPerMillionTokens}, e.g. {@code "our-model:1.75"}, so a
     * team on a negotiated rate or a self-hosted cost model is not stuck with the built-ins.
     */
    public static CostProfile parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return GPT_4O;
        }
        int separator = spec.lastIndexOf(':');
        if (separator <= 0) {
            return byId(spec.trim());
        }
        String name = spec.substring(0, separator).trim();
        try {
            double price = Double.parseDouble(spec.substring(separator + 1).trim());
            return new CostProfile(name.toLowerCase(Locale.ROOT), name, price, DEFAULT_CHARACTERS_PER_TOKEN);
        } catch (NumberFormatException e) {
            return byId(name);
        }
    }

    /** Converts a raw character count into estimated input tokens. */
    public double tokensFor(long characters) {
        return characters / charactersPerToken;
    }

    /** Converts a raw character count into the dollars those tokens would have cost. */
    public double usdFor(long characters) {
        return tokensFor(characters) / 1_000_000.0 * usdPerMillionInputTokens;
    }
}
