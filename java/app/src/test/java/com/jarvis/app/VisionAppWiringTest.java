package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Verifies the Phase 4 composition-root wiring: {@link AppWiring#build} assembles a non-null
 * {@link AppWiring.VisionServices} bundle (reachable both directly off {@link AppWiring.Runtime}
 * and through {@link AppWiring.Runtime#governance()}), and that the whole vision subsystem is
 * default-off even when fully wired into the production runtime — this is the "Default-OFF
 * Verification Evidence" for the vision motion + face-recognition feature.
 */
class VisionAppWiringTest {

    @Test
    void productionRuntimeWiresVisionServicesDefaultOff() {
        AppWiring.Runtime runtime = AppWiring.build(null, "test-model");

        AppWiring.VisionServices visionServices = runtime.visionServices();
        assertNotNull(visionServices, "Runtime.visionServices() must be wired");
        assertNotNull(visionServices.settings(), "VisionServices.settings() must be wired");
        assertNotNull(visionServices.motionEvents(), "VisionServices.motionEvents() must be wired");
        assertNotNull(visionServices.enrollment(), "VisionServices.enrollment() must be wired");

        // Default-OFF Verification Evidence: motion detection and face recognition are both
        // disabled by default even inside the fully-wired production composition root.
        VisionSettings.Snapshot snapshot = visionServices.settings().snapshot();
        assertFalse(snapshot.motion().enabled(), "motion detection must default to off");
        assertFalse(snapshot.face().enabled(), "face recognition must default to off");

        // Reachable identically through Runtime.governance(), which is what WebServer.start(...)
        // actually receives in the production launcher.
        AppWiring.Governance governance = runtime.governance();
        assertNotNull(governance.visionServices());
        assertNotNull(governance.visionServices().settings());
        assertNotNull(governance.visionServices().motionEvents());
        assertNotNull(governance.visionServices().enrollment());
    }
}
