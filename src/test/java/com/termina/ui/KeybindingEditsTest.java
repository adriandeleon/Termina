package com.termina.ui;

import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static com.termina.ui.KeybindingsTest.CLOSE_TAB;
import static com.termina.ui.KeybindingsTest.NEW_TAB;
import static com.termina.ui.KeybindingsTest.ctrlShift;
import static com.termina.ui.KeybindingsTest.defaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the keyboard settings store for a rebind, a clear and a reset. */
class KeybindingEditsTest {

    private static Map<String, KeyCombination> on(Map<String, String> stored) {
        return Keybindings.resolve(defaults(), stored, false);
    }

    @Test
    void aRebindToAFreeChordStoresIt() {
        Map<String, String> stored =
                KeybindingEdits.rebind(defaults(), Map.of(), "file.newTab", ctrlShift(KeyCode.Y), false);
        assertEquals(Map.of("file.newTab", "Ctrl+Shift+Y"), stored);
        assertEquals(ctrlShift(KeyCode.Y), on(stored).get("file.newTab"));
    }

    @Test
    void aRebindTakesTheChordFromTheCommandThatHadIt() {
        Map<String, String> stored =
                KeybindingEdits.rebind(defaults(), Map.of(), "file.closeTab", ctrlShift(KeyCode.T), false);
        assertEquals("none", stored.get("file.newTab"), "New Tab lost its chord, and the file says so");
        assertTrue(Keybindings.same(NEW_TAB, on(stored).get("file.closeTab"), false));
        assertNull(on(stored).get("file.newTab"));
    }

    @Test
    void aRebindBackToTheDefaultLeavesNoLine() {
        // A command moved back to where it started reads as unchanged, not as overridden with its
        // own default — which would stop following the default if a later version moved it.
        Map<String, String> moved =
                KeybindingEdits.rebind(defaults(), Map.of(), "file.newTab", ctrlShift(KeyCode.Y), false);
        Map<String, String> back =
                KeybindingEdits.rebind(defaults(), moved, "file.newTab", ctrlShift(KeyCode.T), false);
        assertEquals(Map.of(), back);
    }

    @Test
    void aRebindToNothingIsAClear() {
        assertEquals(
                Map.of("file.newTab", "none"),
                KeybindingEdits.rebind(defaults(), Map.of(), "file.newTab", null, false));
    }

    @Test
    void clearingACommandWithADefaultStoresNone() {
        Map<String, String> stored = KeybindingEdits.clear(defaults(), Map.of(), "file.closeTab");
        assertEquals(Map.of("file.closeTab", "none"), stored);
        assertNull(on(stored).get("file.closeTab"));
    }

    @Test
    void clearingACommandWithNoDefaultLeavesNoLine() {
        Map<String, String> given = Map.of("tab.rename", "Ctrl+Shift+R");
        assertEquals(Map.of(), KeybindingEdits.clear(defaults(), given, "tab.rename"));
    }

    @Test
    void resetDropsTheLine() {
        Map<String, String> moved = Map.of("file.newTab", "Ctrl+Shift+Y");
        assertEquals(Map.of(), KeybindingEdits.reset(defaults(), moved, "file.newTab", false));
    }

    @Test
    void resetTakesTheDefaultBackFromWhoeverHasIt() {
        // Close Tab took New Tab's chord. Resetting New Tab without taking it back left New Tab
        // unbound under a Reset button that said it had been reset — Editora shipped that once.
        Map<String, String> taken =
                KeybindingEdits.rebind(defaults(), Map.of(), "file.closeTab", ctrlShift(KeyCode.T), false);
        assertEquals("file.closeTab", KeybindingEdits.taken(defaults(), taken, "file.newTab", false));
        Map<String, String> reset = KeybindingEdits.reset(defaults(), taken, "file.newTab", false);
        assertTrue(Keybindings.same(NEW_TAB, on(reset).get("file.newTab"), false));
        assertNull(on(reset).get("file.closeTab"));
    }

    @Test
    void nothingIsTakenWhenTheDefaultIsFree() {
        assertNull(KeybindingEdits.taken(defaults(), Map.of(), "file.newTab", false));
        assertNull(KeybindingEdits.taken(defaults(), Map.of(), "tab.rename", false), "no default, nothing to take");
    }

    @Test
    void aConflictNamesTheOtherCommandNeverTheSameOne() {
        Map<String, KeyCombination> on = on(Map.of());
        assertEquals("file.closeTab", KeybindingEdits.conflict(on, ctrlShift(KeyCode.W), "file.newTab", false));
        assertNull(KeybindingEdits.conflict(on, ctrlShift(KeyCode.W), "file.closeTab", false));
        assertNull(KeybindingEdits.conflict(on, ctrlShift(KeyCode.Y), "file.newTab", false));
    }

    @Test
    void changedMeansAnywhereButTheDefault() {
        assertFalse(KeybindingEdits.isChanged(defaults(), on(Map.of()), "file.newTab", false));
        assertFalse(KeybindingEdits.isChanged(defaults(), on(Map.of()), "tab.rename", false));
        assertTrue(KeybindingEdits.isChanged(defaults(), on(Map.of("file.newTab", "none")), "file.newTab", false));
        assertTrue(KeybindingEdits.isChanged(defaults(), on(Map.of("tab.rename", "Alt+R")), "tab.rename", false));
        // Lost to another command counts: it is not on its default any more.
        assertTrue(KeybindingEdits.isChanged(
                defaults(), on(Map.of("file.closeTab", "Ctrl+Shift+T")), "file.newTab", false));
    }

    @Test
    void theDefaultSpelledAnotherWayIsNotAChange() {
        // A hand-edited Ctrl+Shift+W is Close Tab's Shortcut+Shift+W off macOS, and its Reset
        // button would do nothing.
        Map<String, KeyCombination> on = on(Map.of("file.closeTab", "Ctrl+Shift+W"));
        assertTrue(Keybindings.same(CLOSE_TAB, on.get("file.closeTab"), false));
        assertFalse(KeybindingEdits.isChanged(defaults(), on, "file.closeTab", false));
    }
}
