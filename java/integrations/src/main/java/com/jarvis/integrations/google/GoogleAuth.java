package com.jarvis.integrations.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.memory.MemoryStore;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Google OAuth 2.0 for an installed (desktop) app: builds the consent URL, exchanges the auth code
 * for tokens, and refreshes access tokens on demand. The refresh token is persisted in the durable
 * {@link MemoryStore} (scope {@code google}) so the connection survives restarts.
 *
 * <p>Raw REST over the JDK {@link HttpClient} + Jackson — no Google SDK, so the dependency
 * whitelist is unchanged. The token HTTP call is a seam so the logic is unit-testable offline.
 */
public final class GoogleAuth {

    /** Seam for the token endpoint (form-encoded POST -> JSON). */
    @FunctionalInterface
    public interface TokenHttp {
        String postForm(String url, Map<String, String> form) throws IOException, InterruptedException;
    }

    static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    /** Read/modify mail (send + read) and full calendar access. */
    static final String SCOPES = "https://www.googleapis.com/auth/gmail.modify"
            + " https://www.googleapis.com/auth/calendar";
    private static final String SCOPE = "google";
    private static final String REFRESH_KEY = "refresh_token";

    private final String clientId;
    private final String clientSecret;
    private final MemoryStore<String> store;
    private final TokenHttp http;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String cachedAccess;
    private volatile long cachedExpiryEpochSec;

    public GoogleAuth(String clientId, String clientSecret, MemoryStore<String> store) {
        this(clientId, clientSecret, store, defaultHttp());
    }

    public GoogleAuth(String clientId, String clientSecret, MemoryStore<String> store, TokenHttp http) {
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.store = Objects.requireNonNull(store, "store");
        this.http = Objects.requireNonNull(http, "http");
    }

    /** Whether a refresh token is stored (i.e. the user has completed the consent flow). */
    public boolean isConnected() {
        return store.get(SCOPE, REFRESH_KEY).isPresent();
    }

    /** Consent URL to open in the browser; {@code redirectUri} is the loopback address. */
    public String authUrl(String redirectUri) {
        return AUTH_URL
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&access_type=offline"
                + "&prompt=consent"
                + "&scope=" + enc(SCOPES);
    }

    /** Exchanges an authorization {@code code} for tokens and persists the refresh token. */
    public void exchangeCode(String code, String redirectUri)
            throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("redirect_uri", redirectUri);
        form.put("grant_type", "authorization_code");
        JsonNode json = mapper.readTree(http.postForm(TOKEN_URL, form));
        String refresh = json.path("refresh_token").asText(null);
        if (refresh == null || refresh.isBlank()) {
            throw new IOException("Google did not return a refresh token: " + json);
        }
        store.put(SCOPE, REFRESH_KEY, refresh);
        cacheAccess(json);
    }

    /** Returns a valid access token, refreshing via the stored refresh token when needed. */
    public synchronized String accessToken() throws IOException, InterruptedException {
        long now = nowEpochSec();
        if (cachedAccess != null && now < cachedExpiryEpochSec - 60) {
            return cachedAccess;
        }
        String refresh = store.get(SCOPE, REFRESH_KEY)
                .orElseThrow(() -> new IOException("not connected to Google - run setup first"))
                .value();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("refresh_token", refresh);
        form.put("grant_type", "refresh_token");
        JsonNode json = mapper.readTree(http.postForm(TOKEN_URL, form));
        if (!json.hasNonNull("access_token")) {
            throw new IOException("token refresh failed: " + json);
        }
        cacheAccess(json);
        return cachedAccess;
    }

    /** Disconnects by forgetting the stored refresh token. */
    public void disconnect() {
        store.delete(SCOPE, REFRESH_KEY);
        cachedAccess = null;
        cachedExpiryEpochSec = 0;
    }

    private void cacheAccess(JsonNode tokenJson) {
        if (tokenJson.hasNonNull("access_token")) {
            cachedAccess = tokenJson.get("access_token").asText();
            cachedExpiryEpochSec = nowEpochSec() + tokenJson.path("expires_in").asLong(3600);
        }
    }

    long nowEpochSec() {
        return System.currentTimeMillis() / 1000;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static TokenHttp defaultHttp() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        return (url, form) -> {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (body.length() > 0) {
                    body.append('&');
                }
                body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Google token endpoint " + response.statusCode()
                        + ": " + response.body());
            }
            return response.body();
        };
    }
}
