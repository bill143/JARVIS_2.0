package com.jarvis.app;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Total Recall: recognises a natural-language "remember that …" instruction in a chat message and
 * extracts the fact to store. Parsing is deliberately anchored at the start of the message so a
 * genuine question ("do you remember when …") is NOT mistaken for a command to store something.
 */
final class RecallCapture {

    // Leading imperative → capture the remainder as the fact. Anchored at start; case-insensitive.
    private static final Pattern PATTERN = Pattern.compile(
            "^\\s*(?:hey\\s+jarvis[,\\s]+|jarvis[,\\s]+)?(?:please\\s+|can you\\s+|could you\\s+)?"
                    + "(?:remember that|remember to|remember|note that|make a note that|"
                    + "make a note:?|jot down|keep in mind that|keep in mind|don't let me forget that|"
                    + "don't let me forget)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final int TITLE_MAX = 48;

    private RecallCapture() {
    }

    /** A fact to store: a short title plus the full remembered text. */
    record Fact(String title, String content) {
    }

    /** Extracts a fact when {@code prompt} is a "remember …" instruction; empty otherwise. */
    static Optional<Fact> parse(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(prompt.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        String fact = m.group(1).strip().replaceAll("\\s+", " ");
        // Trim a single trailing sentence terminator for a cleaner stored note.
        fact = fact.replaceAll("[\\s.!]+$", "").strip();
        if (fact.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Fact(titleFrom(fact), fact));
    }

    private static String titleFrom(String fact) {
        String t = fact;
        if (t.length() > TITLE_MAX) {
            int cut = t.lastIndexOf(' ', TITLE_MAX);
            t = (cut > 12 ? t.substring(0, cut) : t.substring(0, TITLE_MAX)).strip() + "…";
        }
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
