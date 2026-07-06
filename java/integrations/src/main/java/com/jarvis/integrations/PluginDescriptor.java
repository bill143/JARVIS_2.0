package com.jarvis.integrations;

import java.util.Objects;

/**
 * Immutable plugin metadata: how a plugin identifies and describes itself to the platform.
 *
 * @param name unique plugin name, the registration key
 * @param version plugin version string
 * @param description what the plugin contributes
 */
public record PluginDescriptor(String name, String version, String description) {

    public PluginDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
