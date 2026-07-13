package com.jarvis.integrations.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.integrations.llm.LlmProvider.Message;
import com.jarvis.integrations.llm.LlmProvider.Request;
import com.jarvis.integrations.llm.LlmProvider.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderTest {

    @Test
    void serializesSystemAndMessagesInOpenAiShape() {
        String body = OpenAiCompatibleProvider.serialize(new Request(
                "nvidia/llama-3.3-nemotron-super-49b-v1", "You are JARVIS.",
                List.of(new Message("user", "hi"), new Message("assistant", "hello")), 1024));
        assertTrue(body.contains("\"model\":\"nvidia/llama-3.3-nemotron-super-49b-v1\""));
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"content\":\"You are JARVIS.\""));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("\"role\":\"assistant\""));
        assertTrue(body.contains("\"max_tokens\":1024"));
    }

    @Test
    void parsesChoiceContentAndUsage() {
        OpenAiCompatibleProvider p = new OpenAiCompatibleProvider(req -> "unused");
        Result r = p.parse("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"42\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}");
        assertEquals("42", r.text());
        assertEquals(11, r.inputTokens());
        assertEquals(3, r.outputTokens());
    }

    @Test
    void completeRoundTripsThroughTheTransport() throws Exception {
        String[] sent = new String[1];
        LlmProvider p = new OpenAiCompatibleProvider(requestJson -> {
            sent[0] = requestJson;
            return "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        });
        Result r = p.complete(new Request("m", "sys", List.of(new Message("user", "q")), 256));
        assertEquals("ok", r.text());
        assertTrue(sent[0].contains("\"model\":\"m\""));
    }
}
