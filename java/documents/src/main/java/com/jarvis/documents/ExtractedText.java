package com.jarvis.documents;

import java.util.Objects;

/**
 * The outcome of extracting readable text from an uploaded file.
 *
 * @param kind      the detected document kind ({@code text}, {@code csv}, {@code json}, {@code markdown},
 *                  {@code pdf}, {@code docx}, {@code xlsx}, or {@code unsupported})
 * @param text      the extracted plain text (never {@code null}; empty when nothing could be read)
 * @param truncated whether {@link #text} was cut off at the character cap
 * @param note      a short human-readable note (e.g. why extraction was partial); empty when clean
 */
public record ExtractedText(String kind, String text, boolean truncated, String note) {

    public ExtractedText {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        note = note == null ? "" : note;
    }

    /** Convenience: a clean, fully-read result. */
    public static ExtractedText of(String kind, String text) {
        return new ExtractedText(kind, text, false, "");
    }

    /** Convenience: an unsupported/failed extraction carrying an explanatory note. */
    public static ExtractedText unsupported(String note) {
        return new ExtractedText("unsupported", "", false, note);
    }

    /** Number of characters extracted. */
    public int chars() {
        return text.length();
    }
}
