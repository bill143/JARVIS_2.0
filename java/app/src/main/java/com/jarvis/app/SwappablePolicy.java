package com.jarvis.app;

import com.jarvis.agent.loop.AgentContext;
import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.Decision;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An {@link AgentPolicy} whose underlying delegate can be replaced at runtime. The assembled
 * {@code JarvisApi} holds one of these, so switching the active model provider (Settings → APIs &amp;
 * Models) takes effect on the next message — no restart. The swap is a single atomic reference set,
 * so an in-flight request always sees a complete policy.
 */
final class SwappablePolicy implements AgentPolicy {

    private final AtomicReference<AgentPolicy> delegate;

    SwappablePolicy(AgentPolicy initial) {
        this.delegate = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    void set(AgentPolicy next) {
        delegate.set(Objects.requireNonNull(next, "next"));
    }

    @Override
    public Decision decide(AgentContext context) {
        return delegate.get().decide(context);
    }
}
