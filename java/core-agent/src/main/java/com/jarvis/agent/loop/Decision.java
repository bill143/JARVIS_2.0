package com.jarvis.agent.loop;

import com.jarvis.tools.ToolCall;
import java.util.Objects;

/**
 * Outcome of one policy decision: either the agent is done and responds, or it invokes a tool and
 * continues the loop. Sealed so {@link AgentLoop} handles every case exhaustively.
 */
public sealed interface Decision {

    /** Terminal decision: reply with {@code message} and stop the loop. */
    record Respond(String message) implements Decision {
        public Respond {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Non-terminal decision: execute {@code call} and feed the observation back into the loop. */
    record Invoke(ToolCall call) implements Decision {
        public Invoke {
            Objects.requireNonNull(call, "call");
        }
    }
}
