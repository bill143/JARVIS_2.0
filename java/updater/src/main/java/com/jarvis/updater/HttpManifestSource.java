package com.jarvis.updater;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * A {@link ManifestSource} that fetches the manifest over HTTPS with the JDK's built-in
 * {@link HttpClient} (whitelist-friendly — no third-party HTTP library). Short timeouts keep the
 * startup check from stalling on a slow network.
 */
public final class HttpManifestSource {

    private HttpManifestSource() {
    }

    /** A source that GETs {@code url} (typically a static, signed JSON file). */
    public static ManifestSource of(String url) {
        Objects.requireNonNull(url, "url");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        return () -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("accept", "application/json")
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("update check interrupted", e);
            }
            if (response.statusCode() / 100 != 2) {
                throw new IOException("manifest fetch returned " + response.statusCode());
            }
            return response.body();
        };
    }
}
