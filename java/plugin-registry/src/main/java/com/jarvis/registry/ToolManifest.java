package com.jarvis.registry;

import com.jarvis.tools.RiskTier;
import java.util.List;
import java.util.Objects;

/**
 * The declared identity of a tool, loaded from a {@code tool.json} manifest. Carries the tool's
 * name, a human description, its {@link RiskTier}, and its parameter schema. The manifest is what
 * turns a hardcoded tool into a governed, self-describing plugin: the risk tier drives permission
 * prompts and audit logging, and the parameter schema documents the tool for the UI.
 *
 * @param name the tool name (must match the runtime {@code Tool.name()})
 * @param description what the tool does
 * @param riskTier how dangerous invoking it is
 * @param parameters the declared parameters (may be empty)
 */
public record ToolManifest(String name, String description, RiskTier riskTier,
        List<ParameterSpec> parameters) {

    public ToolManifest {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("manifest name must not be blank");
        }
        description = description == null ? "" : description;
        riskTier = riskTier == null ? RiskTier.UNKNOWN : riskTier;
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
