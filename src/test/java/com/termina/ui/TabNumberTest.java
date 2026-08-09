package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
