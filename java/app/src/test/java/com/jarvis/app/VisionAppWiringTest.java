package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Verifies the Phase 4 composition-root wiring: {@link AppWiring#build} assembles a non-null
 * {@link AppWiring.VisionServices} bundle, reachable both directly off {@link AppWiring.Runtime}
 * and through {@link AppWiring.Runtime#governance()} (the latter is what {@code WebServer.start}
 * actually receives in the production launcher).
 *
 * <p><b>Deliberately does NOT assert default-off flag values here.</b> {@link AppWiring#build}
 * resolves {@link VisionSettings} through the real process environment ({@code System.getenv()}),
 * by design — that's how live config is meant to work in production. Asserting
 * {@code snapshot.motion().enabled() == false} through this path would make the test's pass/fail
 * outcome depend on whatever the developer's shell happens to have set (e.g. a manual smoke-test
 * session that exported {@code JARVIS_VISION_MOTION_ENABLED=true} before running {@code mvn
 * package} in the same shell) — exactly the kind of ambient-environment leakage every other test
 * in this codebase avoids by constructing {@link ConnectorSettingsService} with an injected fake
 * env supplier instead of the real one. The default-off invariant is already fully and hermetically
 * covered by {@code VisionConfigTest}, which does exactly that. This class only needs to prove the
 * wiring itself is structurally correct, which is env-independent.
 */
class VisionAppWiringTest {

    @Test
    void productionRuntimeWiresVisionServices() {
        AppWiring.Runtime runtime = AppWiring.build(null, "test-model");

        AppWiring.VisionServices visionServices = runtime.visionServices();
        assertNotNull(visionServices, "Runtime.visionServices() must be wired");
        assertNotNull(visionServices.settings(), "VisionServices.settings() must be wired");
        assertNotNull(visionServices.motionEvents(), "VisionServices.motionEvents() must be wired");
        assertNotNull(visionServices.enrollment(), "VisionServices.enrollment() must be wired");

        AppWiring.Governance governance = runtime.governance();
        assertNotNull(governance.visionServices());
        assertNotNull(governance.visionServices().settings());
        assertNotNull(governance.visionServices().motionEvents());
        assertNotNull(governance.visionServices().enrollment());
    }
}
