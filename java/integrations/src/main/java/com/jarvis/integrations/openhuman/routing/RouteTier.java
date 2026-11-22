package com.jarvis.integrations.openhuman.routing;

/** Which tier a task is (or would be) routed to. */
public enum RouteTier {

    /** The operator's configured primary provider for the role (Conductor/Orchestrator/Worker). */
    TIER1_PRIMARY,

    /** The OpenHuman core, consulted as the Tier-2 delegate/failover path. */
    TIER2_OPENHUMAN
}
