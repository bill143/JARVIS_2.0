package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.integrations.llm.ModelCatalog;
import com.jarvis.memory.MemoryEntry;
import com.jarvis.memory.MemoryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores the user's configured model providers and which one is active — the backend for the
 * Settings "Models &amp; Providers" panel. Each provider is one row: a kind
 * ({@code anthropic} native or {@code openai} for any OpenAI-compatible endpoint incl. NVIDIA),
 * a base URL, an API key, and the chosen model. Config persists locally in the {@link MemoryStore}
 * (scope {@code providers}); the API key is held locally and is <b>never</b> returned by
 * {@link #list()} or logged.
 *
 * <p>The active selection takes effect when the app (re)starts — {@link AppWiring} reads it to build
 * the chat brain. Model lists are fetched live from the provider's {@code /models} endpoint, so no
 * model IDs are hardcoded.
 */
final class ProviderSettingsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE = "providers";
    private static final String ACTIVE_KEY = "__active__";

    /** Fetches a provider's available model IDs (seam so tests avoid the network). */
    @FunctionalInterface
    interface ModelFetcher {
        List<String> fetch(String baseUrl, String apiKey) throws Exception;
    }

    /** Validates a native Anthropic key by listing its models (seam so tests avoid the network). */
    @FunctionalInterface
    interface AnthropicValidator {
        List<String> validate(String apiKey) throws Exception;
    }

    /** Preset base URLs the UI offers (all real, documented endpoints). */
    record Preset(String name, String kind, String baseUrl) {
    }

    /**
     * A configured provider as shown to the UI — no API key, only whether one is set. {@code role}
     * is its place in the orchestration hierarchy ({@code conductor} / {@code orchestrator} /
     * {@code worker}), or empty when unassigned.
     */
    record ProviderView(String name, String kind, String baseUrl, String model,
            boolean hasKey, boolean active, String role) {
    }

    /** Valid orchestration roles (tiers), highest first. */
    static final List<String> ROLES = List.of("conductor", "orchestrator", "worker");

    /** The active provider's full config, for internal use by {@link AppWiring}. */
    record Active(String name, String kind, String baseUrl, String apiKey, String model) {
    }

    static final List<Preset> PRESETS = List.of(
            new Preset("Anthropic", "anthropic", ""),
            new Preset("NVIDIA", "openai", "https://integrate.api.nvidia.com/v1"),
            new Preset("OpenAI", "openai", "https://api.openai.com/v1"),
            new Preset("OpenRouter", "openai", "https://openrouter.ai/api/v1"),
            new Preset("Groq", "openai", "https://api.groq.com/openai/v1"),
            new Preset("Mistral", "openai", "https://api.mistral.ai/v1"),
            new Preset("DeepSeek", "openai", "https://api.deepseek.com"),
            new Preset("xAI", "openai", "https://api.x.ai/v1"),
            new Preset("Ollama (local)", "openai", "http://localhost:11434/v1"));

    private final MemoryStore<String> store;
    private final ModelFetcher fetcher;
    private final AnthropicValidator anthropicValidator;

    ProviderSettingsService(MemoryStore<String> store) {
        this(store, ModelCatalog::fetch, ModelCatalog::fetchAnthropic);
    }

    ProviderSettingsService(MemoryStore<String> store, ModelFetcher fetcher) {
        this(store, fetcher, ModelCatalog::fetchAnthropic);
    }

    ProviderSettingsService(MemoryStore<String> store, ModelFetcher fetcher,
            AnthropicValidator anthropicValidator) {
        this.store = Objects.requireNonNull(store, "store");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.anthropicValidator = Objects.requireNonNull(anthropicValidator, "anthropicValidator");
    }

    /** Configured providers (no keys), newest config wins; marks the active one. */
    List<ProviderView> list() {
        String active = activeName();
        List<ProviderView> out = new ArrayList<>();
        for (MemoryEntry<String> e : store.query(SCOPE)) {
            if (ACTIVE_KEY.equals(e.key())) {
                continue;
            }
            JsonNode c = parse(e.value());
            out.add(new ProviderView(e.key(), c.path("kind").asText("openai"),
                    c.path("baseUrl").asText(""), c.path("model").asText(""),
                    !c.path("apiKey").asText("").isBlank(), e.key().equals(active),
                    c.path("role").asText("")));
        }
        return out;
    }

    /**
     * Saves/updates a provider. A blank {@code apiKey} keeps any existing key (so the model can be
     * changed without re-entering the key). Optionally makes it the active brain.
     */
    void save(String name, String kind, String baseUrl, String apiKey, String model,
            boolean makeActive) {
        Objects.requireNonNull(name, "name");
        ObjectNode c = MAPPER.createObjectNode();
        c.put("kind", kind == null || kind.isBlank() ? "openai" : kind);
        c.put("baseUrl", baseUrl == null ? "" : baseUrl.strip());
        c.put("model", model == null ? "" : model.strip());
        String key = apiKey == null ? "" : apiKey.strip();
        if (key.isBlank()) {
            key = store.get(SCOPE, name).map(e -> parse(e.value()).path("apiKey").asText("")).orElse("");
        }
        c.put("apiKey", key);
        // Preserve any assigned orchestration role across a save (model/key edits keep the tier).
        c.put("role", store.get(SCOPE, name)
                .map(e -> parse(e.value()).path("role").asText("")).orElse(""));
        store.put(SCOPE, name, c.toString());
        if (makeActive) {
            store.put(SCOPE, ACTIVE_KEY, name);
        }
    }

    /** Assigns an orchestration role/tier to a provider (empty/unknown clears it). */
    boolean setRole(String name, String role) {
        var entry = store.get(SCOPE, name);
        if (entry.isEmpty()) {
            return false;
        }
        String r = role == null ? "" : role.strip().toLowerCase();
        if (!ROLES.contains(r)) {
            r = "";
        }
        JsonNode c = parse(entry.get().value());
        ObjectNode o = c.isObject() ? (ObjectNode) c : MAPPER.createObjectNode();
        o.put("role", r);
        store.put(SCOPE, name, o.toString());
        return true;
    }

    /** Result of a live connection test. */
    record TestResult(boolean ok, String message) {
    }

    /** Makes {@code name} the active brain if it is configured. Returns whether it now is. */
    boolean activate(String name) {
        if (name == null || store.get(SCOPE, name).isEmpty()) {
            return false;
        }
        store.put(SCOPE, ACTIVE_KEY, name);
        return true;
    }

    /** Removes a configured provider (and clears the active pointer if it referenced it). */
    boolean remove(String name) {
        if (name == null || ACTIVE_KEY.equals(name)) {
            return false;
        }
        boolean had = store.delete(SCOPE, name);
        if (name.equals(activeName())) {
            store.delete(SCOPE, ACTIVE_KEY);
        }
        return had;
    }

    /**
     * Live-tests a configured provider's credentials with a token-free listing call. OpenAI-compatible
     * providers hit {@code GET /models} with a bearer key; native Anthropic hits {@code GET
     * /v1/models} with its {@code x-api-key} headers. Both are a clean 200/401 signal that actually
     * validates the key — the "Test" button no longer merely confirms local storage.
     */
    TestResult test(String name) {
        var entry = store.get(SCOPE, name);
        if (entry.isEmpty()) {
            return new TestResult(false, "no such provider");
        }
        JsonNode c = parse(entry.get().value());
        String kind = c.path("kind").asText("openai");
        String key = c.path("apiKey").asText("");
        if ("anthropic".equals(kind)) {
            if (key.isBlank()) {
                return new TestResult(false, "no API key set");
            }
            try {
                List<String> models = anthropicValidator.validate(key);
                return new TestResult(true, "connected — " + models.size() + " models available");
            } catch (Exception e) {
                return new TestResult(false, friendlyError(e));
            }
        }
        if (key.isBlank() && !c.path("baseUrl").asText("").contains("localhost")) {
            return new TestResult(false, "no API key set");
        }
        try {
            List<String> models = fetcher.fetch(c.path("baseUrl").asText(""), key);
            return new TestResult(true, "connected — " + models.size() + " models available");
        } catch (Exception e) {
            return new TestResult(false, friendlyError(e));
        }
    }

    /** Maps a raw transport error to a user-facing message, decoding the common auth statuses. */
    private static String friendlyError(Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (msg.contains("401")) {
            return "authentication failed — the API key was rejected (401)";
        } else if (msg.contains("403")) {
            return "forbidden — the key lacks access to this endpoint (403)";
        } else if (msg.contains("404")) {
            return "endpoint not found — check the base URL (404)";
        }
        return msg;
    }

    /** Every configured provider as an {@link Active} (with key), for internal orchestration use. */
    List<Active> allConfigured() {
        List<Active> out = new ArrayList<>();
        for (MemoryEntry<String> e : store.query(SCOPE)) {
            if (ACTIVE_KEY.equals(e.key())) {
                continue;
            }
            JsonNode c = parse(e.value());
            out.add(new Active(e.key(), c.path("kind").asText("openai"), c.path("baseUrl").asText(""),
                    c.path("apiKey").asText(""), c.path("model").asText("")));
        }
        return out;
    }

    /** Configured providers assigned the given orchestration role. */
    List<Active> withRole(String role) {
        List<Active> out = new ArrayList<>();
        for (MemoryEntry<String> e : store.query(SCOPE)) {
            if (ACTIVE_KEY.equals(e.key())) {
                continue;
            }
            JsonNode c = parse(e.value());
            if (role.equals(c.path("role").asText(""))) {
                out.add(new Active(e.key(), c.path("kind").asText("openai"), c.path("baseUrl").asText(""),
                        c.path("apiKey").asText(""), c.path("model").asText("")));
            }
        }
        return out;
    }

    /** The role assigned to {@code name}, or empty. */
    String roleOf(String name) {
        return store.get(SCOPE, name).map(e -> parse(e.value()).path("role").asText("")).orElse("");
    }

    /** The active provider's full config (incl. key) if one is selected and configured. */
    Optional<Active> active() {
        String name = activeName();
        if (name == null) {
            return Optional.empty();
        }
        return store.get(SCOPE, name).map(e -> parse(e.value())).map(c -> new Active(
                name, c.path("kind").asText("openai"), c.path("baseUrl").asText(""),
                c.path("apiKey").asText(""), c.path("model").asText("")));
    }

    /** Live model IDs for a configured provider (empty on any error). */
    List<String> models(String name) {
        return store.get(SCOPE, name).map(e -> parse(e.value())).map(c -> {
            try {
                return fetcher.fetch(c.path("baseUrl").asText(""), c.path("apiKey").asText(""));
            } catch (Exception ex) {
                return List.<String>of();
            }
        }).orElse(List.of());
    }

    private String activeName() {
        return store.get(SCOPE, ACTIVE_KEY).map(MemoryEntry::value).filter(s -> !s.isBlank())
                .orElse(null);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
