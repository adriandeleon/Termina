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
                KeybindingEdits.rebind(defaults(), Map.of(), "newTab", ctrlShift(KeyCode.Y), false);
        assertEquals(Map.of("newTab", "Ctrl+Shift+Y"), stored);
        assertEquals(ctrlShift(KeyCode.Y), on(stored).get("newTab"));
    }

    @Test
    void aRebindTakesTheChordFromTheCommandThatHadIt() {
        Map<String, String> stored =
                KeybindingEdits.rebind(defaults(), Map.of(), "closeTab", ctrlShift(KeyCode.T), false);
        assertEquals("none", stored.get("newTab"), "New Tab lost its chord, and the file says so");
        assertTrue(Keybindings.same(NEW_TAB, on(stored).get("closeTab"), false));
        assertNull(on(stored).get("newTab"));
    }

    @Test
    void aRebindBackToTheDefaultLeavesNoLine() {
        // A command moved back to where it started reads as unchanged, not as overridden with its
        // own default — which would stop following the default if a later version moved it.
        Map<String, String> moved = KeybindingEdits.rebind(defaults(), Map.of(), "newTab", ctrlShift(KeyCode.Y), false);
        Map<String, String> back = KeybindingEdits.rebind(defaults(), moved, "newTab", ctrlShift(KeyCode.T), false);
        assertEquals(Map.of(), back);
    }

    @Test
    void aRebindToNothingIsAClear() {
        assertEquals(Map.of("newTab", "none"), KeybindingEdits.rebind(defaults(), Map.of(), "newTab", null, false));
    }

    @Test
    void clearingACommandWithADefaultStoresNone() {
        Map<String, String> stored = KeybindingEdits.clear(defaults(), Map.of(), "closeTab");
        assertEquals(Map.of("closeTab", "none"), stored);
        assertNull(on(stored).get("closeTab"));
    }

    @Test
    void clearingACommandWithNoDefaultLeavesNoLine() {
        Map<String, String> given = Map.of("renameTab", "Ctrl+Shift+R");
        assertEquals(Map.of(), KeybindingEdits.clear(defaults(), given, "renameTab"));
    }

    @Test
    void resetDropsTheLine() {
        Map<String, String> moved = Map.of("newTab", "Ctrl+Shift+Y");
        assertEquals(Map.of(), KeybindingEdits.reset(defaults(), moved, "newTab", false));
    }

    @Test
    void resetTakesTheDefaultBackFromWhoeverHasIt() {
        // Close Tab took New Tab's chord. Resetting New Tab without taking it back left New Tab
        // unbound under a Reset button that said it had been reset — Editora shipped that once.
        Map<String, String> taken =
                KeybindingEdits.rebind(defaults(), Map.of(), "closeTab", ctrlShift(KeyCode.T), false);
        assertEquals("closeTab", KeybindingEdits.taken(defaults(), taken, "newTab", false));
        Map<String, String> reset = KeybindingEdits.reset(defaults(), taken, "newTab", false);
        assertTrue(Keybindings.same(NEW_TAB, on(reset).get("newTab"), false));
        assertNull(on(reset).get("closeTab"));
    }

    @Test
    void nothingIsTakenWhenTheDefaultIsFree() {
        assertNull(KeybindingEdits.taken(defaults(), Map.of(), "newTab", false));
        assertNull(KeybindingEdits.taken(defaults(), Map.of(), "renameTab", false), "no default, nothing to take");
    }

    @Test
    void aConflictNamesTheOtherCommandNeverTheSameOne() {
        Map<String, KeyCombination> on = on(Map.of());
        assertEquals("closeTab", KeybindingEdits.conflict(on, ctrlShift(KeyCode.W), "newTab", false));
        assertNull(KeybindingEdits.conflict(on, ctrlShift(KeyCode.W), "closeTab", false));
        assertNull(KeybindingEdits.conflict(on, ctrlShift(KeyCode.Y), "newTab", false));
    }

    @Test
    void changedMeansAnywhereButTheDefault() {
        assertFalse(KeybindingEdits.isChanged(defaults(), on(Map.of()), "newTab", false));
        assertFalse(KeybindingEdits.isChanged(defaults(), on(Map.of()), "renameTab", false));
        assertTrue(KeybindingEdits.isChanged(defaults(), on(Map.of("newTab", "none")), "newTab", false));
        assertTrue(KeybindingEdits.isChanged(defaults(), on(Map.of("renameTab", "Alt+R")), "renameTab", false));
        // Lost to another command counts: it is not on its default any more.
        assertTrue(KeybindingEdits.isChanged(defaults(), on(Map.of("closeTab", "Ctrl+Shift+T")), "newTab", false));
    }

    @Test
    void theDefaultSpelledAnotherWayIsNotAChange() {
        // A hand-edited Ctrl+Shift+W is Close Tab's Shortcut+Shift+W off macOS, and its Reset
        // button would do nothing.
        Map<String, KeyCombination> on = on(Map.of("closeTab", "Ctrl+Shift+W"));
        assertTrue(Keybindings.same(CLOSE_TAB, on.get("closeTab"), false));
        assertFalse(KeybindingEdits.isChanged(defaults(), on, "closeTab", false));
    }
}
