package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether the menu bar takes up a row in the window. */
class MenuBarVisibilityTest {

    @Test
    void inWindowMenuBarFollowsTheSetting() {
        assertTrue(TerminalWindow.menuBarOccupiesSpace(false, true));
        assertFalse(TerminalWindow.menuBarOccupiesSpace(false, false));
    }

    @Test
    void aScreenMenuBarNeverTakesSpaceWhateverTheSettingSays() {
        // The node stays in the scene graph because that is what JavaFX forwards to the system
        // bar, but leaving it measurable costs a band of empty chrome above the terminal — which
        // is exactly the bug this rule was extracted from.
        assertFalse(TerminalWindow.menuBarOccupiesSpace(true, true));
        assertFalse(TerminalWindow.menuBarOccupiesSpace(true, false));
    }

    @Test
    void theToggleIsOfferedOnlyWhereThereIsABarInTheWindowToHide() {
        // Read by two menus — the View menu's Hide item and the right-click menu's checkbox — which
        // is the whole reason it is a named rule rather than an `if` in each of them. Offering it
        // in one place and not the other reads as a bug in whichever you looked at first.
        assertTrue(TerminalWindow.offersMenuBarToggle(false));
        assertFalse(TerminalWindow.offersMenuBarToggle(true));
    }
}
