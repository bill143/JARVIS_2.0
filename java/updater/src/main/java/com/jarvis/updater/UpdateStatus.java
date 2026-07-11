package com.jarvis.updater;

import java.util.Objects;

/**
 * The outcome of an update check, ready to show on the HUD. Notify-only: even when an update is
 * available this just carries the download URL for the user to act on — nothing installs itself.
 *
 * @param state where things stand
 * @param currentVersion the running version
 * @param available the newer manifest (only when {@code state == UPDATE_AVAILABLE}), else null
 * @param message a short human-readable explanation
 */
public record UpdateStatus(UpdateState state, String currentVersion, UpdateManifest available,
        String message) {

    public UpdateStatus {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(currentVersion, "currentVersion");
        message = message == null ? "" : message;
    }

    public static UpdateStatus checking(Version current) {
        return new UpdateStatus(UpdateState.CHECKING, current.toString(), null, "Checking for updates…");
    }

    public static UpdateStatus disabled(Version current) {
        return new UpdateStatus(UpdateState.DISABLED, current.toString(), null,
                "Update checks are off.");
    }

    public static UpdateStatus upToDate(Version current) {
        return new UpdateStatus(UpdateState.UP_TO_DATE, current.toString(), null,
                "You're on the latest version.");
    }

    public static UpdateStatus available(Version current, UpdateManifest manifest) {
        return new UpdateStatus(UpdateState.UPDATE_AVAILABLE, current.toString(), manifest,
                "Version " + manifest.version() + " is available.");
    }

    public static UpdateStatus unverified(Version current, String why) {
        return new UpdateStatus(UpdateState.UNVERIFIED, current.toString(), null, why);
    }

    public static UpdateStatus error(Version current, String why) {
        return new UpdateStatus(UpdateState.ERROR, current.toString(), null, why);
    }
}
