package com.jarvis.app;

import com.jarvis.memory.MemoryStore;
import java.util.List;
import java.util.function.Function;

/**
 * Durable, in-app configuration for the external connectors (Brain/Obsidian, SAM.gov, GitHub,
 * OpenHuman, embeddings, Google Drive, OneDrive). Values are persisted to the {@link MemoryStore}
 * under the {@code connectors} scope so they survive a restart, and they are resolved <b>live</b>:
 * {@link #resolve} returns the saved value when present, otherwise falls back to the process
 * environment variable. Because the transports read through a {@link java.util.function.Supplier}
 * backed by {@link #resolve}, saving a value through the UI takes effect on the next request with
 * <b>no restart</b> and without setting an environment variable.
 *
 * <p>Secrets (keys/tokens) are stored locally and are never returned by {@link #status} — only a
 * {@code hasValue} flag is exposed to the UI, mirroring the providers/MCP panels.
 */
final class ConnectorSettingsService {

    static final String SCOPE = "connectors";

    /**
     * The configurable connectors and their fields, in display order. Each field names the store key
     * and the environment variable it falls back to, and whether it holds a secret. This single
     * catalog drives the UI form, the config endpoint, and the live suppliers handed to the
     * transports.
     */
    static final java.util.Map<String, List<FieldSpec>> CONNECTORS;

    static {
        java.util.LinkedHashMap<String, List<FieldSpec>> m = new java.util.LinkedHashMap<>();
        m.put("obsidian", List.of(
                new FieldSpec("obsidian.vaultPath", "OBSIDIAN_VAULT_PATH", false)));
        m.put("samgov", List.of(
                new FieldSpec("samgov.apiKey", "SAMGOV_API_KEY", true),
                new FieldSpec("samgov.baseUrl", "SAMGOV_BASE_URL", false)));
        m.put("github", List.of(
                new FieldSpec("github.token", "JARVIS_GITHUB_TOKEN", true)));
        m.put("openhuman", List.of(
                new FieldSpec("openhuman.url", "JARVIS_OPENHUMAN_URL", false),
                new FieldSpec("openhuman.token", "OPENHUMAN_CORE_TOKEN", true)));
        m.put("embeddings", List.of(
                new FieldSpec("embeddings.key", "JARVIS_EMBEDDINGS_KEY", true),
                new FieldSpec("embeddings.endpoint", "JARVIS_EMBEDDINGS_ENDPOINT", false),
                new FieldSpec("embeddings.model", "JARVIS_EMBEDDINGS_MODEL", false)));
        m.put("gdrive", List.of(
                new FieldSpec("gdrive.token", "GOOGLE_DRIVE_TOKEN", true),
                new FieldSpec("gdrive.folders", "GOOGLE_DRIVE_ALLOWED_FOLDER_IDS", false)));
        m.put("onedrive", List.of(
                new FieldSpec("onedrive.token", "ONEDRIVE_TOKEN", true),
                new FieldSpec("onedrive.folders", "ONEDRIVE_ALLOWED_FOLDER_IDS", false)));
        CONNECTORS = java.util.Collections.unmodifiableMap(m);
    }

    /** A single stored field the UI can render (never carries the secret value). */
    record Field(String key, String env, boolean secret, boolean set, String value) {
    }

    private final MemoryStore<String> store;
    private final Function<String, String> env;   // seam so tests avoid the real environment

    ConnectorSettingsService(MemoryStore<String> store) {
        this(store, System::getenv);
    }

    ConnectorSettingsService(MemoryStore<String> store, Function<String, String> env) {
        this.store = store;
        this.env = env == null ? k -> null : env;
    }

    /** The saved value for {@code key}, else the {@code envName} environment variable, else null. */
    String resolve(String key, String envName) {
        String v = store.get(SCOPE, key).map(e -> e.value()).orElse(null);
        if (v != null && !v.isBlank()) {
            return v;
        }
        String e = envName == null ? null : env.apply(envName);
        return e == null || e.isBlank() ? null : e;
    }

    /** A live supplier over {@link #resolve} — hand this to a transport so it re-reads per request. */
    java.util.function.Supplier<String> supplier(String key, String envName) {
        return () -> resolve(key, envName);
    }

    /** Persists a value (blank/null clears it, reverting to the environment fallback). */
    void set(String key, String value) {
        if (value == null || value.isBlank()) {
            store.delete(SCOPE, key);
        } else {
            store.put(SCOPE, key, value.strip());
        }
    }

    /** Whether {@code key} resolves to a non-blank value (saved or from the environment). */
    boolean has(String key, String envName) {
        return resolve(key, envName) != null;
    }

    /**
     * A non-secret view of one connector's fields for the UI. Secret fields report only whether a
     * value is set; non-secret fields (URLs, endpoints, folder paths) return the resolved value so
     * the form can prefill them.
     */
    List<Field> view(List<FieldSpec> specs) {
        return specs.stream().map(s -> {
            String resolved = resolve(s.key(), s.env());
            boolean set = resolved != null;
            String shown = s.secret() ? "" : (resolved == null ? "" : resolved);
            return new Field(s.key(), s.env(), s.secret(), set, shown);
        }).toList();
    }

    /** Declares a configurable field: its store key, the env var it falls back to, and secrecy. */
    record FieldSpec(String key, String env, boolean secret) {
    }
}
