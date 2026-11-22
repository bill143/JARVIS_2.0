package com.jarvis.integrations.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelCatalogTest {

    @Test
    void parsesAndSortsModelIds() {
        List<String> ids = ModelCatalog.parse(
                "{\"data\":[{\"id\":\"nvidia/nemotron-nano-9b-v2\"},"
                + "{\"id\":\"meta/llama-3.3-70b-instruct\"},{\"id\":\"\"}]}");
        assertEquals(2, ids.size());                              // blank id dropped
        assertEquals("meta/llama-3.3-70b-instruct", ids.get(0));  // sorted
        assertTrue(ids.contains("nvidia/nemotron-nano-9b-v2"));
    }

    @Test
    void emptyOrUnparseableIsGracefulNotAThrow() {
        assertTrue(ModelCatalog.parse(null).isEmpty());
        assertTrue(ModelCatalog.parse("").isEmpty());
        assertTrue(ModelCatalog.parse("not json").isEmpty());
        assertTrue(ModelCatalog.parse("{\"data\":[]}").isEmpty());
    }
}
