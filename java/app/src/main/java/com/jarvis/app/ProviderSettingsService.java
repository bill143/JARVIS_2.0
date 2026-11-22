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

    /** Preset base URLs the UI offers (all real, documented endpoints). */
    record Preset(String name, String kind, String baseUrl) {
    }

    /** A configured provider as shown to the UI — no API key, only whether one is set. */
    record ProviderView(String name, String kind, String baseUrl, String model,
            boolean hasKey, boolean active) {
    }

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

    ProviderSettingsService(MemoryStore<String> store) {
        this(store, ModelCatalog::fetch);
    }

    ProviderSettingsService(MemoryStore<String> store, ModelFetcher fetcher) {
        this.store = Objects.requireNonNull(store, "store");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
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
                    !c.path("apiKey").asText("").isBlank(), e.key().equals(active)));
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
        store.put(SCOPE, name, c.toString());
        if (makeActive) {
            store.put(SCOPE, ACTIVE_KEY, name);
        }
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
