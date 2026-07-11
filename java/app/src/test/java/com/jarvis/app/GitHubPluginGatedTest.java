package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.audit.AuditedTool;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.integrations.PluginManager;
import com.jarvis.integrations.github.GitHubPlugin;
import com.jarvis.integrations.github.GitHubResponse;
import com.jarvis.integrations.github.GitHubTransport;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.registry.HealthTrackingTool;
import com.jarvis.registry.PluginRegistry;
import com.jarvis.security.AuthorizingTool;
import com.jarvis.security.PermissionGate;
import com.jarvis.security.PermissionLevel;
import com.jarvis.security.PermissionOutcome;
import com.jarvis.security.PermissionPolicy;
import com.jarvis.tools.RiskTier;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * CHECKPOINT 1 for the Phase 5 GitHub reference integration: proves, end-to-end through the real
 * governance stack (manifest risk tiers → HealthTracking → Audit → Authorizing gate), that a
 * READ_ONLY GitHub call runs without a prompt and a MUTATING call is blocked until the user
 * confirms — a denied confirmation never reaches the network.
 */
class GitHubPluginGatedTest {

    /** Fake transport that counts calls, so we can prove a denied write never hits the wire. */
    private static final class CountingTransport implements GitHubTransport {
        final AtomicInteger calls = new AtomicInteger();
        final GitHubResponse response;

        CountingTransport(GitHubResponse response) {
            this.response = response;
        }

        @Override
        public GitHubResponse send(String method, String path, String jsonBody) {
            calls.incrementAndGet();
            return response;
        }
    }

    private final PluginRegistry registry = AppWiring.pluginRegistry();
    private final AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());

    /** Wraps a raw tool exactly as {@code AppWiring.governedRegistry} does, with a supplied gate. */
    private Tool govern(Tool raw, PermissionPolicy policy, PermissionGate gate) {
        RiskTier tier = registry.riskTier(raw.name());
        Tool tracked = new HealthTrackingTool(raw, registry);
        Tool audited = new AuditedTool(tracked, audit, tier, AuditTrigger.USER);
        return new AuthorizingTool(audited, tier, policy, gate, audit);
    }

    private Tool rawTool(GitHubTransport transport, String name) {
        ToolRegistry tools = new ToolRegistry();
        new PluginManager(tools).install(new GitHubPlugin(transport));
        return tools.lookup(name).orElseThrow();
    }

    @Test
    void readOnlyCallRunsWithoutAnyPrompt() {
        CountingTransport t = new CountingTransport(new GitHubResponse(200, "[]"));
        PermissionGate denyEverything = (tool, tier, detail) -> {
            throw new AssertionError("READ_ONLY must not be gated: " + tool);
        };
        Tool listRepos = govern(rawTool(t, "github_list_repos"),
                new PermissionPolicy(PermissionLevel.MUTATING), denyEverything);

        var result = listRepos.execute(ToolCall.of("github_list_repos"));

        assertTrue(result.success());
        assertEquals("No repositories.", result.output());
        assertEquals(1, t.calls.get());   // the read hit the (fake) API, ungated
    }

    @Test
    void mutatingCallIsBlockedWhenTheUserDeniesAndNeverHitsTheNetwork() {
        CountingTransport t = new CountingTransport(
                new GitHubResponse(201, "{\"number\":1,\"html_url\":\"x\"}"));
        AtomicInteger prompts = new AtomicInteger();
        PermissionGate deny = (tool, tier, detail) -> {
            prompts.incrementAndGet();
            return PermissionOutcome.DENIED;
        };
        Tool createIssue = govern(rawTool(t, "github_create_issue"),
                new PermissionPolicy(PermissionLevel.MUTATING), deny);

        var result = createIssue.execute(new ToolCall("github_create_issue",
                Map.of("owner", "o", "repo", "r", "title", "hi")));

        assertFalse(result.success());
        assertEquals(1, prompts.get());    // the user WAS asked
        assertEquals(0, t.calls.get());    // and the write never reached the API
    }

    @Test
    void mutatingCallProceedsOnceTheUserConfirms() {
        CountingTransport t = new CountingTransport(
                new GitHubResponse(201, "{\"number\":1,\"html_url\":\"https://github.com/o/r/issues/1\"}"));
        PermissionGate allow = (tool, tier, detail) -> PermissionOutcome.ALLOWED;
        Tool createIssue = govern(rawTool(t, "github_create_issue"),
                new PermissionPolicy(PermissionLevel.MUTATING), allow);

        var result = createIssue.execute(new ToolCall("github_create_issue",
                Map.of("owner", "o", "repo", "r", "title", "hi")));

        assertTrue(result.success());
        assertTrue(result.output().contains("#1"));
        assertEquals(1, t.calls.get());    // confirmed → the write executed
    }

    @Test
    void manifestTiersMatchTheDirective() {
        assertEquals(RiskTier.READ_ONLY, registry.riskTier("github_list_repos"));
        assertEquals(RiskTier.READ_ONLY, registry.riskTier("github_read_file"));
        assertEquals(RiskTier.MUTATING, registry.riskTier("github_create_issue"));
        assertEquals(RiskTier.MUTATING, registry.riskTier("github_open_pull_request"));
    }
}
