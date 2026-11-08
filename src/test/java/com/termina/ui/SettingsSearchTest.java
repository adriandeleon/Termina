package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings window's search (SET-03, Pauta 2.0.2). */
class SettingsSearchTest {

    private static SettingsSearch.Texts title(String text) {
        return new SettingsSearch.Texts(text, "", "", "");
    }

    private static SettingsSearch.Texts description(String text) {
        return new SettingsSearch.Texts("", text, "", "");
    }

    private static SettingsSearch.Texts heading(String text) {
        return new SettingsSearch.Texts("", "", text, "");
    }

    private static SettingsSearch.Texts keywords(String text) {
        return new SettingsSearch.Texts("", "", "", text);
    }

    @Test
    void aTitleHitOutranksAKeywordHitAndAWholeWordOutranksPartOfOne() {
        // The rule's own check.
        assertTrue(SettingsSearch.score("bell", title("Visual bell")) > SettingsSearch.score("bell", keywords("bell")));
        assertTrue(
                SettingsSearch.score("tab", title("Hide the tab bar")) > SettingsSearch.score("tab", title("Table")));
        assertTrue(SettingsSearch.score("tab", title("Table")) > SettingsSearch.score("tab", title("Stable")));
    }

    @Test
    void titleThenDescriptionThenHeadingThenKeyword() {
        int t = SettingsSearch.score("scrollback", title("Scrollback lines"));
        int d = SettingsSearch.score("scrollback", description("The wheel moves through the scrollback"));
        int h = SettingsSearch.score("scrollback", heading("Scrollback"));
        int k = SettingsSearch.score("scrollback", keywords("scrollback history"));
        assertTrue(t > d && d > h && h > k, t + " " + d + " " + h + " " + k);
    }

    @Test
    void aSectionHeadingFindsTheRowsUnderIt() {
        // SET-03: the heading is searched, so "diagnostics" finds Debug log, whose own text does not say it.
        SettingsSearch.Texts debugLog = new SettingsSearch.Texts(
                "Debug log", "Warnings and errors from this session", "Diagnostics Advanced", "");
        assertTrue(SettingsSearch.score("diagnostics", debugLog) > 0);
        assertTrue(SettingsSearch.score("advanced", debugLog) > 0, "and so is the page's name");
    }

    @Test
    void everyWordMustMatchInAnyOrderAndCaseIsIgnored() {
        SettingsSearch.Texts row = new SettingsSearch.Texts("Font size", "Points", "", "zoom");
        assertTrue(SettingsSearch.score("SIZE font", row) > 0);
        assertEquals(0, SettingsSearch.score("font colour", row));
        assertEquals(0, SettingsSearch.score("scrollback", row));
        assertEquals(1, SettingsSearch.score("  ", row), "a blank query keeps everything");
    }

    @Test
    void aQueryThatNamesAPageLandsOnIt() {
        // The Keyboard page's "Sync Settings Now" is a title hit; the Sync page's rows are not.
        int keyboardRow =
                SettingsSearch.landing(SettingsSearch.score("sync", title("Sync Settings Now")), "sync", "Keyboard");
        int syncRow = SettingsSearch.landing(SettingsSearch.score("sync", keywords("sync git")), "sync", "Sync");
        assertEquals(1, SettingsSearch.bestOf(new int[] {keyboardRow, syncRow}));
        assertEquals(0, SettingsSearch.landing(0, "sync", "Sync"), "a row that did not match stays hidden");
    }

    @Test
    void theBestIsTheHighestAndTheFirstOnATie() {
        assertEquals(2, SettingsSearch.bestOf(new int[] {1, 0, 2, 2}));
        assertEquals(0, SettingsSearch.bestOf(new int[] {1, 1}));
        assertEquals(-1, SettingsSearch.bestOf(new int[] {0, 0}));
        assertEquals(-1, SettingsSearch.bestOf(new int[0]));
    }
}
