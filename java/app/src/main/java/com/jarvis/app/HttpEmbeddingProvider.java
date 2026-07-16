package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.rag.EmbeddingProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cloud {@link EmbeddingProvider} over an OpenAI/Voyage-style embeddings endpoint, using only
 * whitelisted machinery: the JDK's {@link HttpClient} for transport and Jackson for JSON.
 *
 * <p><b>Dormant by default (decision D3).</b> Constructed without an API key, {@link #available()}
 * returns {@code false} and the provider never touches the network — {@link SemanticMemory} then
 * answers by keyword instead. Semantic recall lights up only when a key is deliberately supplied,
 * so no embedding traffic and no cost occur until the user opts in.
 */
final class HttpEmbeddingProvider implements EmbeddingProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/embeddings";
    private static final String DEFAULT_MODEL = "text-embedding-3-small";

    private final java.util.function.Supplier<String> keySupplier;       // resolved live; null → dormant
    private final java.util.function.Supplier<String> endpointSupplier;  // resolved live
    private final java.util.function.Supplier<String> modelSupplier;     // resolved live
    private final HttpClient client;

    HttpEmbeddingProvider(String apiKey, String endpoint, String model) {
        this((java.util.function.Supplier<String>) () -> apiKey,
                (java.util.function.Supplier<String>) () -> endpoint,
                (java.util.function.Supplier<String>) () -> model);
    }

    private HttpEmbeddingProvider(java.util.function.Supplier<String> keySupplier,
            java.util.function.Supplier<String> endpointSupplier,
            java.util.function.Supplier<String> modelSupplier) {
        this.keySupplier = keySupplier == null ? () -> null : keySupplier;
        this.endpointSupplier = endpointSupplier == null ? () -> null : endpointSupplier;
        this.modelSupplier = modelSupplier == null ? () -> null : modelSupplier;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /**
     * Live-resolving provider: the key, endpoint and model are read from the suppliers on every
     * request, so an in-app configuration change takes effect without a restart.
     */
    static HttpEmbeddingProvider resolving(java.util.function.Supplier<String> keySupplier,
            java.util.function.Supplier<String> endpointSupplier,
            java.util.function.Supplier<String> modelSupplier) {
        return new HttpEmbeddingProvider(keySupplier, endpointSupplier, modelSupplier);
    }

    /** Reads the key from {@code JARVIS_EMBEDDINGS_KEY} (unset → dormant). */
    static HttpEmbeddingProvider fromEnvironment() {
        return new HttpEmbeddingProvider(
                System.getenv("JARVIS_EMBEDDINGS_KEY"),
                System.getenv("JARVIS_EMBEDDINGS_ENDPOINT"),
                System.getenv("JARVIS_EMBEDDINGS_MODEL"));
    }

    private String apiKey() {
        String k = keySupplier.get();
        return k == null || k.isBlank() ? null : k;
    }

    private String endpoint() {
        String e = endpointSupplier.get();
        return e == null || e.isBlank() ? DEFAULT_ENDPOINT : e;
    }

    private String model() {
        String m = modelSupplier.get();
        return m == null || m.isBlank() ? DEFAULT_MODEL : m;
    }

    @Override
    public boolean available() {
        return apiKey() != null;
    }

    @Override
    public float[] embed(String text) throws IOException, InterruptedException {
        String apiKey = apiKey();
        if (apiKey == null) {
            throw new IllegalStateException("embeddings are dormant (no API key wired)");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model());
        body.put("input", text == null ? "" : text);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                .header("Authorization", "Bearer " + apiKey)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("embeddings API returned " + response.statusCode()
                    + ": " + response.body());
        }
        return parse(response.body());
    }

    /** Parses {@code {"data":[{"embedding":[...]}]}} into a float vector; exposed for tests. */
    static float[] parse(String responseJson) throws IOException {
        JsonNode embedding = MAPPER.readTree(responseJson).path("data").path(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new IOException("no embedding in response: " + responseJson);
        }
        float[] v = new float[embedding.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) embedding.get(i).asDouble();
        }
        return v;
    }
}
