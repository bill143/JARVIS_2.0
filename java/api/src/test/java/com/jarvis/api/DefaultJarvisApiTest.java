package com.jarvis.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.Decision;
import com.jarvis.agent.orchestration.Orchestrator;
import com.jarvis.agent.routing.PromptRouter;
import com.jarvis.agent.routing.Route;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.memory.MemoryStore;
import com.jarvis.planning.Plan;
import com.jarvis.planning.PlanStep;
import com.jarvis.planning.Planner;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultJarvisApiTest {

    private static final AgentPolicy ANSWER =
            context -> new Decision.Respond("answer: " + context.input());
    /** Invokes a missing tool forever, so every run exhausts its budget. */
    private static final AgentPolicy STUCK =
            context -> new Decision.Invoke(new ToolCall("missing", Map.of()));

    private static JarvisApi api(MemoryStore<String> memory) {
        PromptRouter<AgentPolicy> router = new PromptRouter<>(List.of(
                new Route<>("stuck",
                        prompt -> prompt.toLowerCase(Locale.ROOT).contains("stuck"), STUCK)));
        Planner planner = goal -> new Plan(goal, List.of(
                PlanStep.pending("s1", "first part"),
                PlanStep.pending("s2", "second part")));
        return new DefaultJarvisApi(
                new Orchestrator(router, ANSWER, new ToolRegistry(), planner, memory, 2));
    }

    @Test
    void chatMapsACompletedTurn() {
        ChatResponse response = api(new InMemoryStore<>())
                .chat(new ChatRequest("s1", "how are you"));

        assertEquals("s1", response.sessionId());
        assertTrue(response.completed());
        assertEquals("answer: how are you", response.response());
        assertEquals(0, response.toolSteps());
    }

    @Test
    void chatMapsBudgetExhaustionToIncomplete() {
        ChatResponse response = api(new InMemoryStore<>())
                .chat(new ChatRequest("s1", "I am stuck"));

        assertFalse(response.completed());
        assertNull(response.response());
        assertEquals(2, response.toolSteps());
    }

    @Test
    void chatTurnsAreRecordedInSessionMemory() {
        MemoryStore<String> memory = new InMemoryStore<>();
        JarvisApi api = api(memory);
        api.chat(new ChatRequest("session-x", "one"));
        api.chat(new ChatRequest("session-x", "two"));

        assertEquals(2, memory.query("session-x").size());
    }

    @Test
    void planMapsEveryStepOutcomeInOrder() {
        PlanResponse response = api(new InMemoryStore<>())
                .plan(new PlanRequest("s1", "do both parts"));

        assertTrue(response.succeeded());
        assertEquals(2, response.stepOutcomes().size());
        assertEquals("first part", response.stepOutcomes().get(0).description());
        assertEquals("answer: first part", response.stepOutcomes().get(0).response());
        assertEquals("answer: second part", response.stepOutcomes().get(1).response());
    }

    @Test
    void planWithAFailingStepReportsNotSucceeded() {
        MemoryStore<String> memory = new InMemoryStore<>();
        PromptRouter<AgentPolicy> router = new PromptRouter<>(List.of(
                new Route<>("stuck",
                        prompt -> prompt.toLowerCase(Locale.ROOT).contains("stuck"), STUCK)));
        Planner planner = goal -> new Plan(goal, List.of(
                PlanStep.pending("bad", "stuck step"),
                PlanStep.pending("good", "fine step")));
        JarvisApi api = new DefaultJarvisApi(
                new Orchestrator(router, ANSWER, new ToolRegistry(), planner, memory, 2));

        PlanResponse response = api.plan(new PlanRequest("s1", "goal"));
        assertFalse(response.succeeded());
        assertFalse(response.stepOutcomes().get(0).completed());
        assertNull(response.stepOutcomes().get(0).response());
        assertTrue(response.stepOutcomes().get(1).completed());
    }

    @Test
    void requestAndResponseInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest(" ", "p"));
        assertThrows(NullPointerException.class, () -> new ChatRequest("s", null));
        assertThrows(IllegalArgumentException.class, () -> new PlanRequest(" ", "g"));
        assertThrows(NullPointerException.class, () -> new ChatResponse("s", true, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChatResponse("s", false, "r", 0));
        assertThrows(IllegalArgumentException.class, () -> new ChatResponse("s", true, "r", -1));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanResponse.StepOutcome("d", false, "r"));
    }

    @Test
    void apiValidatesArguments() {
        JarvisApi api = api(new InMemoryStore<>());
        assertThrows(NullPointerException.class, () -> api.chat(null));
        assertThrows(NullPointerException.class, () -> api.plan(null));
        assertThrows(NullPointerException.class, () -> new DefaultJarvisApi(null));
    }
}
