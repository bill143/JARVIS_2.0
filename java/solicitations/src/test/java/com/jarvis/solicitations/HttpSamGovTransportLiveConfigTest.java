package com.jarvis.solicitations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Proves the "no restart" contract: a transport built from a live supplier flips from dormant to
 * available the moment the underlying configuration changes — the same object, never rebuilt.
 */
class HttpSamGovTransportLiveConfigTest {

    @Test
    void becomesAvailableWhenTheSuppliedKeyAppears() {
        AtomicReference<String> key = new AtomicReference<>(null);   // stands in for the saved config
        HttpSamGovTransport t = HttpSamGovTransport.resolving(key::get, () -> null);
        assertFalse(t.available(), "dormant with no key");
        key.set("api-key-set-in-app");
        assertTrue(t.available(), "same transport is now available — no restart, no rebuild");
        key.set("");
        assertFalse(t.available(), "clearing the key returns it to dormant");
    }
}
