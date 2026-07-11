package com.jarvis.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.tools.RiskTier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Reads {@code tool.json} manifests. A manifest is a JSON object with {@code name} (required),
 * {@code description}, {@code riskTier} (READ_ONLY / MUTATING / DESTRUCTIVE / UNKNOWN), and an
 * optional {@code parameters} array of {@code {name,type,required,description}}.
 *
 * <p>Parsing is lenient by design so one malformed plugin never blocks the rest: a manifest missing
 * a name is skipped, and an unrecognized risk tier falls back to {@link RiskTier#UNKNOWN}.
 */
public final class ManifestLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ManifestLoader() {
    }

    /** Parses a single manifest object. Throws {@link IllegalArgumentException} if it has no name. */
    public static ToolManifest parse(String json) {
        try {
            ToolManifest manifest = fromNode(MAPPER.readTree(json));
            if (manifest == null) {
                throw new IllegalArgumentException("manifest is missing a name: " + json);
            }
            return manifest;
        } catch (IOException e) {
            throw new IllegalArgumentException("unparseable manifest: " + json, e);
        }
    }

    /** Parses a JSON array of manifests, skipping any that are malformed or unnamed. */
    public static List<ToolManifest> parseArray(String json) {
        List<ToolManifest> out = new ArrayList<>();
        try {
            for (JsonNode node : MAPPER.readTree(json)) {
                ToolManifest manifest = fromNode(node);
                if (manifest != null) {
                    out.add(manifest);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("unparseable manifest array", e);
        }
        return out;
    }

    /**
     * Loads every {@code *.tool.json} file in {@code dir} (one manifest per file). Returns an empty
     * list if the directory does not exist — a missing plugins folder is not an error.
     */
    public static List<ToolManifest> loadDirectory(Path dir) {
        Objects.requireNonNull(dir, "dir");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ToolManifest> out = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir, "*.tool.json")) {
            for (Path file : stream) {
                try {
                    ToolManifest manifest = fromNode(
                            MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8)));
                    if (manifest != null) {
                        out.add(manifest);
                    }
                } catch (IOException | RuntimeException skip) {
                    // A bad plugin file is skipped, not fatal.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan plugins directory: " + dir, e);
        }
        return out;
    }

    /** Builds a manifest from a node, or null if it has no usable name. */
    private static ToolManifest fromNode(JsonNode node) {
        if (node == null || !node.hasNonNull("name") || node.path("name").asText().isBlank()) {
            return null;
        }
        String name = node.path("name").asText().strip();
        String description = node.path("description").asText("");
        RiskTier tier = parseTier(node.path("riskTier").asText(""));
        List<ParameterSpec> params = new ArrayList<>();
        for (JsonNode p : node.path("parameters")) {
            if (p.hasNonNull("name")) {
                params.add(new ParameterSpec(p.path("name").asText(), p.path("type").asText("string"),
                        p.path("required").asBoolean(false), p.path("description").asText("")));
            }
        }
        return new ToolManifest(name, description, tier, params);
    }

    private static RiskTier parseTier(String raw) {
        if (raw == null || raw.isBlank()) {
            return RiskTier.UNKNOWN;
        }
        try {
            return RiskTier.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RiskTier.UNKNOWN;
        }
    }
}
