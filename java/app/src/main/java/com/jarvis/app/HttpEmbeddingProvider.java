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

    private final String apiKey;      // nullable → dormant
    private final String endpoint;
    private final String model;
    private final HttpClient client;

    HttpEmbeddingProvider(String apiKey, String endpoint, String model) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /** Reads the key from {@code JARVIS_EMBEDDINGS_KEY} (unset → dormant). */
    static HttpEmbeddingProvider fromEnvironment() {
        return new HttpEmbeddingProvider(
                System.getenv("JARVIS_EMBEDDINGS_KEY"),
                System.getenv("JARVIS_EMBEDDINGS_ENDPOINT"),
                System.getenv("JARVIS_EMBEDDINGS_MODEL"));
    }

    @Override
    public boolean available() {
        return apiKey != null;
    }

    @Override
    public float[] embed(String text) throws IOException, InterruptedException {
        if (!available()) {
            throw new IllegalStateException("embeddings are dormant (no API key wired)");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("input", text == null ? "" : text);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
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
