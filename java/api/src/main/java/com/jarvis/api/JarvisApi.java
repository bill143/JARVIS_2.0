package com.jarvis.api;

/**
 * The platform's public programmatic surface: the one interface external callers (CLIs, servers,
 * UIs) depend on. Everything behind it — routing, the agent loop, tools, memory, planning — stays
 * an implementation detail.
 *
 * <p>Transport-free by design: the dependency whitelist admits no web framework, so HTTP/websocket
 * bindings would wrap this interface in a later phase without changing it.
 */
public interface JarvisApi {

    /** Handles one chat turn. */
    ChatResponse chat(ChatRequest request);

    /** Decomposes a goal into a plan and executes it step by step. */
    PlanResponse plan(PlanRequest request);
}
