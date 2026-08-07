package com.termina.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Command-palette ranking. */
class CommandMatcherTest {

    private static void ranksAbove(String query, String better, String worse) {
        int a = CommandMatcher.score(query, better);
        int b = CommandMatcher.score(query, worse);
        assertTrue(a > b, "\"" + query + "\": " + better + " (" + a + ") should beat " + worse + " (" + b + ")");
    }

    @Test
    void initialsFindTheirCommand() {
        // The whole reason for a palette: two keys, no full word typed.
        assertTrue(CommandMatcher.score("ct", "Close Tab") > CommandMatcher.NO_MATCH);
        assertTrue(CommandMatcher.score("nw", "New Window") > CommandMatcher.NO_MATCH);
    }

    @Test
    void initialsBeatALetterBuriedInAWord() {
        // "nt" is a subsequence of both, but only one is the initials.
        ranksAbove("nt", "New Tab", "Next Tab");
    }

    @Test
    void aRunOfCharactersBeatsAScatteredMatch() {
        ranksAbove("tab", "Close Tab", "Terminal About Box");
    }

    @Test
    void aMatchNearTheFrontWins() {
        ranksAbove("c", "Copy", "Actual Size");
    }

    @Test
    void aQueryThatIsNotASubsequenceDoesNotMatch() {
        assertEquals(CommandMatcher.NO_MATCH, CommandMatcher.score("zz", "Close Tab"));
        // Order matters: the letters are all there, in the wrong sequence.
        assertEquals(CommandMatcher.NO_MATCH, CommandMatcher.score("bat", "Tab"));
    }

    @Test
    void caseIsIgnoredBothWays() {
        assertTrue(CommandMatcher.score("CT", "Close Tab") > CommandMatcher.NO_MATCH);
        assertTrue(CommandMatcher.score("ct", "CLOSE TAB") > CommandMatcher.NO_MATCH);
    }

    @Test
    void spacesInTheQueryAreIgnored() {
        // Typing the words out should not do worse than typing the initials.
        assertTrue(CommandMatcher.score("close tab", "Close Tab") > CommandMatcher.NO_MATCH);
    }

    @Test
    void anEmptyQueryMatchesEverythingEqually() {
        // Equal, so the caller's own ordering survives — for the palette that is the menu order.
        assertEquals(0, CommandMatcher.score("", "Copy"));
        assertEquals(0, CommandMatcher.score("   ", "Close Tab"));
    }

    @Test
    void aNullLabelIsNotAMatchRatherThanACrash() {
        assertEquals(CommandMatcher.NO_MATCH, CommandMatcher.score("c", null));
    }
}
