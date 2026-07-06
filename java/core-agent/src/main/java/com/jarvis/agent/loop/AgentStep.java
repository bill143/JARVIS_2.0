package com.jarvis.agent.loop;

import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.util.Objects;

/**
 * One completed loop iteration: the tool call the policy chose and the observation it produced.
 * Failed executions are ordinary steps — the failure is the observation.
 *
 * @param call the tool call that was executed
 * @param result the outcome of executing it
 */
public record AgentStep(ToolCall call, ToolResult result) {

    public AgentStep {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(result, "result");
    }
}
