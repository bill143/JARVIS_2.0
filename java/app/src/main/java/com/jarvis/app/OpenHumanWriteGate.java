package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.integrations.openhuman.OpenHumanClient;
import com.jarvis.memory.MemoryStore;
import com.jarvis.tools.RiskTier;
import java.util.ArrayList;
import java.util.List;

/**
 * The default-deny authorization gate for {@link OpenHumanClient#memoryWrite}. Mirrors
 * {@link GatedLaneService}'s pattern — default-deny, an absolute harm denylist that always wins,
 * every decision audited — rather than reusing that class directly: {@code GatedLaneService} gates
 * free-text <em>tasks</em> against a self-hosted model provider, a different shape of decision than
 * authorizing a namespace/key <em>write</em> to OpenHuman's memory.
 *
 * <p>This exists because OpenHuman's own direct {@code /rpc} write path does not re-run its own
 * write-approval policy (verified against its source — that check only exists on its separate
 * MCP-tool path); a bearer token alone is otherwise sufficient to write there. This gate is JARVIS's
 * only protection on that call, and {@link OpenHumanClient#memoryWrite} checks it <em>before</em> any
 * network request is made, so a denial never touches the loopback network.
 *
 * <p>Audit details include the target {@code namespace} for traceability, but never the write's
 * {@code key}/{@code title}/{@code content} — those carry the actual payload text.
 */
final class OpenHumanWriteGate implements OpenHumanClient.WritePolicy {

    static final String SCOPE = "openhuman-write-gate";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The gate's configuration, as shown to the operator. */
    record Config(boolean enabled, List<String> allow) {
    }

    private final MemoryStore<String> store;
    private final AuditLog audit;   // nullable

    OpenHumanWriteGate(MemoryStore<String> store, AuditLog audit) {
        this.store = store;
        this.audit = audit;
    }

    Config config() {
        JsonNode c = store.get(SCOPE, "config").map(e -> parse(e.value()))
                .orElse(MAPPER.createObjectNode());
        List<String> allow = new ArrayList<>();
        c.path("allow").forEach(n -> allow.add(n.asText()));
        return new Config(c.path("enabled").asBoolean(false), allow);
    }

    /** Persists the gate config. Disabled with an empty allowlist by default (default-deny). */
    void setConfig(boolean enabled, List<String> allow) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("enabled", enabled);
        var arr = c.putArray("allow");
        if (allow != null) {
            for (String a : allow) {
                if (a != null && !a.isBlank()) {
                    arr.add(a.strip());
                }
            }
        }
        store.put(SCOPE, "config", c.toString());
        record("openhuman_write_gate_config", "enabled=" + enabled, AuditOutcome.SUCCESS);
    }

    @Override
    public boolean permits(String namespace, String key) {
        String text = ((namespace == null ? "" : namespace) + " " + (key == null ? "" : key))
                .toLowerCase();
        for (String bad : GatedLaneService.DENYLIST) {
            if (text.contains(bad)) {
                record("openhuman_write_gate", "REJECTED — matches an absolute harm category "
                        + "(namespace=" + namespace + ")", AuditOutcome.FAILURE);
                return false;
            }
        }
        Config cfg = config();
        if (!cfg.enabled()) {
            record("openhuman_write_gate",
                    "REJECTED — the gate is disabled (namespace=" + namespace + ")", AuditOutcome.FAILURE);
            return false;
        }
        if (cfg.allow().isEmpty()) {
            record("openhuman_write_gate", "REJECTED — default-deny: no allowlist scope configured "
                    + "(namespace=" + namespace + ")", AuditOutcome.FAILURE);
            return false;
        }
        for (String term : cfg.allow()) {
            if (!term.isBlank() && text.contains(term.toLowerCase())) {
                record("openhuman_write_gate", "APPROVED — matches allowlist scope '" + term
                        + "' (namespace=" + namespace + ")", AuditOutcome.SUCCESS);
                return true;
            }
        }
        record("openhuman_write_gate", "REJECTED — outside the configured allowlist (namespace="
                + namespace + ")", AuditOutcome.FAILURE);
        return false;
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private void record(String action, String detail, AuditOutcome outcome) {
        if (audit == null) {
            return;
        }
        audit.record(new AuditEvent(AuditCategory.TOOL_INVOCATION, action, AuditTrigger.USER,
                RiskTier.MUTATING, outcome, detail));
    }
}
