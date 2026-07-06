package com.jarvis.speech;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The voice interaction pattern from {@code open-jarvis/OpenJarvis}, reduced to its turn mechanism:
 * audio in → transcribe → (wake-word gate) → handle → synthesize → audio out.
 *
 * <p>Engine-free by design — recognition and synthesis arrive through the {@link SpeechToText} and
 * {@link TextToSpeech} adapters, reasoning through the {@link PromptHandler} seam. When a wake word
 * is configured, transcripts that do not contain it are ignored (an empty result), and the wake
 * word plus everything before it is stripped from the prompt passed to the handler.
 */
public final class VoicePipeline {

    private final SpeechToText stt;
    private final PromptHandler handler;
    private final TextToSpeech tts;
    private final String wakeWord;

    /** Creates a pipeline that responds to every utterance (no wake-word gating). */
    public VoicePipeline(SpeechToText stt, PromptHandler handler, TextToSpeech tts) {
        this(stt, handler, tts, null);
    }

    /**
     * Creates a pipeline gated on {@code wakeWord} (case-insensitive), or ungated when
     * {@code wakeWord} is {@code null}.
     */
    public VoicePipeline(SpeechToText stt, PromptHandler handler, TextToSpeech tts, String wakeWord) {
        this.stt = Objects.requireNonNull(stt, "stt");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.tts = Objects.requireNonNull(tts, "tts");
        if (wakeWord != null && wakeWord.isBlank()) {
            throw new IllegalArgumentException("wakeWord must be null or non-blank");
        }
        this.wakeWord = wakeWord == null ? null : wakeWord.toLowerCase(Locale.ROOT);
    }

    /**
     * Processes one utterance. Returns the completed turn, or {@link Optional#empty()} when a wake
     * word is configured and the transcript does not contain it.
     */
    public Optional<VoiceTurn> onAudio(AudioClip audio) {
        Objects.requireNonNull(audio, "audio");
        Transcription transcription =
                Objects.requireNonNull(stt.transcribe(audio), "stt returned null transcription");
        Optional<String> prompt = extractPrompt(transcription.text());
        if (prompt.isEmpty()) {
            return Optional.empty();
        }
        String response = Objects.requireNonNull(
                handler.handle(prompt.get()), "handler returned null response");
        AudioClip spoken = Objects.requireNonNull(
                tts.synthesize(response), "tts returned null audio");
        return Optional.of(new VoiceTurn(transcription, response, spoken));
    }

    private Optional<String> extractPrompt(String transcript) {
        if (wakeWord == null) {
            return Optional.of(transcript.strip());
        }
        int at = transcript.toLowerCase(Locale.ROOT).indexOf(wakeWord);
        if (at < 0) {
            return Optional.empty();
        }
        return Optional.of(transcript.substring(at + wakeWord.length()).strip());
    }
}
