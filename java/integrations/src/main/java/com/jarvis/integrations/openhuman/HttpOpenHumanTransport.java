package com.jarvis.integrations.openhuman;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Production {@link OpenHumanTransport}: talks to a locally-running {@code openhuman-core serve}
 * over the JDK {@link HttpClient} (whitelist-clean — no third-party SDK), authenticated with the
 * core's per-launch bearer token.
 *
 * <p><b>Dormant by default.</b> Without a base URL <em>and</em> a token, {@link #available()} is
 * {@code false} and the transport never touches the network — the OpenHuman tools then report "not
 * configured" rather than failing. The token is held only in a private field and is never logged,
 * echoed, or placed in an exception message.
 *
 * <p>Config (referenced by name only): {@code JARVIS_OPENHUMAN_URL} (e.g. {@code
 * http://127.0.0.1:8765}) and the token from {@code OPENHUMAN_CORE_TOKEN} (OpenHuman's own variable)
 * or {@code JARVIS_OPENHUMAN_TOKEN}.
 */
public final class HttpOpenHumanTransport implements OpenHumanTransport {

    public static final String URL_ENV = "JARVIS_OPENHUMAN_URL";
    public static final String TOKEN_ENV = "OPENHUMAN_CORE_TOKEN";
    public static final String TOKEN_ENV_ALT = "JARVIS_OPENHUMAN_TOKEN";

    private final String baseUrl;   // nullable → dormant
    private final String token;     // nullable → dormant; never logged
    private final HttpClient client;

    public HttpOpenHumanTransport(String baseUrl, String token) {
        this.baseUrl = strip(baseUrl);
        this.token = token == null || token.isBlank() ? null : token;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Reads the base URL and token from the environment (unset → dormant). */
    public static HttpOpenHumanTransport fromEnvironment() {
        String token = System.getenv(TOKEN_ENV);
        if (token == null || token.isBlank()) {
            token = System.getenv(TOKEN_ENV_ALT);
        }
        return new HttpOpenHumanTransport(System.getenv(URL_ENV), token);
    }

    @Override
    public boolean available() {
        return baseUrl != null && token != null;
    }

    @Override
    public OpenHumanResponse send(String method, String path, String jsonBody)
            throws IOException, InterruptedException {
        if (!available()) {
            throw new IOException("OpenHuman is not configured — run 'openhuman-core serve', then set "
                    + URL_ENV + " and " + TOKEN_ENV);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60));
        HttpRequest.BodyPublisher pub = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }
        HttpResponse<String> response =
                client.send(builder.method(method, pub).build(), HttpResponse.BodyHandlers.ofString());
        return new OpenHumanResponse(response.statusCode(), response.body());
    }

    private static String strip(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.strip();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
