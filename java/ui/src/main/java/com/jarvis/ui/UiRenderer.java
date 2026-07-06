package com.jarvis.ui;

/**
 * Placeholder rendering seam for future user interfaces (REQ-STEP-012 is UI inspiration only).
 *
 * <p>A real UI — console, desktop, web — implements this to present conversation messages however
 * it likes. The platform stays UI-agnostic: nothing internal depends on this module.
 */
@FunctionalInterface
public interface UiRenderer {

    /** Presents {@code message} to the user. */
    void render(UiMessage message);
}
