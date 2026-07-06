package com.jarvis.ui;

import java.util.Objects;

/**
 * One displayable conversation message.
 *
 * @param role who the message is from (e.g. "user", "jarvis")
 * @param text what to display
 */
public record UiMessage(String role, String text) {

    public UiMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
    }
}
