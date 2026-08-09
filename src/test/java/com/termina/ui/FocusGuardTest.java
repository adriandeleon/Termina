package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the terminal takes keyboard focus back.
 *
 * <p>Both directions of this rule fail silently and neither looks like a focus problem. Reclaim too
 * little and the window swallows everything typed into it; reclaim too eagerly and the command
 * palette cannot be typed into and right-click dismisses its own menu.
 */
class FocusGuardTest {

    @Test
    void anythingButTheTerminalHoldingFocusHandsItBack() {
        // The tab strip, the menu bar, the new-tab button, or nothing at all: the caller collapses
        // all of them to "not the terminal", because to the person typing they are the same dead
        // window. Splitting them here would be two tests asserting one thing.
        assertTrue(FocusGuard.shouldReclaim(true, false, false));
    }

    @Test
    void theTerminalKeepingFocusIsLeftAlone() {
        // The overwhelmingly common case, and it must not queue work on every focus event.
        assertFalse(FocusGuard.shouldReclaim(true, true, false));
    }

    @Test
    void anOpenOverlayKeepsTheKeyboard() {
        // The palette has a text field; a menu navigates by keyboard; a context menu is a popup
        // that dismisses when focus moves. Reclaiming under any of them breaks it.
        assertFalse(FocusGuard.shouldReclaim(true, false, true));
    }

    @Test
    void withNoTerminalThereIsNothingToReclaimTo() {
        // A window being torn down, or one whose last tab has just closed.
        assertFalse(FocusGuard.shouldReclaim(false, false, false));
        assertFalse(FocusGuard.shouldReclaim(false, false, true));
    }
}
