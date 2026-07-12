package com.jarvis.integrations.openhuman;

/**
 * A raw response from the OpenHuman core: HTTP status + body. Kept minimal so the
 * {@link OpenHumanTransport} seam is trivial to fake in tests.
 *
 * @param status the HTTP status code
 * @param body the response body (JSON for the endpoints this client uses; never null)
 */
public record OpenHumanResponse(int status, String body) {

    public OpenHumanResponse {
        body = body == null ? "" : body;
    }

    /** Whether the status is 2xx. */
    public boolean ok() {
        return status / 100 == 2;
    }
}
