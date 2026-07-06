package com.jarvis.agent.loop;

/**
 * The decision seam of the control loop: given the current context (original input plus all
 * observations so far), choose the next {@link Decision}.
 *
 * <p>This is where reasoning plugs in — prompt routing (REQ-STEP-005), task planning
 * (REQ-STEP-006), or an LLM-backed policy later. The loop itself stays mechanism-only.
 */
@FunctionalInterface
public interface AgentPolicy {

    /** Decides the next action for {@code context}; must not return {@code null}. */
    Decision decide(AgentContext context);
}
