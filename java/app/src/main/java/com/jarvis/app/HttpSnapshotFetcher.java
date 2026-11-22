package com.jarvis.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Production {@link MotionEventService.SnapshotFetcher}: fetches a camera snapshot over plain
 * HTTP(S) via {@link HttpClient}. Trivial by design — tests inject a fake so nothing here ever
 * touches the network in the test suite.
 */
final class HttpSnapshotFetcher implements MotionEventService.SnapshotFetcher {

    private final HttpClient httpClient;

    HttpSnapshotFetcher(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public byte[] fetch(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("snapshot fetch failed with status " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("snapshot fetch interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid snapshot url: " + url, e);
        }
    }
}
