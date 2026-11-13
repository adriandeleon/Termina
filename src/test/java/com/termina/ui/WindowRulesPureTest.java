package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The window's pure rules, without the toolkit (QUAL-05, Pauta 2.0.2). */
class WindowRulesPureTest {

    /** WIN-13 (Pauta 6.0.1): every window asked to close in one turn is the system quitting. */
    @Test
    void closeRequestsOnEveryWindowAtOnceAreAQuit() {
        assertTrue(WindowRules.closeRequestsAreAQuit(2, 2));
        assertTrue(WindowRules.closeRequestsAreAQuit(3, 3));
        // One of several is that window's close button.
        assertFalse(WindowRules.closeRequestsAreAQuit(1, 2));
        assertFalse(WindowRules.closeRequestsAreAQuit(2, 3));
        // With one window open, closing it and quitting are the same thing: it closes as a window.
        assertFalse(WindowRules.closeRequestsAreAQuit(1, 1));
        assertFalse(WindowRules.closeRequestsAreAQuit(0, 0));
    }

    @Test
    void onlyAnOrdinaryWindowsGeometryIsRemembered() {
        assertTrue(WindowRules.isRestoredGeometry(false, false));
        assertFalse(WindowRules.isRestoredGeometry(true, false));
        // The one that was missed: closing a full-screen window saved the screen as its size.
        assertFalse(WindowRules.isRestoredGeometry(false, true));
        assertFalse(WindowRules.isRestoredGeometry(true, true));
    }

    @Test
    void aWindowWhoseLastShellWillNotStartStaysOpen() {
        // Closing took the error dialog with it and, for the last window, the application.
        assertTrue(WindowRules.keepsWindowOpenEmpty(0, false));
        // The default shell is being tried instead: that tab is what the window holds.
        assertFalse(WindowRules.keepsWindowOpenEmpty(0, true));
        // Other tabs are left; the failed one simply goes.
        assertFalse(WindowRules.keepsWindowOpenEmpty(2, false));
    }

    @Test
    void theReferenceListsProfileChordsOnlyForProfilesThatExist() {
        assertTrue(WindowRules.listsProfileChord("file.newTabProfile1", 1));
        assertFalse(WindowRules.listsProfileChord("file.newTabProfile2", 1));
        assertTrue(WindowRules.listsProfileChord("file.newTabProfile9", 9));
        // Everything that is not a profile chord is listed as before.
        assertTrue(WindowRules.listsProfileChord("file.newTab", 0));
        assertTrue(WindowRules.listsProfileChord("tab.goTo9", 0));
        assertTrue(WindowRules.listsProfileChord(null, 0));
    }
}
