package com.jarvis.integrations.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The production {@link GitHubTransport}: talks to the GitHub REST API over the JDK's
 * {@link HttpClient} (whitelist-clean — no third-party SDK). Authenticates with a fine-grained
 * Personal Access Token.
 *
 * <p><b>Dormant by default.</b> Constructed without a token, {@link #available()} is {@code false}
 * and the transport refuses to touch the network — the GitHub tools then return a graceful "not
 * configured" error instead of failing mid-call. The token is read from an environment variable by
 * name and held only in a private field; it is <b>never</b> logged, echoed, or placed in an
 * exception message.
 */
public final class HttpGitHubTransport implements GitHubTransport {

    /** Environment variable that supplies the fine-grained PAT (referenced by name only). */
    public static final String TOKEN_ENV = "JARVIS_GITHUB_TOKEN";

    private static final String API_BASE = "https://api.github.com";
    private static final String API_VERSION = "2022-11-28";

    private final java.util.function.Supplier<String> tokenSupplier;   // resolved live; null → dormant
    private final HttpClient client;

    /** Creates a transport authenticated with {@code token} (null/blank → dormant). */
    public HttpGitHubTransport(String token) {
        this((java.util.function.Supplier<String>) () -> token);
    }

    private HttpGitHubTransport(java.util.function.Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier == null ? () -> null : tokenSupplier;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /**
     * Live-resolving transport: the token is read from the supplier on every request, so an in-app
     * configuration change takes effect without a restart.
     */
    public static HttpGitHubTransport resolving(java.util.function.Supplier<String> tokenSupplier) {
        return new HttpGitHubTransport(tokenSupplier);
    }

    /** Reads the PAT from {@code JARVIS_GITHUB_TOKEN} (unset → dormant). */
    public static HttpGitHubTransport fromEnvironment() {
        return new HttpGitHubTransport(System.getenv(TOKEN_ENV));
    }

    private String token() {
        String t = tokenSupplier.get();
        return t == null || t.isBlank() ? null : t;
    }

    @Override
    public boolean available() {
        return token() != null;
    }

    @Override
    public GitHubResponse send(String method, String path, String jsonBody)
            throws IOException, InterruptedException {
        String token = token();
        if (token == null) {
            throw new IOException("GitHub is not configured — set the " + TOKEN_ENV
                    + " environment variable to a fine-grained Personal Access Token");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(API_BASE + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "JARVIS")
                .timeout(Duration.ofSeconds(60));
        HttpRequest.BodyPublisher pub = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }
        HttpResponse<String> response =
                client.send(builder.method(method, pub).build(), HttpResponse.BodyHandlers.ofString());
        return new GitHubResponse(response.statusCode(), response.body());
    }
}
