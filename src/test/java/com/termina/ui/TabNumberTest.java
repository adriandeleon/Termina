package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which tab a number selects. */
class TabNumberTest {

    @Test
    void aNumberSelectsThatTab() {
        assertEquals(0, TerminalWindow.tabIndexFor(1, 5));
        assertEquals(2, TerminalWindow.tabIndexFor(3, 5));
    }

    @Test
    void theLastDigitMeansTheLastTabNotTheNinth() {
        // The convention every browser and terminal follows: with four tabs open, 9 goes to the
        // fourth. Treating it as "the ninth" would make it do nothing in almost every session.
        assertEquals(3, TerminalWindow.tabIndexFor(9, 4));
        assertEquals(0, TerminalWindow.tabIndexFor(9, 1));
        assertEquals(8, TerminalWindow.tabIndexFor(9, 9));
    }

    @Test
    void aNumberPastTheEndSelectsNothing() {
        // Rather than the nearest: landing somewhere the user did not ask for is worse than
        // not moving at all.
        assertEquals(-1, TerminalWindow.tabIndexFor(5, 3));
        assertEquals(-1, TerminalWindow.tabIndexFor(2, 1));
    }

    @Test
    void nonsenseSelectsNothing() {
        assertEquals(-1, TerminalWindow.tabIndexFor(0, 3));
        assertEquals(-1, TerminalWindow.tabIndexFor(-1, 3));
        assertEquals(-1, TerminalWindow.tabIndexFor(10, 3));
        assertEquals(-1, TerminalWindow.tabIndexFor(1, 0));
    }

    @Test
    void theChordShownForATabIsTheOneThatReachesIt() {
        // The inverse of tabIndexFor, for the palette's rows: every index it labels with a number
        // must be the index that number selects.
        for (int count = 1; count <= 12; count++) {
            for (int index = 0; index < count; index++) {
                int number = TerminalWindow.tabNumberFor(index, count);
                if (number != 0) assertEquals(index, TerminalWindow.tabIndexFor(number, count), index + "/" + count);
            }
        }
        assertEquals(9, TerminalWindow.tabNumberFor(3, 4)); // the last tab answers to 9
        assertEquals(0, TerminalWindow.tabNumberFor(8, 12)); // ninth of twelve: no chord
        assertEquals(0, TerminalWindow.tabNumberFor(5, 3));
    }

    @Test
    void aFailedLaunchFallsBackOnlyWhenTheWindowWouldBeLeftEmpty() {
        // An empty window closes itself and the last one closing quits, which is how a broken
        // default profile left nobody a window to fix it from.
        assertTrue(TerminalWindow.shouldFallBackToDefaultShell(1, false));
        assertFalse(TerminalWindow.shouldFallBackToDefaultShell(2, false));
        // ...and never by retrying the default shell itself, which would loop.
        assertFalse(TerminalWindow.shouldFallBackToDefaultShell(1, true));
    }
}
