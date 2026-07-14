package com.jarvis.solicitations;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/**
 * Production {@link SamGovTransport} over the JDK {@link HttpClient} (whitelist-clean — no SDK).
 *
 * <p><b>Dormant by default.</b> Without an API key {@link #available()} is {@code false} and the
 * transport refuses to touch the network. The key is read from {@code SAMGOV_API_KEY} by name, held
 * only in a private field, and <b>never</b> logged or placed in an exception/URL echoed to the user.
 * SAM requires a {@code postedFrom}/{@code postedTo} window, which this transport fills (default: the
 * last {@value #DEFAULT_LOOKBACK_DAYS} days) so the adapter can stay clock-free. Transient failures
 * (429/5xx/IO) are retried with exponential backoff.
 */
public final class HttpSamGovTransport implements SamGovTransport {

    /** Environment variable holding the SAM.gov API key (referenced by name only). */
    public static final String KEY_ENV = "SAMGOV_API_KEY";
    /** Optional override for the API base (referenced by name only). */
    public static final String BASE_ENV = "SAMGOV_BASE_URL";

    private static final String DEFAULT_BASE = "https://api.sam.gov/opportunities/v2/search";
    private static final int DEFAULT_LOOKBACK_DAYS = 365;
    private static final int MAX_RETRIES = 4;
    private static final DateTimeFormatter SAM_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final String apiKey;   // nullable → dormant; never logged
    private final String base;
    private final HttpClient client;
    private final int lookbackDays;

    public HttpSamGovTransport(String apiKey, String base) {
        this(apiKey, base, DEFAULT_LOOKBACK_DAYS);
    }

    HttpSamGovTransport(String apiKey, String base, int lookbackDays) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.base = base == null || base.isBlank() ? DEFAULT_BASE : base.strip();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        this.lookbackDays = lookbackDays;
    }

    /** Reads {@code SAMGOV_API_KEY} / {@code SAMGOV_BASE_URL} (unset key → dormant). */
    public static HttpSamGovTransport fromEnvironment() {
        return new HttpSamGovTransport(System.getenv(KEY_ENV), System.getenv(BASE_ENV));
    }

    @Override
    public boolean available() {
        return apiKey != null;
    }

    @Override
    public String search(Map<String, String> params) throws IOException, InterruptedException {
        if (!available()) {
            throw new IOException("SAM.gov is not configured — set the " + KEY_ENV
                    + " environment variable to an api.sam.gov key");
        }
        Map<String, String> query = new TreeMap<>(params == null ? Map.of() : params);
        query.putIfAbsent("limit", "50");
        query.putIfAbsent("postedFrom", LocalDate.now().minusDays(lookbackDays).format(SAM_DATE));
        query.putIfAbsent("postedTo", LocalDate.now().format(SAM_DATE));
        query.put("api_key", apiKey);   // added last; never logged

        StringBuilder url = new StringBuilder(base).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) {
                url.append('&');
            }
            first = false;
            url.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }

        IOException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(60)).GET().build();
                HttpResponse<String> resp =
                        client.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code >= 200 && code < 300) {
                    return resp.body();
                }
                if (code == 429 || code >= 500) {
                    last = new IOException("SAM.gov returned HTTP " + code);   // no URL (has key)
                    backoff(attempt);
                    continue;
                }
                throw new IOException("SAM.gov returned HTTP " + code);
            } catch (IOException e) {
                last = e;
                backoff(attempt);
            }
        }
        throw last != null ? last : new IOException("SAM.gov request failed");
    }

    private static void backoff(int attempt) throws InterruptedException {
        Thread.sleep((long) Math.pow(2, attempt) * 250L);   // 250ms, 500, 1s, 2s
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
