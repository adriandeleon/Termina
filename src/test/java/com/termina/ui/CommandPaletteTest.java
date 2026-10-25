package com.termina.ui;

import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The palette's ordering, its keyword fallback, and the chord that opens it. */
class CommandPaletteTest {

    private static MenuAction command(String label) {
        return MenuAction.of(label, () -> {});
    }

    private static List<String> labels(List<MenuAction> actions) {
        return actions.stream().map(MenuAction::label).toList();
    }

    // ---- ordering ----

    @Test
    void anEmptyQueryKeepsMenuOrder() {
        // An order the user has already learned.
        List<MenuAction> menu = List.of(command("New Tab"), command("Close Tab"), command("Copy"));
        assertEquals(List.of("New Tab", "Close Tab", "Copy"), labels(CommandPalette.orderedMatches(menu, "")));
        assertEquals(List.of("New Tab", "Close Tab", "Copy"), labels(CommandPalette.orderedMatches(menu, "  ")));
    }

    @Test
    void aQueryRanksByScoreAndDropsNonMatches() {
        List<MenuAction> menu = List.of(command("Next Tab"), command("Copy"), command("New Tab"));
        assertEquals(List.of("New Tab", "Next Tab"), labels(CommandPalette.orderedMatches(menu, "nt")));
    }

    @Test
    void initialsWinEvenWhenAnEarlierLetterCouldHaveMatched() {
        // "nw" is a subsequence of "New Tab" too (the w of "New"); a matcher that takes the leftmost
        // match for each character scores the two identically and menu order decides.
        List<MenuAction> menu = List.of(command("New Tab"), command("New Window"));
        assertEquals(List.of("New Window", "New Tab"), labels(CommandPalette.orderedMatches(menu, "nw")));
    }

    @Test
    void tiesGoToTheShorterThenTheAlphabeticalLabel() {
        // Both begin with a word start on c; neither is a better match, so the order must be one
        // that stays put across keystrokes rather than shuffling under the cursor.
        List<MenuAction> menu = List.of(command("Close Window"), command("Copy"), command("Close Tab"));
        assertEquals(List.of("Copy", "Close Tab", "Close Window"), labels(CommandPalette.orderedMatches(menu, "c")));
    }

    // ---- keywords ----

    @Test
    void theMenuNameFindsACommandWhoseLabelDoesNot() {
        MenuAction newTab = command("New Tab").withKeywords("File");
        List<MenuAction> found = CommandPalette.orderedMatches(List.of(newTab, command("Copy")), "file new");
        assertEquals(List.of("New Tab"), labels(found));
    }

    @Test
    void aKeywordMatchRanksBelowEveryLabelMatch() {
        // "f" through the File keyword must not outrank "f" in a label, however weakly it matches.
        MenuAction viaKeyword = command("New Tab").withKeywords("File");
        MenuAction viaLabel = command("Toggle Full Screen");
        assertEquals(
                List.of("Toggle Full Screen", "New Tab"),
                labels(CommandPalette.orderedMatches(List.of(viaKeyword, viaLabel), "f")));
        assertTrue(CommandPalette.keywordScore(viaKeyword, "f") < -CommandPalette.KEYWORD_PENALTY / 2);
    }

    @Test
    void theQueryHasToStartWithTheKeyword() {
        MenuAction newTab = command("New Tab").withKeywords("File");
        assertEquals(FuzzyMatch.NO_SCORE, CommandPalette.keywordScore(newTab, "xnew"));
        assertEquals(FuzzyMatch.NO_SCORE, CommandPalette.keywordScore(command("New Tab"), "file"), "no keywords");
    }

    // ---- the chord that opens it ----

    @Test
    void theDefaultIsEmacsMx() {
        assertEquals(new KeyCodeCombination(KeyCode.X, KeyCombination.ALT_DOWN), MenuAction.DEFAULT_PALETTE_CHORD);
    }

    @Test
    void aStoredChordIsRead() {
        KeyCombination chord = MenuAction.parseChord("Ctrl+Shift+P", MenuAction.DEFAULT_PALETTE_CHORD);
        assertEquals(new KeyCodeCombination(KeyCode.P, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), chord);
    }

    @Test
    void anUnreadableOrEmptyLineIsTheDefaultNotAnError() {
        // The settings file is hand-editable: clamp on read, as every other setting does.
        assertEquals(MenuAction.DEFAULT_PALETTE_CHORD, MenuAction.parseChord("", MenuAction.DEFAULT_PALETTE_CHORD));
        assertEquals(MenuAction.DEFAULT_PALETTE_CHORD, MenuAction.parseChord(null, MenuAction.DEFAULT_PALETTE_CHORD));
        assertEquals(
                MenuAction.DEFAULT_PALETTE_CHORD, MenuAction.parseChord("Ctrl+", MenuAction.DEFAULT_PALETTE_CHORD));
        assertEquals(
                MenuAction.DEFAULT_PALETTE_CHORD, MenuAction.parseChord("Banana+X", MenuAction.DEFAULT_PALETTE_CHORD));
    }

    @Test
    void sameActionIgnoresLabelAndChord() {
        Runnable run = () -> {};
        MenuAction a = MenuAction.of("Hide Menu Bar", run);
        MenuAction b = MenuAction.of("Show Menu Bar", MenuAction.DEFAULT_PALETTE_CHORD, run)
                .describedAs("x");
        assertTrue(a.sameActionAs(b));
        assertTrue(!a.sameActionAs(command("Hide Menu Bar")));
        assertTrue(!a.sameActionAs(null));
    }

    @Test
    void eightRowsOfThirtySixPlusTheChrome() {
        assertEquals(8 * 36.0, CommandPalette.listHeight(0));
        assertEquals(8 * 36.0 + 2, CommandPalette.listHeight(2));
    }
}
