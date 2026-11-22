package com.jarvis.integrations.openhuman;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JARVIS-side verification of OpenHuman's private-mode constraints (RFC 0001, §6). The source of
 * truth for disabling public-network / crypto / marketplace features is OpenHuman's own
 * {@code config.toml}; JARVIS cannot disable them. What JARVIS <em>can</em> do is <b>verify</b>: scan
 * the core's advertised {@code /schema} for markers of those features and raise a prominent warning
 * so a misconfiguration never passes silently.
 *
 * <p>Pure string scan — deliberately conservative and dependency-free.
 */
public final class PrivateModeAssertion {

    /** Markers that indicate a public-network / payment / marketplace capability is present. */
    private static final String[] MARKERS = {
        "tiny.place", "tinyplace", "x402", "usdc", "marketplace", "wallet", "bounty", "bounties"
    };

    private PrivateModeAssertion() {
    }

    /**
     * Returns the private-mode concerns found in {@code schemaJson} (the body of {@code /schema}).
     * An empty list means the schema advertises none of the flagged capabilities.
     */
    public static List<String> scan(String schemaJson) {
        List<String> found = new ArrayList<>();
        if (schemaJson == null || schemaJson.isBlank()) {
            return found;
        }
        String hay = schemaJson.toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (hay.contains(marker) && !found.contains(marker)) {
                found.add(marker);
            }
        }
        return found;
    }

    /** Whether the schema is clean of all flagged markers. */
    public static boolean isPrivate(String schemaJson) {
        return scan(schemaJson).isEmpty();
    }
}
