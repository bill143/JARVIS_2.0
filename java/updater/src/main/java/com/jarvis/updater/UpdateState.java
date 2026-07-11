package com.jarvis.updater;

/** Where an update check stands. */
public enum UpdateState {

    /** The check hasn't finished yet. */
    CHECKING,

    /** Checks are turned off (no manifest URL configured). */
    DISABLED,

    /** Running the latest published version. */
    UP_TO_DATE,

    /** A newer, verified version is available. */
    UPDATE_AVAILABLE,

    /** A manifest was fetched but its signature didn't verify — ignored, not trusted. */
    UNVERIFIED,

    /** The check itself failed (network, parse). Non-fatal. */
    ERROR
}
