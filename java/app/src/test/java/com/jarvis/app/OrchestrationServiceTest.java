package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.integrations.llm.LlmProvider;
import com.jarvis.memory.InMemoryStore;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class OrchestrationServiceTest {

    /** A fake model whose reply depends on the stage (encoded in the system prompt) and its name. */
    private static Function<ProviderSettingsService.Active, LlmProvider> fakeModels() {
        return a -> req -> {
            String sys = req.system();
            String user = req.messages().get(0).content();
            String text;
            if (sys.contains("Break the user's request")) {
                text = "research the topic\nwrite the summary";           // decompose → 2 sub-tasks
            } else if (sys.contains("Worker")) {
                text = a.name() + " did: " + user;                        // worker output
            } else if (sys.contains("arbiter") || sys.contains("Compose")) {
                text = "FINAL from " + a.name();                          // conductor arbitration
            } else {
                text = a.name() + " answers: 42";                         // flat ensemble answer
            }
            return new LlmProvider.Result(text, 5, 5);
        };
    }

    private static ProviderSettingsService providersWith(String... nameRolePairs) {
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        for (String pair : nameRolePairs) {
            String[] nr = pair.split("=");
            svc.save(nr[0], "openai", "https://x/v1", "key-" + nr[0], "m-" + nr[0], false);
            if (nr.length > 1 && !nr[1].isBlank()) {
                svc.setRole(nr[0], nr[1]);
            }
        }
        return svc;
    }

    @Test
    void ensembleRunsEveryMemberAndFusesBest() {
        ProviderSettingsService p = providersWith("A=worker", "B=worker", "C=worker");
        OrchestrationService o = new OrchestrationService(p, null, "m", fakeModels());
        OrchestrationService.EnsembleResult r = o.ensemble("what is 6*7?", "best", null);
        assertEquals(3, r.results().size());                 // all three models ran
        assertTrue(r.results().stream().allMatch(OrchestrationService.ModelResult::ok));
        assertTrue(r.answer().contains("answers: 42"));
    }

    @Test
    void ensembleVoteReturnsTheMajorityAnswer() {
        ProviderSettingsService p = providersWith("A=worker", "B=worker", "C=worker");
        OrchestrationService o = new OrchestrationService(p, null, "m", fakeModels());
        // All three fake workers reply identically after normalization → a clean majority.
        OrchestrationService.EnsembleResult r = o.ensemble("q", "vote", null);
        assertTrue(r.chosen().contains("majority"));
        assertTrue(r.answer().contains("answers: 42"));
    }

    @Test
    void ensembleJudgeUsesTheConductorToArbitrate() {
        ProviderSettingsService p = providersWith("Boss=conductor", "W1=worker", "W2=worker");
        OrchestrationService o = new OrchestrationService(p, null, "m", fakeModels());
        OrchestrationService.EnsembleResult r = o.ensemble("decide", "judge", null);
        assertEquals("Boss", r.chosen());
        assertTrue(r.answer().contains("FINAL from Boss"));
        // Trace includes the arbitration step.
        assertTrue(r.results().stream().anyMatch(m -> m.stage().equals("arbitrate")));
    }

    @Test
    void ensembleReportsWhenNothingConfigured() {
        OrchestrationService o = new OrchestrationService(providersWith(), null, "m", fakeModels());
        OrchestrationService.EnsembleResult r = o.ensemble("hi", "best", null);
        assertTrue(r.results().isEmpty());
        assertTrue(r.answer().toLowerCase().contains("no models"));
    }

    @Test
    void hierarchyDecomposesRunsWorkersAndArbitrates() {
        ProviderSettingsService p = providersWith("Boss=conductor", "W1=worker", "W2=worker");
        OrchestrationService o = new OrchestrationService(p, null, "m", fakeModels());
        OrchestrationService.HierarchyResult r = o.hierarchy("plan a product launch");
        assertEquals(2, r.plan().size());                                  // decomposed into 2
        assertTrue(r.answer().contains("FINAL from Boss"));                 // conductor arbitrated
        assertTrue(r.steps().stream().anyMatch(m -> m.stage().equals("decompose")));
        assertTrue(r.steps().stream().anyMatch(m -> m.stage().equals("work")));
        assertTrue(r.steps().stream().anyMatch(m -> m.stage().equals("arbitrate")));
    }

    @Test
    void hierarchyFallsBackToEnsembleWithoutAConductor() {
        ProviderSettingsService p = providersWith("W1=worker", "W2=worker");
        OrchestrationService o = new OrchestrationService(p, null, "m", fakeModels());
        OrchestrationService.HierarchyResult r = o.hierarchy("do a thing");
        assertFalse(r.answer().isBlank());
        assertTrue(r.plan().get(0).toLowerCase().contains("no conductor"));
    }
}
