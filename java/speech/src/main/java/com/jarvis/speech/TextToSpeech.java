package com.jarvis.speech;

/**
 * Speech-synthesis adapter: turns text into audio. Concrete engines implement this in later steps
 * or external modules — the pipeline only needs the contract.
 */
@FunctionalInterface
public interface TextToSpeech {

    /** Synthesizes speech for {@code text}; must not return {@code null}. */
    AudioClip synthesize(String text);
}
