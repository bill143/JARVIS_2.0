package com.jarvis.updater;

import java.util.Objects;

/**
 * A simple {@code major.minor.patch} semantic version. Any pre-release / build suffix
 * ({@code -SNAPSHOT}, {@code +build}) is ignored for comparison, which is enough to decide "is the
 * published version newer than mine?".
 *
 * @param major the major component
 * @param minor the minor component
 * @param patch the patch component
 */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    public Version {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version components must be >= 0");
        }
    }

    /** Parses {@code "1.2.3"} (missing components default to 0; suffixes after -/+ are ignored). */
    public static Version parse(String text) {
        Objects.requireNonNull(text, "text");
        String core = text.strip();
        int cut = core.length();
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            if (c == '-' || c == '+') {
                cut = i;
                break;
            }
        }
        String[] parts = core.substring(0, cut).split("\\.");
        try {
            int maj = parts.length > 0 && !parts[0].isBlank() ? Integer.parseInt(parts[0].strip()) : 0;
            int min = parts.length > 1 && !parts[1].isBlank() ? Integer.parseInt(parts[1].strip()) : 0;
            int pat = parts.length > 2 && !parts[2].isBlank() ? Integer.parseInt(parts[2].strip()) : 0;
            return new Version(maj, min, pat);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a version: " + text, e);
        }
    }

    @Override
    public int compareTo(Version o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, o.minor);
        return c != 0 ? c : Integer.compare(patch, o.patch);
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
