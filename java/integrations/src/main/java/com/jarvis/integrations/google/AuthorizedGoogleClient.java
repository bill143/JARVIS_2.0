package com.jarvis.integrations.google;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Real {@link GoogleClient}: attaches the current OAuth access token to every request. */
public final class AuthorizedGoogleClient implements GoogleClient {

    private final GoogleAuth auth;
    private final HttpClient client;

    public AuthorizedGoogleClient(GoogleAuth auth) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String get(String url) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + auth.accessToken())
                .timeout(Duration.ofSeconds(30)).GET().build());
    }

    @Override
    public String postJson(String url, String jsonBody) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + auth.accessToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build());
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Google API " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
