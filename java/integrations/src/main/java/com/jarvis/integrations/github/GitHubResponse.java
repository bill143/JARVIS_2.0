package com.jarvis.integrations.github;

import java.util.Objects;

/**
 * A raw GitHub REST response: the HTTP status and the response body. Kept deliberately minimal so
 * the {@link GitHubTransport} seam stays trivial to fake in tests.
 *
 * @param status the HTTP status code
 * @param body the response body (JSON for the endpoints this client uses; never null)
 */
public record GitHubResponse(int status, String body) {

    public GitHubResponse {
        body = body == null ? "" : body;
    }

    /** Whether the status is 2xx. */
    public boolean ok() {
        return status / 100 == 2;
    }
}
