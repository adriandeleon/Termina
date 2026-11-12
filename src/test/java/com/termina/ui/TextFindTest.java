package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Debug Log's search and the Message Log's filter (FIND-01..03, FIND-05, FIND-06, Pauta 6.0.1). */
class TextFindTest {

    private static final String LOG = "WARNING one\nSEVERE Two\nwarning three\n";

    @Test
    void aLowerCaseQueryMatchesEitherCaseAndACapitalMakesItExact() {
        assertArrayEquals(new int[] {0, 23}, TextFind.matches(LOG, "warning"));
        assertArrayEquals(new int[] {0}, TextFind.matches(LOG, "WARNING"));
        assertArrayEquals(new int[] {19}, TextFind.matches(LOG, "Two"));
        assertArrayEquals(new int[] {19}, TextFind.matches(LOG, "two"));
    }

    @Test
    void theQueryIsTextNotAPattern() {
        assertArrayEquals(new int[] {1}, TextFind.matches("a.b axb", ".b"));
        assertArrayEquals(new int[] {0}, TextFind.matches("(x) x", "(x)"));
    }

    @Test
    void anEmptyQueryOrAnEmptyTextFindsNothing() {
        assertEquals(0, TextFind.matches(LOG, "").length);
        assertEquals(0, TextFind.matches("", "x").length);
        assertEquals(0, TextFind.matches(LOG, "absent").length);
    }

    @Test
    void matchesDoNotOverlap() {
        assertArrayEquals(new int[] {0, 2}, TextFind.matches("aaaa", "aa"));
    }

    @Test
    void theCountStopsOneOverTheLimitSoItCanSayThereWereMore() {
        int[] found = TextFind.matches("x".repeat(TextFind.MAX_MATCHES * 3), "x");
        assertEquals(TextFind.MAX_MATCHES + 1, found.length);
        assertEquals(TextFind.MAX_MATCHES + "+", DebugLogWindow.total(found.length));
        assertEquals("7", DebugLogWindow.total(7));
        // The step stays within the matches that are counted.
        assertEquals(0, TextFind.step(TextFind.MAX_MATCHES - 1, found, true));
        assertEquals(TextFind.MAX_MATCHES - 1, TextFind.step(0, found, false));
    }

    @Test
    void theSearchStartsOnTheFirstMatchAtOrAfterTheCaretAndWrapsToTheFirst() {
        int[] found = {5, 20, 40};
        assertEquals(0, TextFind.startAt(found, 0));
        assertEquals(1, TextFind.startAt(found, 6));
        assertEquals(1, TextFind.startAt(found, 20));
        assertEquals(0, TextFind.startAt(found, 41));
        assertEquals(-1, TextFind.startAt(new int[0], 0));
    }

    @Test
    void aStepWrapsAtBothEnds() {
        int[] found = {5, 20, 40};
        assertEquals(1, TextFind.step(0, found, true));
        assertEquals(0, TextFind.step(2, found, true));
        assertEquals(2, TextFind.step(0, found, false));
        assertEquals(0, TextFind.step(-1, found, true));
        assertEquals(2, TextFind.step(-1, found, false));
        assertEquals(-1, TextFind.step(0, new int[0], true));
    }

    @Test
    void aLogLinePassesWhenEveryWordTypedIsInItWhateverItsCase() {
        String line = "14:02:11  Sync failed: no route to host";
        assertTrue(TextFind.passes(line, ""));
        assertTrue(TextFind.passes(line, "   "));
        assertTrue(TextFind.passes(line, "sync"));
        assertTrue(TextFind.passes(line, "HOST sync"));
        assertTrue(TextFind.passes(line, "14:02"));
        assertFalse(TextFind.passes(line, "sync update"));
    }
}
