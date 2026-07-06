package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.api.ChatRequest;
import com.jarvis.api.ChatResponse;
import com.jarvis.api.JarvisApi;
import com.jarvis.api.PlanRequest;
import com.jarvis.api.PlanResponse;
import org.junit.jupiter.api.Test;

class AppWiringTest {

    @Test
    void offlineModeAnswersEveryPromptWithEcho() {
        JarvisApi api = AppWiring.buildApi(null, "any-model");

        ChatResponse response = api.chat(new ChatRequest("console", "hello there"));
        assertTrue(response.completed());
        assertEquals(AppWiring.OFFLINE_HINT + "hello there", response.response());
    }

    @Test
    void blankKeyAlsoMeansOffline() {
        JarvisApi api = AppWiring.buildApi("  ", "any-model");
        assertTrue(api.chat(new ChatRequest("console", "hi")).completed());
        assertFalse(AppWiring.isOnline("  "));
        assertFalse(AppWiring.isOnline(null));
        assertTrue(AppWiring.isOnline("sk-something"));
    }

    @Test
    void planPathWorksEndToEndInOfflineMode() {
        JarvisApi api = AppWiring.buildApi(null, "any-model");

        PlanResponse response = api.plan(new PlanRequest("console", "tidy the desk"));
        assertTrue(response.succeeded());
        assertEquals(1, response.stepOutcomes().size());
        assertEquals(AppWiring.OFFLINE_HINT + "tidy the desk",
                response.stepOutcomes().getFirst().response());
    }

    @Test
    void sessionsAccumulateAcrossTurns() {
        JarvisApi api = AppWiring.buildApi(null, "any-model");
        api.chat(new ChatRequest("console", "one"));
        ChatResponse second = api.chat(new ChatRequest("console", "two"));
        assertTrue(second.completed());
    }
}
