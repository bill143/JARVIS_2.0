package com.jarvis.metering;

/**
 * Aggregate totals across all metered calls: the numbers the HUD widget shows.
 *
 * @param calls number of metered calls
 * @param inputTokens total prompt tokens
 * @param outputTokens total completion tokens
 * @param costUsd total estimated cost
 */
public record UsageSummary(long calls, long inputTokens, long outputTokens, double costUsd) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
