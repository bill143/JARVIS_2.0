package com.jarvis.integrations.github;

import java.io.IOException;

/**
 * Transport seam for the GitHub REST API: given an HTTP method, a path relative to the API base
 * ({@code https://api.github.com}), and an optional JSON body, it returns the raw
 * {@link GitHubResponse}. This is the single boundary where the network lives, mirroring the
 * {@code AnthropicPolicy.LlmTransport} pattern — so {@link GitHubClient} logic is unit-tested with a
 * fake transport (no token, no network) and the real {@link HttpGitHubTransport} is swapped in for
 * production.
 */
@FunctionalInterface
public interface GitHubTransport {

    /**
     * Sends a request to the GitHub API.
     *
     * @param method HTTP method ({@code GET}, {@code POST}, {@code PUT}, {@code DELETE}, …)
     * @param path path beginning with {@code /}, relative to the API base, query string included
     * @param jsonBody request body JSON, or {@code null} for a body-less request
     */
    GitHubResponse send(String method, String path, String jsonBody)
            throws IOException, InterruptedException;

    /** Whether this transport is configured to make live calls (a token is present). */
    default boolean available() {
        return true;
    }
}
