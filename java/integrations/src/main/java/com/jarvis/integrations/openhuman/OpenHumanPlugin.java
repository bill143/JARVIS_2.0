package com.jarvis.integrations.openhuman;

import com.jarvis.integrations.Plugin;
import com.jarvis.integrations.PluginDescriptor;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.util.List;
import java.util.Objects;

/**
 * OpenHuman agent tools — all READ_ONLY. OpenHuman runs <em>under</em> JARVIS as an advisor: JARVIS
 * consults its Memory Tree and reasoning, but never asks it to act. There are deliberately no
 * write/messaging/payment tools here (OpenHuman's outbound messaging and x402 payments stay off).
 *
 * <p>Failures — including "not configured" when the core isn't running — are failed
 * {@link ToolResult}s, never throws, so the assistant degrades gracefully.
 */
public final class OpenHumanPlugin implements Plugin {

    private final OpenHumanClient client;

    public OpenHumanPlugin(OpenHumanTransport transport) {
        this(new OpenHumanClient(transport));
    }

    public OpenHumanPlugin(OpenHumanClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("openhuman", "0.1.0",
                "OpenHuman advisor (read-only): status, capability schema, Memory Tree search, "
                        + "and consult");
    }

    @Override
    public List<Tool> tools() {
        return List.of(status(), schema(), memorySearch(), consult());
    }

    private Tool status() {
        return tool("openhuman_status",
                "Check whether the OpenHuman advisor is connected and healthy.",
                call -> ToolResult.ok(client.healthy() ? "OpenHuman: connected and healthy."
                        : "OpenHuman: not connected (start 'openhuman-core serve' and configure it)."));
    }

    private Tool schema() {
        return tool("openhuman_schema",
                "Fetch OpenHuman's capability schema (its available RPC methods).",
                call -> ToolResult.ok(client.schema()));
    }

    private Tool memorySearch() {
        return tool("openhuman_memory_search",
                "Search OpenHuman's Memory Tree by meaning. Args: query (required), max (default 10).",
                call -> ToolResult.ok(client.memorySearch(str(call, "query"), intArg(call, "max", 10))));
    }

    private Tool consult() {
        return tool("openhuman_consult",
                "Ask the OpenHuman advisor a question (uses its memory + research). Args: question "
                        + "(required), context (optional).",
                call -> ToolResult.ok(client.consult(str(call, "question"), str(call, "context"))));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private Tool tool(String name, String desc, ThrowingBody body) {
        return new Tool() {
            public String name() {
                return name;
            }

            public String description() {
                return desc;
            }

            public ToolResult execute(ToolCall call) {
                try {
                    return body.run(call);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error(name + " interrupted");
                } catch (Exception e) {
                    return ToolResult.error(name + " failed: " + e.getMessage());
                }
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingBody {
        ToolResult run(ToolCall call) throws Exception;
    }

    private static String str(ToolCall call, String key) {
        Object v = call.arguments().get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static int intArg(ToolCall call, String key, int fallback) {
        Object v = call.arguments().get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
