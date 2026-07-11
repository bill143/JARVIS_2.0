package com.jarvis.registry;

import java.util.Objects;

/**
 * One declared parameter of a tool, from its {@code tool.json} manifest. Used to describe a tool's
 * inputs on the Tools &amp; Skills page and, later, to validate calls.
 *
 * @param name the parameter name
 * @param type a free-form type hint (e.g. {@code string}, {@code number}, {@code enum})
 * @param required whether the tool needs this parameter
 * @param description what it means
 */
public record ParameterSpec(String name, String type, boolean required, String description) {

    public ParameterSpec {
        Objects.requireNonNull(name, "name");
        type = type == null || type.isBlank() ? "string" : type;
        description = description == null ? "" : description;
    }
}
