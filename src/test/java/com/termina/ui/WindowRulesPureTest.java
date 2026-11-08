package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The window's pure rules, without the toolkit (QUAL-05, Pauta 2.0.2). */
class WindowRulesPureTest {

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
        assertTrue(WindowRules.listsProfileChord("newTabProfile1", 1));
        assertFalse(WindowRules.listsProfileChord("newTabProfile2", 1));
        assertTrue(WindowRules.listsProfileChord("newTabProfile9", 9));
        // Everything that is not a profile chord is listed as before.
        assertTrue(WindowRules.listsProfileChord("newTab", 0));
        assertTrue(WindowRules.listsProfileChord("goToTab9", 0));
        assertTrue(WindowRules.listsProfileChord(null, 0));
    }
}
