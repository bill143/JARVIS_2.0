package com.jarvis.speech;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, engine-agnostic chunk of audio. Bytes are defensively copied on the way in and out so
 * the clip cannot be mutated through a retained or returned array.
 *
 * @param data the encoded audio bytes
 * @param format adapter-defined format tag (e.g. "wav", "pcm16le-16k")
 */
public record AudioClip(byte[] data, String format) {

    public AudioClip {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(format, "format");
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioClip clip
                && format.equals(clip.format)
                && Arrays.equals(data, clip.data);
    }

    @Override
    public int hashCode() {
        return 31 * format.hashCode() + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "AudioClip[format=" + format + ", bytes=" + data.length + "]";
    }
}
