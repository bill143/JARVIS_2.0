package com.jarvis.speech;

import java.util.Objects;

/**
 * One completed voice interaction: what was heard, what was answered, and the audio to play back.
 *
 * @param transcription what the user was heard to say
 * @param responseText the reply produced by the {@link PromptHandler}
 * @param responseAudio the synthesized reply
 */
public record VoiceTurn(Transcription transcription, String responseText, AudioClip responseAudio) {

    public VoiceTurn {
        Objects.requireNonNull(transcription, "transcription");
        Objects.requireNonNull(responseText, "responseText");
        Objects.requireNonNull(responseAudio, "responseAudio");
    }
}
