package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The production {@link FaceRecognitionClient}: talks to a self-hosted CompreFace
 * (github.com/exadel-inc/CompreFace) instance over the JDK's {@link HttpClient} — no third-party
 * SDK, no multipart library. Mirrors the "live-resolving, dormant-by-default" shape of
 * {@code HttpGitHubTransport}: base URL / API key / similarity threshold are re-read from a
 * {@link VisionSettings.FaceSnapshot} supplier on <b>every call</b> (not cached at construction),
 * so an in-app settings change takes effect without a restart, and a missing base URL or API key
 * makes the client refuse the network call entirely rather than throwing.
 *
 * <p>Per the {@link FaceRecognitionClient} contract, neither {@link #recognize} nor {@link #enroll}
 * ever throws for ordinary provider failures (not configured, connection refused, timeout,
 * non-200 response, unparseable JSON) — those are all caught here and turned into
 * {@link FaceRecognitionClient.FaceMatchResult#error} / {@link FaceRecognitionClient.FaceEnrollResult#failure}.
 */
final class CompreFaceClient implements FaceRecognitionClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RECOGNIZE_PATH = "/api/v1/recognition/recognize";
    private static final String FACES_PATH = "/api/v1/recognition/faces";

    private final Supplier<VisionSettings.FaceSnapshot> faceSettings;
    private final HttpClient client;

    /**
     * @param faceSettings resolved live on every call (see class javadoc); never cached
     * @param client injected so tests can point at a local mock {@code HttpServer} with a short
     *     timeout — production wiring should use {@link #defaultHttpClient()}
     */
    CompreFaceClient(Supplier<VisionSettings.FaceSnapshot> faceSettings, HttpClient client) {
        this.faceSettings = faceSettings == null ? () -> null : faceSettings;
        this.client = client == null ? defaultHttpClient() : client;
    }

    /** Live-resolving client backed by {@code visionSettings.snapshot().face()}, production {@link HttpClient}. */
    static CompreFaceClient resolving(VisionSettings visionSettings) {
        return new CompreFaceClient(() -> visionSettings.snapshot().face(), defaultHttpClient());
    }

    /** The default production {@link HttpClient}: a 20s connect timeout, no other configuration. */
    static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public FaceMatchResult recognize(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return FaceMatchResult.error("No image bytes provided");
        }
        VisionSettings.FaceSnapshot snap = faceSettings.get();
        if (snap == null || isBlank(snap.baseUrl()) || isBlank(snap.apiKey())) {
            return FaceMatchResult.error("CompreFace not configured");
        }
        try {
            HttpRequest request = multipartRequest(
                    baseUrl(snap) + RECOGNIZE_PATH, snap.apiKey(), imageBytes);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return FaceMatchResult.error("CompreFace returned HTTP " + response.statusCode());
            }
            return parseRecognizeResponse(response.body(), snap.similarityThreshold());
        } catch (IOException e) {
            return FaceMatchResult.error("CompreFace request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FaceMatchResult.error("CompreFace request interrupted");
        } catch (RuntimeException e) {
            return FaceMatchResult.error("CompreFace request failed: " + e.getMessage());
        }
    }

    @Override
    public FaceEnrollResult enroll(byte[] imageBytes, String personName) {
        if (imageBytes == null || imageBytes.length == 0) {
            return FaceEnrollResult.failure("No image bytes provided");
        }
        if (isBlank(personName)) {
            return FaceEnrollResult.failure("Person name is required");
        }
        VisionSettings.FaceSnapshot snap = faceSettings.get();
        if (snap == null || isBlank(snap.baseUrl()) || isBlank(snap.apiKey())) {
            return FaceEnrollResult.failure("CompreFace not configured");
        }
        try {
            String url = baseUrl(snap) + FACES_PATH + "?subject="
                    + URLEncoder.encode(personName, StandardCharsets.UTF_8);
            HttpRequest request = multipartRequest(url, snap.apiKey(), imageBytes);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return FaceEnrollResult.failure("CompreFace returned HTTP " + response.statusCode());
            }
            return parseEnrollResponse(response.body());
        } catch (IOException e) {
            return FaceEnrollResult.failure("CompreFace request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FaceEnrollResult.failure("CompreFace request interrupted");
        } catch (RuntimeException e) {
            return FaceEnrollResult.failure("CompreFace request failed: " + e.getMessage());
        }
    }

    private FaceMatchResult parseRecognizeResponse(String body, double threshold) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            return FaceMatchResult.error("CompreFace returned unparseable response: " + e.getMessage());
        }
        if (root == null) {
            return FaceMatchResult.error("CompreFace returned an empty response");
        }
        JsonNode results = root.path("result");
        if (!results.isArray() || results.isEmpty()) {
            return FaceMatchResult.noMatch();
        }
        JsonNode subjects = results.get(0).path("subjects");
        if (!subjects.isArray() || subjects.isEmpty()) {
            return FaceMatchResult.noMatch();
        }
        String bestSubject = null;
        double bestSimilarity = -1.0;
        for (JsonNode subject : subjects) {
            double similarity = subject.path("similarity").asDouble(0.0);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestSubject = subject.path("subject").asText(null);
            }
        }
        if (bestSubject == null || bestSimilarity < threshold) {
            return FaceMatchResult.noMatch();
        }
        return FaceMatchResult.matched(bestSubject, bestSimilarity);
    }

    private FaceEnrollResult parseEnrollResponse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            return FaceEnrollResult.failure("CompreFace returned unparseable response: " + e.getMessage());
        }
        if (root == null) {
            return FaceEnrollResult.failure("CompreFace returned an empty response");
        }
        String imageId = root.path("image_id").asText(null);
        if (isBlank(imageId)) {
            return FaceEnrollResult.failure("CompreFace response missing image_id");
        }
        return FaceEnrollResult.success(imageId);
    }

    /**
     * Builds a {@code POST} with a hand-rolled {@code multipart/form-data} body (single field,
     * {@code file}) — no multipart library is on the dependency whitelist, but the JDK's
     * {@link HttpRequest.BodyPublishers#ofByteArrays} makes it trivial to assemble one from raw
     * parts without ever turning the image bytes into a {@link String}.
     */
    private HttpRequest multipartRequest(String url, String apiKey, byte[] imageBytes) {
        String boundary = newBoundary();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"image.jpg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        List<byte[]> parts = List.of(
                head.getBytes(StandardCharsets.UTF_8),
                imageBytes,
                tail.getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(url))
                .header("x-api-key", apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
                .build();
    }

    private static String newBoundary() {
        return "----JarvisCompreFaceBoundary"
                + Long.toHexString(ThreadLocalRandom.current().nextLong())
                + Long.toHexString(System.nanoTime());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String baseUrl(VisionSettings.FaceSnapshot snap) {
        String url = snap.baseUrl().strip();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
