package com.jarvis.integrations.openhuman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class OpenHumanClientTest {

    /** Recording fake transport: captures the last request, returns a scripted response. */
    private static final class FakeTransport implements OpenHumanTransport {
        String method;
        String path;
        String body;
        OpenHumanResponse next;

        FakeTransport(OpenHumanResponse next) {
            this.next = next;
        }

        @Override
        public OpenHumanResponse send(String method, String path, String jsonBody) {
            this.method = method;
            this.path = path;
            this.body = jsonBody;
            return next;
        }
    }

    @Test
    void healthyProbesTheHealthEndpoint() {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200, "{}"));
        assertTrue(new OpenHumanClient(t).healthy());
        assertEquals("GET", t.method);
        assertEquals("/health", t.path);
    }

    @Test
    void healthReportsDegradedWithoutBeingUnreachable() throws Exception {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200, "{\"degraded\":true}"));
        OpenHumanHealthStatus status = new OpenHumanClient(t).health();
        assertTrue(status.reachable());
        assertTrue(status.degraded());
        assertEquals(200, status.httpStatus());
    }

    @Test
    void healthReportsUnreachableOnACriticalFailure() throws Exception {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(503, "{\"degraded\":true}"));
        OpenHumanHealthStatus status = new OpenHumanClient(t).health();
        assertFalse(status.reachable());
        assertEquals(503, status.httpStatus());
    }

    @Test
    void healthOnAnUnconfiguredTransportDoesNotTouchTheNetwork() throws Exception {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200, "{}"));
        OpenHumanTransport dormant = new OpenHumanTransport() {
            @Override
            public OpenHumanResponse send(String method, String path, String jsonBody) {
                return t.send(method, path, jsonBody);
            }

            @Override
            public boolean available() {
                return false;
            }
        };
        assertEquals(OpenHumanHealthStatus.NOT_CONFIGURED, new OpenHumanClient(dormant).health());
        assertNull(t.method); // never called
    }

    @Test
    void consultSendsAJsonRpcCallAndUnwrapsTheResult() throws Exception {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"result\":\"use a 60V pack\","
                        + "\"logs\":[\"agent chat completed\"]}}"));
        String reply = new OpenHumanClient(t).consult("what battery?", "go-kart");
        assertEquals("/rpc", t.path);
        assertEquals("POST", t.method);
        assertTrue(t.body.contains("\"method\":\"openhuman.agent_chat\""));
        assertTrue(t.body.contains("Context: go-kart"));
        assertTrue(t.body.contains("what battery?"));
        assertEquals("use a 60V pack", reply);
    }

    @Test
    void memorySearchDefaultsToTheDefaultNamespace() throws Exception {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200,
                "{\"result\":{\"summary\":\"3 notes about batteries\"}}"));
        String out = new OpenHumanClient(t).memorySearch("battery", 5);
        assertTrue(t.body.contains("\"method\":\"openhuman.memory_query_namespace\""));
        assertTrue(t.body.contains("\"namespace\":\"default\""));
        assertTrue(t.body.contains("\"query\":\"battery\""));
        assertEquals("3 notes about batteries", out);
    }

    @Test
    void memorySearchUsesTheConfiguredMethodNameAndNamespace() throws Exception {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200,
                "{\"result\":{\"summary\":\"3 notes about batteries\"}}"));
        String out = new OpenHumanClient(t, "mem.find", "chat").memorySearch("kart", "battery", 5);
        assertTrue(t.body.contains("\"method\":\"mem.find\""));
        assertTrue(t.body.contains("\"namespace\":\"kart\""));
        assertEquals("3 notes about batteries", out);
    }

    @Test
    void aJsonRpcErrorBecomesAnException() {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200,
                "{\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}"));
        IOException e = assertThrows(IOException.class,
                () -> new OpenHumanClient(t).consult("hi", null));
        assertTrue(e.getMessage().contains("Method not found"));
    }

    @Test
    void memoryWriteIsDeniedByDefaultAndNeverTouchesTheNetwork() {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200, "{\"result\":{\"document_id\":\"d1\"}}"));
        OpenHumanClient client = new OpenHumanClient(t);
        SecurityException e = assertThrows(SecurityException.class,
                () -> client.memoryWrite("kart-notes", "battery-spec", "Battery", "60V pack"));
        assertTrue(e.getMessage().contains("kart-notes"));
        assertTrue(e.getMessage().contains("battery-spec"));
        assertNull(t.method); // the deny happens before any transport call
    }

    @Test
    void memoryWriteSendsTheRealRpcShapeWhenThePolicyPermits() throws Exception {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200, "{\"result\":{\"document_id\":\"doc-42\"}}"));
        OpenHumanClient client = new OpenHumanClient(t, OpenHumanClient.DEFAULT_MEMORY_SEARCH_METHOD,
                OpenHumanClient.DEFAULT_CONSULT_METHOD, OpenHumanClient.DEFAULT_MEMORY_WRITE_METHOD,
                (namespace, key) -> "kart-notes".equals(namespace) && "battery-spec".equals(key));

        String docId = client.memoryWrite("kart-notes", "battery-spec", "Battery", "60V pack");

        assertEquals("/rpc", t.path);
        assertTrue(t.body.contains("\"method\":\"openhuman.memory_doc_put\""));
        assertTrue(t.body.contains("\"namespace\":\"kart-notes\""));
        assertTrue(t.body.contains("\"key\":\"battery-spec\""));
        assertTrue(t.body.contains("\"content\":\"60V pack\""));
        assertEquals("doc-42", docId);
    }

    @Test
    void memoryWritePolicyIsScopedToTheExactNamespaceAndKey() {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200, "{\"result\":{\"document_id\":\"x\"}}"));
        OpenHumanClient client = new OpenHumanClient(t, OpenHumanClient.DEFAULT_MEMORY_SEARCH_METHOD,
                OpenHumanClient.DEFAULT_CONSULT_METHOD, OpenHumanClient.DEFAULT_MEMORY_WRITE_METHOD,
                (namespace, key) -> "kart-notes".equals(namespace) && "battery-spec".equals(key));

        assertThrows(SecurityException.class,
                () -> client.memoryWrite("other-namespace", "battery-spec", "t", "c"));
        assertNull(t.method);
    }

    @Test
    void httpTransportIsDormantWithoutUrlOrToken() {
        assertFalse(new HttpOpenHumanTransport(null, null).available());
        assertFalse(new HttpOpenHumanTransport("http://127.0.0.1:8765", null).available());
        assertFalse(new HttpOpenHumanTransport(null, "tok").available());
        assertTrue(new HttpOpenHumanTransport("http://127.0.0.1:8765", "tok").available());
    }

    @Test
    void dormantTransportErrorNamesTheEnvVarsNotAToken() {
        HttpOpenHumanTransport dormant = new HttpOpenHumanTransport(null, null);
        IOException e = assertThrows(IOException.class, () -> dormant.send("GET", "/health", null));
        assertTrue(e.getMessage().contains(HttpOpenHumanTransport.URL_ENV));
        assertTrue(e.getMessage().contains(HttpOpenHumanTransport.TOKEN_ENV));
    }
}
