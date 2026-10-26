package com.termina.ui;

import com.termina.ui.TabMarks.Mark;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a background tab gets a dot, and when it loses it. */
class TabMarksTest {

    private static final long QUIET = TabMarks.QUIET_MS;

    @Test
    void aBellMarksABackgroundTabAndNeverTheSelectedOne() {
        assertEquals(Mark.BELL, TabMarks.onBell(false, Mark.NONE));
        assertEquals(Mark.BELL, TabMarks.onBell(false, Mark.ACTIVITY));
        assertEquals(Mark.NONE, TabMarks.onBell(true, Mark.NONE));
    }

    @Test
    void outputAfterAQuietSpellIsNews() {
        assertEquals(Mark.ACTIVITY, TabMarks.onOutput(false, Mark.NONE, 10_000, 10_000 - QUIET, QUIET));
        assertEquals(Mark.ACTIVITY, TabMarks.onOutput(false, Mark.NONE, 10_000, 0, QUIET));
    }

    @Test
    void outputInTheSameBurstIsNot() {
        // A new tab's own first prompt, printed a moment after it was opened and left, is not
        // something that happened while the user was away.
        assertEquals(Mark.NONE, TabMarks.onOutput(false, Mark.NONE, 10_000, 10_000 - QUIET + 1, QUIET));
    }

    @Test
    void aMarkIsKeptUntilTheTabIsLookedAt() {
        // A stream that keeps going does not flicker; a bell is not downgraded by later output.
        assertEquals(Mark.ACTIVITY, TabMarks.onOutput(false, Mark.ACTIVITY, 10_000, 9_990, QUIET));
        assertEquals(Mark.BELL, TabMarks.onOutput(false, Mark.BELL, 10_000, 0, QUIET));
        assertEquals(Mark.NONE, TabMarks.onOutput(true, Mark.BELL, 10_000, 0, QUIET));
        assertEquals(Mark.NONE, TabMarks.onSelected());
    }

    @Test
    void aNotificationOutranksEverything() {
        assertEquals(Mark.NOTIFY, TabMarks.onNotify(false));
        assertEquals(Mark.NONE, TabMarks.onNotify(true));
        assertEquals(Mark.NOTIFY, TabMarks.onBell(false, Mark.NOTIFY));
        assertEquals(Mark.NOTIFY, TabMarks.onOutput(false, Mark.NOTIFY, 10_000, 0, QUIET));
        assertEquals(Mark.NONE, TabMarks.onOutput(true, Mark.NOTIFY, 10_000, 0, QUIET));
    }

    @Test
    void theDesktopHearsOfItUnlessThePaneIsTheOneInFront() {
        assertFalse(TabMarks.postsToDesktop(true, true, true));
        assertTrue(TabMarks.postsToDesktop(false, true, true), "another window in front");
        assertTrue(TabMarks.postsToDesktop(true, false, true), "another tab selected");
        assertTrue(TabMarks.postsToDesktop(true, true, false), "another pane of the split");
    }
}
