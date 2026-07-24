package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jarvis.agent.loop.AgentContext;
import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.Decision;
import org.junit.jupiter.api.Test;

class SwappablePolicyTest {

    private static AgentPolicy replies(String text) {
        return ctx -> new Decision.Respond(text);
    }

    private static String respond(AgentPolicy p) {
        Decision d = p.decide(new AgentContext("hi", java.util.List.of()));
        return ((Decision.Respond) d).message();
    }

    @Test
    void delegatesToCurrentAndSwapsLive() {
        SwappablePolicy sw = new SwappablePolicy(replies("first"));
        assertEquals("first", respond(sw));
        sw.set(replies("second"));
        assertEquals("second", respond(sw));   // swap took effect with no new instance
    }
}
