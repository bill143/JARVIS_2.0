package com.jarvis.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

class PlainTextRendererTest {

    @Test
    void rendersRolePrefixedLines() {
        StringBuilder out = new StringBuilder();
        UiRenderer renderer = new PlainTextRenderer(out);

        renderer.render(new UiMessage("user", "hello"));
        renderer.render(new UiMessage("jarvis", "hi bill"));

        assertEquals("user> hello\njarvis> hi bill\n", out.toString());
    }

    @Test
    void messageValidation() {
        assertThrows(IllegalArgumentException.class, () -> new UiMessage(" ", "t"));
        assertThrows(NullPointerException.class, () -> new UiMessage(null, "t"));
        assertThrows(NullPointerException.class, () -> new UiMessage("r", null));
    }

    @Test
    void rendererValidatesArguments() {
        assertThrows(NullPointerException.class, () -> new PlainTextRenderer(null));
        assertThrows(NullPointerException.class,
                () -> new PlainTextRenderer(new StringBuilder()).render(null));
    }

    @Test
    void ioFailuresSurfaceUnchecked() {
        Appendable failing = new Appendable() {
            @Override
            public Appendable append(CharSequence csq) throws IOException {
                throw new IOException("closed");
            }

            @Override
            public Appendable append(CharSequence csq, int start, int end) throws IOException {
                throw new IOException("closed");
            }

            @Override
            public Appendable append(char c) throws IOException {
                throw new IOException("closed");
            }
        };
        UiRenderer renderer = new PlainTextRenderer(failing);
        assertThrows(UncheckedIOException.class, () -> renderer.render(new UiMessage("r", "t")));
    }
}
