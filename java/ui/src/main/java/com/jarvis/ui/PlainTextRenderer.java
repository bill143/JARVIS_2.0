package com.jarvis.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Minimal {@link UiRenderer}: writes {@code role> text} lines to any {@link Appendable}
 * (a console writer, a string buffer in tests). Exists to prove the seam renders — richer UIs
 * replace it.
 */
public final class PlainTextRenderer implements UiRenderer {

    private final Appendable out;

    public PlainTextRenderer(Appendable out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void render(UiMessage message) {
        Objects.requireNonNull(message, "message");
        try {
            out.append(message.role()).append("> ").append(message.text()).append('\n');
        } catch (IOException e) {
            throw new UncheckedIOException("failed to render message", e);
        }
    }
}
