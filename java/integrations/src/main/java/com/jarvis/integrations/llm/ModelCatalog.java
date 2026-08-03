package com.jarvis.integrations.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists the models a provider actually exposes via the OpenAI-compatible {@code GET /models}
 * endpoint. Fetching live means we never hardcode (and never invent) model IDs — the dropdown shows
 * exactly what the configured key can use, and it stays current as the provider's catalog changes.
 */
public final class ModelCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelCatalog() {
    }

    /** Parses the {@code {"data":[{"id":...}]}} model-list body into sorted model IDs. */
    public static List<String> parse(String modelsJson) {
        List<String> ids = new ArrayList<>();
        if (modelsJson == null || modelsJson.isBlank()) {
            return ids;
        }
        try {
            for (JsonNode m : MAPPER.readTree(modelsJson).path("data")) {
                String id = m.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        } catch (IOException e) {
            return ids;   // unparseable → empty, never throws into the UI path
        }
        ids.sort(String::compareTo);
        return ids;
    }

    /**
     * Validates an Anthropic key by listing its models over the native {@code GET
     * https://api.anthropic.com/v1/models} endpoint (a token-free listing call, so it costs nothing
     * yet proves the key is accepted). Uses Anthropic's {@code x-api-key} / {@code anthropic-version}
     * headers rather than OpenAI's bearer scheme.
     */
    public static List<String> fetchAnthropic(String apiKey)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models"))
                .header("x-api-key", apiKey == null ? "" : apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("model list returned " + response.statusCode());
        }
        return parse(response.body());
    }

    /** Fetches {@code <baseUrl>/models} with a bearer key and returns the model IDs. */
    public static List<String> fetch(String baseUrl, String apiKey)
            throws IOException, InterruptedException {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/models"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("model list returned " + response.statusCode());
        }
        return parse(response.body());
    }
}
