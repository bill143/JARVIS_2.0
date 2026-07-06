package com.jarvis.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VoicePipelineTest {

    /** Fake STT: "hears" whatever text the clip's bytes spell out. */
    private static final SpeechToText FAKE_STT = audio ->
            new Transcription(new String(audio.data(), StandardCharsets.UTF_8), 0.9);

    /** Fake TTS: "speaks" by encoding the text back into bytes. */
    private static final TextToSpeech FAKE_TTS = text ->
            new AudioClip(text.getBytes(StandardCharsets.UTF_8), "fake");

    private static AudioClip saying(String text) {
        return new AudioClip(text.getBytes(StandardCharsets.UTF_8), "fake");
    }

    @Test
    void ungatedPipelineCompletesAFullTurn() {
        VoicePipeline pipeline =
                new VoicePipeline(FAKE_STT, prompt -> "you said: " + prompt, FAKE_TTS);

        VoiceTurn turn = pipeline.onAudio(saying("what time is it")).orElseThrow();
        assertEquals("what time is it", turn.transcription().text());
        assertEquals(0.9, turn.transcription().confidence());
        assertEquals("you said: what time is it", turn.responseText());
        assertEquals(saying("you said: what time is it"), turn.responseAudio());
    }

    @Test
    void wakeWordGateIgnoresUnrelatedSpeech() {
        VoicePipeline pipeline =
                new VoicePipeline(FAKE_STT, prompt -> "reply", FAKE_TTS, "jarvis");

        Optional<VoiceTurn> turn = pipeline.onAudio(saying("just people chatting nearby"));
        assertTrue(turn.isEmpty());
    }

    @Test
    void wakeWordIsMatchedCaseInsensitivelyAndStrippedFromThePrompt() {
        VoicePipeline pipeline =
                new VoicePipeline(FAKE_STT, prompt -> "prompt was: " + prompt, FAKE_TTS, "jarvis");

        VoiceTurn turn = pipeline.onAudio(saying("hey JARVIS turn on the lights")).orElseThrow();
        assertEquals("prompt was: turn on the lights", turn.responseText());
        // The transcription still reports the full utterance.
        assertEquals("hey JARVIS turn on the lights", turn.transcription().text());
    }

    @Test
    void wakeWordAloneYieldsAnEmptyPrompt() {
        VoicePipeline pipeline =
                new VoicePipeline(FAKE_STT, prompt -> "[" + prompt + "]", FAKE_TTS, "jarvis");

        VoiceTurn turn = pipeline.onAudio(saying("jarvis")).orElseThrow();
        assertEquals("[]", turn.responseText());
    }

    @Test
    void constructorAndInputValidation() {
        assertThrows(NullPointerException.class,
                () -> new VoicePipeline(null, p -> "r", FAKE_TTS));
        assertThrows(NullPointerException.class,
                () -> new VoicePipeline(FAKE_STT, null, FAKE_TTS));
        assertThrows(NullPointerException.class,
                () -> new VoicePipeline(FAKE_STT, p -> "r", null));
        assertThrows(IllegalArgumentException.class,
                () -> new VoicePipeline(FAKE_STT, p -> "r", FAKE_TTS, "  "));

        VoicePipeline pipeline = new VoicePipeline(FAKE_STT, p -> "r", FAKE_TTS);
        assertThrows(NullPointerException.class, () -> pipeline.onAudio(null));
    }

    @Test
    void transcriptionConfidenceIsRangeChecked() {
        assertThrows(IllegalArgumentException.class, () -> new Transcription("t", -0.1));
        assertThrows(IllegalArgumentException.class, () -> new Transcription("t", 1.1));
        assertThrows(IllegalArgumentException.class, () -> new Transcription("t", Double.NaN));
        assertEquals(1.0, new Transcription("t", 1.0).confidence());
    }

    @Test
    void audioClipIsDefensivelyCopiedAndValueComparable() {
        byte[] bytes = {1, 2, 3};
        AudioClip clip = new AudioClip(bytes, "wav");
        bytes[0] = 99;

        assertEquals(1, clip.data()[0]);
        assertNotSame(clip.data(), clip.data());
        assertEquals(new AudioClip(new byte[] {1, 2, 3}, "wav"), clip);
        assertNotEquals(new AudioClip(new byte[] {1, 2, 3}, "mp3"), clip);
        assertEquals("AudioClip[format=wav, bytes=3]", clip.toString());
    }
}
