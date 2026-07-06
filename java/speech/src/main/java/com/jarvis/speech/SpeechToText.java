package com.jarvis.speech;

/**
 * Speech-recognition adapter: turns audio into text. Concrete engines (on-device models, cloud
 * APIs) implement this in later steps or external modules — the pipeline only needs the contract.
 */
@FunctionalInterface
public interface SpeechToText {

    /** Transcribes {@code audio}; must not return {@code null}. */
    Transcription transcribe(AudioClip audio);
}
