package com.jarvis.rag;

/**
 * Seam over a text-embedding backend: turns text into a dense vector so documents can be recalled
 * by meaning rather than by shared keywords. The concrete cloud implementation lives in the app
 * layer (JDK {@code HttpClient} + Jackson); tests and the offline default use fakes.
 *
 * <p>Cloud embeddings are decision D3 — <b>dormant by default</b>, like the updater and licensing
 * network calls. A provider whose {@link #available()} is {@code false} must never be asked to
 * {@link #embed} (semantic callers fall back to keyword search instead), so no network traffic and
 * no cost are incurred until an API key is deliberately wired in.
 */
public interface EmbeddingProvider {

    /** Whether this provider can actually embed (an API key is present). */
    boolean available();

    /** Embeds {@code text} into a dense vector. Only valid when {@link #available()} is true. */
    float[] embed(String text) throws Exception;

    /** The dormant provider: never available, refuses to embed. The safe default (decision D3). */
    EmbeddingProvider DORMANT = new EmbeddingProvider() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public float[] embed(String text) {
            throw new IllegalStateException("embeddings are dormant (no API key wired)");
        }
    };
}
