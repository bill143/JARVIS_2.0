package com.jarvis.app;

/**
 * A typed, defaulted view over the vision motion + face-recognition configuration keys, resolved
 * through {@link ConnectorSettingsService} (saved value wins, else the environment variable, else
 * the documented default below) — the same pattern {@link ConsensusSettings} uses.
 *
 * <p>Every field defaults to off/inert: motion detection ({@link #DEFAULT_MOTION_ENABLED}) and
 * face recognition ({@link #DEFAULT_FACE_ENABLED}) are both disabled until explicitly turned on,
 * matching the default-deny posture of every other safety-gated feature in this app.
 */
final class VisionSettings {

    static final boolean DEFAULT_MOTION_ENABLED = false;
    static final int DEFAULT_MOTION_COOLDOWN_SEC = 20;
    static final boolean DEFAULT_FACE_ENABLED = false;
    static final String DEFAULT_FACE_PROVIDER = "compreface";
    static final double DEFAULT_FACE_SIMILARITY_THRESHOLD = 0.80;
    static final int DEFAULT_FACE_PENDING_TTL_SEC = 300;

    /** Motion-detection configuration. */
    record MotionSnapshot(boolean enabled, String webhookSecret, int cooldownSec) {
    }

    /** Face-recognition configuration. */
    record FaceSnapshot(boolean enabled, String provider, String baseUrl, String apiKey,
            double similarityThreshold, int pendingTtlSec) {
    }

    /** A fully-resolved, typed snapshot of the vision configuration at the moment it was read. */
    record Snapshot(MotionSnapshot motion, FaceSnapshot face, String storageRoot) {
    }

    private final ConnectorSettingsService connectors;

    VisionSettings(ConnectorSettingsService connectors) {
        this.connectors = connectors;
    }

    Snapshot snapshot() {
        MotionSnapshot motion = new MotionSnapshot(
                bool("vision.motion.enabled", "JARVIS_VISION_MOTION_ENABLED", DEFAULT_MOTION_ENABLED),
                str("vision.motion.webhookSecret", "JARVIS_VISION_MOTION_WEBHOOK_SECRET", null),
                positiveInt("vision.motion.cooldownSec", "JARVIS_VISION_MOTION_COOLDOWN_SEC",
                        DEFAULT_MOTION_COOLDOWN_SEC));
        FaceSnapshot face = new FaceSnapshot(
                bool("vision.face.enabled", "JARVIS_FACE_ENABLED", DEFAULT_FACE_ENABLED),
                str("vision.face.provider", "JARVIS_FACE_PROVIDER", DEFAULT_FACE_PROVIDER),
                str("vision.face.baseUrl", "JARVIS_FACE_BASE_URL", null),
                str("vision.face.apiKey", "JARVIS_FACE_API_KEY", null),
                positiveDouble("vision.face.similarityThreshold", "JARVIS_FACE_SIMILARITY_THRESHOLD",
                        DEFAULT_FACE_SIMILARITY_THRESHOLD),
                positiveInt("vision.face.pendingTtlSec", "JARVIS_FACE_PENDING_TTL_SEC",
                        DEFAULT_FACE_PENDING_TTL_SEC));
        // Resolved lazily (not a class-init constant) so the default tracks the user.home of the
        // process actually running, and so a saved/env override is picked up live like every other
        // field.
        String storageRoot = str("vision.storage.root", "JARVIS_VISION_STORAGE_ROOT", defaultStorageRoot());
        return new Snapshot(motion, face, storageRoot);
    }

    private static String defaultStorageRoot() {
        return System.getProperty("user.home") + "/.jarvis/vision";
    }

    private boolean bool(String key, String env, boolean fallback) {
        String v = connectors.resolve(key, env);
        return v == null ? fallback : Boolean.parseBoolean(v.strip());
    }

    private String str(String key, String env, String fallback) {
        String v = connectors.resolve(key, env);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    private int positiveInt(String key, String env, int fallback) {
        String v = connectors.resolve(key, env);
        if (v == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(v.strip());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double positiveDouble(String key, String env, double fallback) {
        String v = connectors.resolve(key, env);
        if (v == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(v.strip());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
