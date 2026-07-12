package com.jarvis.integrations.openhuman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void consultSendsAJsonRpcCallAndUnwrapsTheResult() throws Exception {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"text\":\"use a 60V pack\"}}"));
        String reply = new OpenHumanClient(t).consult("what battery?", "go-kart");
        assertEquals("/rpc", t.path);
        assertEquals("POST", t.method);
        assertTrue(t.body.contains("\"method\":\"agent.chat\""));
        assertTrue(t.body.contains("\"message\":\"what battery?\""));
        assertEquals("use a 60V pack", reply);
    }

    @Test
    void memorySearchUsesTheConfiguredMethodName() throws Exception {
        FakeTransport t = new FakeTransport(new OpenHumanResponse(200,
                "{\"result\":{\"summary\":\"3 notes about batteries\"}}"));
        String out = new OpenHumanClient(t, "mem.find", "chat").memorySearch("battery", 5);
        assertTrue(t.body.contains("\"method\":\"mem.find\""));
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
