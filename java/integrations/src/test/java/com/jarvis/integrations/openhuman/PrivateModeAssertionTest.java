package com.jarvis.integrations.openhuman;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrivateModeAssertionTest {

    @Test
    void aCleanSchemaHasNoConcerns() {
        String schema = "{\"methods\":[\"memory.search\",\"memory.add\",\"agent.chat\"]}";
        assertTrue(PrivateModeAssertion.scan(schema).isEmpty());
        assertTrue(PrivateModeAssertion.isPrivate(schema));
    }

    @Test
    void flagsPublicNetworkAndPaymentMarkers() {
        String schema = "{\"features\":[\"tiny.place\",\"x402\",\"marketplace\",\"wallet\"]}";
        assertFalse(PrivateModeAssertion.isPrivate(schema));
        assertTrue(PrivateModeAssertion.scan(schema).contains("x402"));
        assertTrue(PrivateModeAssertion.scan(schema).contains("marketplace"));
        assertTrue(PrivateModeAssertion.scan(schema).contains("tiny.place"));
    }

    @Test
    void isCaseInsensitiveAndNullSafe() {
        assertFalse(PrivateModeAssertion.isPrivate("{\"x\":\"USDC bounty\"}"));
        assertTrue(PrivateModeAssertion.scan(null).isEmpty());
        assertTrue(PrivateModeAssertion.scan("").isEmpty());
    }
}
