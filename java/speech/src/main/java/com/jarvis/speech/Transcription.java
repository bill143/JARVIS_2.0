package com.jarvis.speech;

import java.util.Objects;

/**
 * Result of transcribing audio to text.
 *
 * @param text the recognized text
 * @param confidence adapter-reported recognition confidence in {@code [0.0, 1.0]}
 */
public record Transcription(String text, double confidence) {

    public Transcription {
        Objects.requireNonNull(text, "text");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException(
                    "confidence must be within [0.0, 1.0], got " + confidence);
        }
    }
}
