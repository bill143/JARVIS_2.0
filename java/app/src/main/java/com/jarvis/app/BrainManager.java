package com.jarvis.app;

import com.jarvis.agent.loop.AgentPolicy;
import java.util.function.Supplier;

/**
 * Owns the live chat brain so the active model provider can be switched without a restart.
 *
 * <p>The {@code JarvisApi} is assembled once around a {@link SwappablePolicy}; {@link #reload()}
 * rebuilds a policy from whatever provider is currently active and swaps it in atomically. The
 * {@code brainFactory} closes over the tools, memory and provider settings assembled at startup, so
 * a reload picks up the newly-activated provider (and its key/model) with no other wiring.
 */
final class BrainManager {

    private final SwappablePolicy swappable;
    private final Supplier<AgentPolicy> brainFactory;
    private final Supplier<String> modelLabel;

    BrainManager(SwappablePolicy swappable, Supplier<AgentPolicy> brainFactory,
            Supplier<String> modelLabel) {
        this.swappable = swappable;
        this.brainFactory = brainFactory;
        this.modelLabel = modelLabel;
    }

    /** Rebuilds the policy from the currently-active provider and swaps it in live. */
    synchronized void reload() {
        swappable.set(brainFactory.get());
    }

    /** The model id of the currently-active brain (for status display). */
    String currentModel() {
        return modelLabel.get();
    }
}
