package com.jarvis.metering;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A local, editable price table: dollars per million input/output tokens, keyed by model. Cost is
 * computed provider-agnostically as {@code tokens / 1e6 * rate}. The figures are an approximate
 * local estimate the owner can adjust — not a billing source of truth.
 */
public final class PriceTable {

    /** Dollars per million tokens. */
    public record Rate(double inputPerMillion, double outputPerMillion) {
    }

    private final Map<String, Rate> rates;
    private final Rate fallback;

    public PriceTable(Map<String, Rate> rates, Rate fallback) {
        this.rates = new HashMap<>(Objects.requireNonNull(rates, "rates"));
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /**
     * Reasonable defaults for the models JARVIS uses today. Approximate — edit as prices change.
     * Unknown models fall back to the Sonnet-class rate.
     */
    public static PriceTable defaults() {
        Map<String, Rate> rates = new HashMap<>();
        rates.put("claude-sonnet-5", new Rate(3.00, 15.00));
        rates.put("claude-opus-4-8", new Rate(15.00, 75.00));
        rates.put("claude-haiku-4-5-20251001", new Rate(1.00, 5.00));
        return new PriceTable(rates, new Rate(3.00, 15.00));
    }

    /** The estimated USD cost of a call at {@code model} using {@code in}/{@code out} tokens. */
    public double cost(String model, long inputTokens, long outputTokens) {
        Rate rate = rates.getOrDefault(model, fallback);
        return inputTokens / 1_000_000.0 * rate.inputPerMillion()
                + outputTokens / 1_000_000.0 * rate.outputPerMillion();
    }
}
