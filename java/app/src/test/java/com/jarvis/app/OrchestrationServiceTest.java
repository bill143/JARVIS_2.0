package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.audit.AuditEntry;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.integrations.llm.LlmProvider;
import com.jarvis.integrations.openhuman.OpenHumanClient;
import com.jarvis.integrations.openhuman.OpenHumanResponse;
import com.jarvis.integrations.openhuman.OpenHumanTransport;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.memory.InMemoryStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
    void ensembleFansOutManyModelsAndIsResilientToFailures() {
        // 24 workers; the odd-indexed ones throw. Proves the virtual-thread fan-out collects every
        // result, isolates failures, and fuses deterministically — run repeatedly to catch flakiness.
        ProviderSettingsService p = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        for (int i = 0; i < 24; i++) {
            p.save("M" + i, "openai", "https://x/v1", "k", "m" + i, false);
            p.setRole("M" + i, "worker");
        }
        Function<ProviderSettingsService.Active, LlmProvider> factory = a -> req -> {
            int idx = Integer.parseInt(a.name().substring(1));
            if (idx % 2 == 1) {
                throw new RuntimeException("boom-" + idx);          // half the pool fails
            }
            return new LlmProvider.Result("ok from " + a.name(), 1, 1);
        };
        for (int trial = 0; trial < 5; trial++) {
            OrchestrationService o = new OrchestrationService(p, null, "m", factory);
            OrchestrationService.EnsembleResult r = o.ensemble("q", "best", null);
            assertEquals(24, r.results().size());                  // every model reported back
            assertEquals(12, r.results().stream().filter(OrchestrationService.ModelResult::ok).count());
            assertEquals(12, r.results().stream().filter(m -> !m.ok()).count());   // failures captured
            assertTrue(r.answer().startsWith("ok from"));          // fused from a surviving model
        }
    }

    @Test
    void hierarchyFallsBackToEnsembleWithoutAConductor() {
        ProviderSettingsService p = providersWith("W1=worker", "W2=worker");
        OrchestrationService o = new OrchestrationService(p, null, "m", fakeModels());
        OrchestrationService.HierarchyResult r = o.hierarchy("do a thing");
        assertFalse(r.answer().isBlank());
        assertTrue(r.plan().get(0).toLowerCase().contains("no conductor"));
    }

    // ---- Stage 4: Tier-2 routing wired into the callOne hot path -----------------------------

    private static RoutingSettings routingSettings(Map<String, String> env) {
        return new RoutingSettings(new ConnectorSettingsService(new InMemoryStore<>(), env::get));
    }

    /** A real {@link OpenHumanClient} backed by a scripted fake transport (no network). */
    private static OpenHumanClient openHumanClient(String replyText, AtomicInteger callCounter) {
        OpenHumanTransport transport = (method, path, body) -> {
            if (callCounter != null && "/rpc".equals(path)) {
                callCounter.incrementAndGet();
            }
            if ("/health".equals(path)) {
                return new OpenHumanResponse(200, "{}");
            }
            return new OpenHumanResponse(200, "{\"result\":{\"result\":\"" + replyText + "\"}}");
        };
        return new OpenHumanClient(transport);
    }

    private static Function<ProviderSettingsService.Active, LlmProvider> alwaysThrows(
            String message, AtomicInteger callCounter) {
        return a -> req -> {
            if (callCounter != null) {
                callCounter.incrementAndGet();
            }
            throw new RuntimeException(message);
        };
    }

    @Test
    void routingIsInertByDefaultAndNeverCallsOpenHuman() {
        ProviderSettingsService p = providersWith("A=worker");
        AtomicInteger openHumanCalls = new AtomicInteger();
        RoutingSettings routing = routingSettings(Map.of()); // JARVIS_OPENHUMAN_ENABLED unset -> false
        OpenHumanClient openHuman = openHumanClient("should never be used", openHumanCalls);
        OrchestrationService o = new OrchestrationService(p, null, "m",
                alwaysThrows("HTTP 429 too many requests", null), routing, openHuman);

        OrchestrationService.EnsembleResult r = o.ensemble("q", "best", null);

        assertTrue(r.results().stream().noneMatch(OrchestrationService.ModelResult::ok));
        assertEquals(0, openHumanCalls.get());
    }

    @Test
    void routingFailsOverToOpenHumanWhenThePrimaryIsRateLimited() {
        ProviderSettingsService p = providersWith("A=worker");
        RoutingSettings routing = routingSettings(Map.of(
                "JARVIS_OPENHUMAN_ENABLED", "true",
                "JARVIS_ROUTING_FAILOVER_ENABLED", "true",
                "JARVIS_ROUTING_MAX_RETRIES", "1"));
        OpenHumanClient openHuman = openHumanClient("openhuman saved the day", null);
        OrchestrationService o = new OrchestrationService(p, null, "m",
                alwaysThrows("HTTP 429 too many requests", null), routing, openHuman);

        OrchestrationService.EnsembleResult r = o.ensemble("q", "best", null);

        assertTrue(r.answer().contains("openhuman saved the day"));
    }

    @Test
    void routingHonorsFailoverDisabledEvenWhenOpenHumanEnabled() {
        ProviderSettingsService p = providersWith("A=worker");
        AtomicInteger openHumanCalls = new AtomicInteger();
        RoutingSettings routing = routingSettings(Map.of(
                "JARVIS_OPENHUMAN_ENABLED", "true",
                "JARVIS_ROUTING_FAILOVER_ENABLED", "false"));
        OpenHumanClient openHuman = openHumanClient("should never be used", openHumanCalls);
        OrchestrationService o = new OrchestrationService(p, null, "m",
                alwaysThrows("HTTP 429 too many requests", null), routing, openHuman);

        OrchestrationService.EnsembleResult r = o.ensemble("q", "best", null);

        assertTrue(r.results().stream().noneMatch(OrchestrationService.ModelResult::ok));
        assertEquals(0, openHumanCalls.get());
    }

    @Test
    void routingEmitsARouteDecisionAuditEventWithoutLeakingThePrompt() {
        ProviderSettingsService p = providersWith("A=worker");
        RoutingSettings routing = routingSettings(Map.of(
                "JARVIS_OPENHUMAN_ENABLED", "true",
                "JARVIS_ROUTING_FAILOVER_ENABLED", "true",
                "JARVIS_ROUTING_MAX_RETRIES", "1"));
        OpenHumanClient openHuman = openHumanClient("fallback reply", null);
        AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());
        OrchestrationService o = new OrchestrationService(p, audit, "m",
                alwaysThrows("HTTP 429 too many requests", null), routing, openHuman);

        o.ensemble("super secret prompt text", "best", null);

        List<AuditEntry> routeEvents = audit.recent(50).stream()
                .filter(e -> e.event().action().equals("route_decision")).toList();
        assertFalse(routeEvents.isEmpty());
        String detail = routeEvents.get(0).event().detail();
        assertTrue(detail.contains("tier=TIER2_OPENHUMAN"));
        assertTrue(detail.contains("reason=PRIMARY_RATE_LIMITED"));
        assertTrue(detail.contains("corr="));
        assertFalse(detail.contains("super secret prompt text")); // no user payload in the audit log
        assertFalse(detail.toLowerCase().contains("bearer"));     // no credentials either
    }

    @Test
    void circuitBreakerOnThePrimaryFailsFastButStillFailsOverToOpenHuman() {
        ProviderSettingsService p = providersWith("A=worker");
        RoutingSettings routing = routingSettings(Map.of(
                "JARVIS_OPENHUMAN_ENABLED", "true",
                "JARVIS_ROUTING_FAILOVER_ENABLED", "true",
                "JARVIS_ROUTING_MAX_RETRIES", "1",
                "JARVIS_ROUTING_BREAKER_FAIL_THRESHOLD", "2",
                "JARVIS_ROUTING_BREAKER_WINDOW_SEC", "60",
                "JARVIS_ROUTING_BREAKER_COOLDOWN_SEC", "30"));
        AtomicInteger primaryCalls = new AtomicInteger();
        OpenHumanClient openHuman = openHumanClient("fallback reply", null);
        OrchestrationService o = new OrchestrationService(p, null, "m",
                alwaysThrows("HTTP 500 boom", primaryCalls), routing, openHuman);

        o.ensemble("q1", "best", null);
        o.ensemble("q2", "best", null); // 2 consecutive failures crosses the threshold=2 -> breaker OPENs
        assertEquals(2, primaryCalls.get());

        OrchestrationService.EnsembleResult third = o.ensemble("q3", "best", null);
        assertEquals(2, primaryCalls.get()); // breaker short-circuited the primary — no 3rd call
        assertTrue(third.answer().contains("fallback reply")); // still failed over to OpenHuman
    }

    // ---- blank-model guard (regression: claude-sonnet-5 sent to an OpenAI endpoint = 404) ----

    @Test
    void blankModelOnOpenAiKindFailsFastInsteadOfGuessing() {
        ProviderSettingsService p = providersWith();
        p.save("NoModel", "openai", "https://x/v1", "key", "", false);   // blank model
        AtomicInteger factoryCalls = new AtomicInteger();
        OrchestrationService o = new OrchestrationService(p, null, "claude-sonnet-5", a -> {
            factoryCalls.incrementAndGet();
            return req -> new LlmProvider.Result("should never run", 1, 1);
        });
        ProviderSettingsService.Active noModel = p.allConfigured().stream()
                .filter(a -> a.name().equals("NoModel")).findFirst().orElseThrow();
        OrchestrationService.ModelResult r = o.callOne(noModel, "sys", "hi", 64, "test");
        assertFalse(r.ok());
        assertTrue(r.error().contains("no model selected"));
        assertTrue(r.error().contains("Fetch live models"));
        assertEquals(0, factoryCalls.get());   // the doomed call was never even attempted
    }

    @Test
    void blankModelOnAnthropicKindStillFallsBackToDefault() {
        ProviderSettingsService p = providersWith();
        p.save("Claude", "anthropic", "", "sk-ant-key", "", false);
        OrchestrationService o = new OrchestrationService(p, null, "claude-sonnet-5",
                a -> req -> new LlmProvider.Result("model=" + req.model(), 1, 1));
        ProviderSettingsService.Active claude = p.allConfigured().stream()
                .filter(a -> a.name().equals("Claude")).findFirst().orElseThrow();
        OrchestrationService.ModelResult r = o.callOne(claude, "sys", "hi", 64, "test");
        assertTrue(r.ok());
        assertTrue(r.text().contains("claude-sonnet-5"));   // default id valid here, and only here
    }
}
