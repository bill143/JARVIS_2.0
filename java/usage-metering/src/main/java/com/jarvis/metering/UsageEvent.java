package com.jarvis.metering;

import java.time.Instant;
import java.util.Objects;

/**
 * One metered LLM call: when it happened, which provider/model, how many tokens each way, and the
 * estimated cost. Provider-agnostic — Claude is the only active provider today, but the shape holds
 * for any.
 *
 * @param at when the call completed
 * @param provider the provider name (e.g. {@code "anthropic"})
 * @param model the model used
 * @param inputTokens prompt tokens
 * @param outputTokens completion tokens
 * @param costUsd estimated cost from the price table
 */
public record UsageEvent(Instant at, String provider, String model, long inputTokens,
        long outputTokens, double costUsd) {

    public UsageEvent {
        Objects.requireNonNull(at, "at");
        provider = provider == null ? "" : provider;
        model = model == null ? "" : model;
    }
}
