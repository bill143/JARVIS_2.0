package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.api.JarvisApi;
import com.jarvis.discussion.DiscussionRunner;
import com.jarvis.integrations.llm.LlmProvider;
import com.jarvis.integrations.openhuman.OpenHumanClient;
import com.jarvis.integrations.openhuman.OpenHumanResponse;
import com.jarvis.memory.InMemoryStore;
import org.junit.jupiter.api.Test;

class DiscussionServiceTest {

    private static JarvisApi echoApi() {
        return AppWiring.buildApi(null, "m", new InMemoryStore<>());
    }

    /** Providers with an active "Chair" brain and a second, non-active "SecondOpinion" model. */
    private static ProviderSettingsService twoProviders() {
        ProviderSettingsService p = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        p.save("Chair", "openai", "https://x/v1", "k1", "m-chair", true);
        p.save("SecondOpinion", "openai", "https://x/v1", "k2", "m-second", false);
        return p;
    }

    private static OrchestrationService fakeOrchestration(ProviderSettingsService p) {
        return new OrchestrationService(p, null, "m",
                a -> req -> new LlmProvider.Result("advice from " + a.name(), 1, 1));
    }

    // ---- advisor fallback (regression: feature dormant without an OpenHuman core) ----

    @Test
    void advisorFallsBackToARosterModelWhenOpenHumanIsAbsent() {
        ProviderSettingsService p = twoProviders();
        DiscussionService svc = new DiscussionService(echoApi(), null, null, null,
                p, fakeOrchestration(p));
        assertTrue(svc.advisorAvailable());
        // Prefers the provider that is NOT the active chat brain — a real second opinion.
        assertEquals("model:SecondOpinion", svc.advisorSource());
    }

    @Test
    void singleProviderStillAdvisesEvenIfItIsTheActiveBrain() {
        ProviderSettingsService p = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        p.save("Only", "openai", "https://x/v1", "k", "m", true);
        DiscussionService svc = new DiscussionService(echoApi(), null, null, null,
                p, fakeOrchestration(p));
        assertTrue(svc.advisorAvailable());
        assertEquals("model:Only", svc.advisorSource());
    }

    @Test
    void discussionRunsWithTheModelAdvisor() {
        ProviderSettingsService p = twoProviders();
        DiscussionService svc = new DiscussionService(echoApi(), null, null, null,
                p, fakeOrchestration(p));
        DiscussionRunner.Discussion d = svc.run("Should we bid the riverfront project?");
        assertFalse(d.rounds().isEmpty());
        DiscussionRunner.Round first = d.rounds().get(0);
        assertFalse(first.failed());
        assertEquals("advice from SecondOpinion", first.answer());
        assertFalse(d.outcome().isBlank());   // chair synthesized an outcome
    }

    @Test
    void dormantWithNoAdvisorAndNoProviders() {
        DiscussionService svc = new DiscussionService(echoApi(), null, null, null);
        assertFalse(svc.advisorAvailable());
        assertEquals("", svc.advisorSource());
        // A run still terminates: the advisor failure becomes an explicit error round.
        DiscussionRunner.Discussion d = svc.run("anything");
        assertFalse(d.rounds().isEmpty());
        assertTrue(d.rounds().get(0).failed());
        assertFalse(d.converged());
    }

    @Test
    void openHumanTakesPriorityWhenHealthy() {
        // A fake OpenHuman core that answers /health with 200 → available()==true.
        com.jarvis.integrations.openhuman.OpenHumanTransport healthy =
                (method, path, body) -> new OpenHumanResponse(200, "{}");
        OpenHumanClient openhuman = new OpenHumanClient(healthy);
        ProviderSettingsService p = twoProviders();
        DiscussionService svc = new DiscussionService(echoApi(), openhuman, null, null,
                p, fakeOrchestration(p));
        assertTrue(svc.advisorAvailable());
        assertEquals("openhuman", svc.advisorSource());   // core outranks the model fallback
    }
}
