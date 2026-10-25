package com.termina.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which chord each command ends up on, from the defaults and a hand-editable file. */
class KeybindingsTest {

    static final KeyCombination NEW_TAB = MenuAction.appChord(KeyCode.T, false);
    static final KeyCombination CLOSE_TAB = MenuAction.appChord(KeyCode.W, false);
    static final KeyCombination PALETTE = MenuAction.DEFAULT_PALETTE_CHORD;

    /** New Tab, Close Tab, the palette, and Rename Tab, which has no chord of its own. */
    static Map<String, KeyCombination> defaults() {
        Map<String, KeyCombination> defaults = new LinkedHashMap<>();
        defaults.put("newTab", NEW_TAB);
        defaults.put("closeTab", CLOSE_TAB);
        defaults.put("commandPalette", PALETTE);
        defaults.put("renameTab", null);
        return defaults;
    }

    static KeyCombination ctrlShift(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
    }

    @Test
    void nothingStoredIsTheDefaults() {
        assertEquals(defaults(), Keybindings.resolve(defaults(), Map.of(), false));
    }

    @Test
    void aStoredChordReplacesTheDefault() {
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), Map.of("newTab", "Ctrl+Shift+Y"), false);
        assertEquals(ctrlShift(KeyCode.Y), on.get("newTab"));
        assertEquals(CLOSE_TAB, on.get("closeTab"));
    }

    @Test
    void aCommandWithNoDefaultCanBeGivenOne() {
        assertEquals(
                ctrlShift(KeyCode.R),
                Keybindings.resolve(defaults(), Map.of("renameTab", "Ctrl+Shift+R"), false)
                        .get("renameTab"));
    }

    @Test
    void noneUnbinds() {
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), Map.of("newTab", "none"), false);
        assertTrue(on.containsKey("newTab"));
        assertNull(on.get("newTab"));
    }

    @Test
    void anUnreadableOrBlankLineIsTheDefault() {
        // Hand-edited: clamp on read, as every other setting does.
        Map<String, String> stored = Map.of("newTab", "Banana+X", "closeTab", "  ", "commandPalette", "Ctrl+");
        assertEquals(defaults(), Keybindings.resolve(defaults(), stored, false));
    }

    @Test
    void aCharacterChordIsNotOneTheTableCanCompare() {
        // valueOf reads an unknown key name as a character combination, which matches on what a
        // key types; the recorder never writes one, so nothing could be seen to collide with it.
        assertNull(Keybindings.parse("Ctrl+'q'"));
        assertEquals(ctrlShift(KeyCode.P), Keybindings.parse("Ctrl+Shift+P"));
    }

    @Test
    void anUnknownCommandIsIgnored() {
        assertEquals(defaults(), Keybindings.resolve(defaults(), Map.of("splitPane", "Ctrl+Shift+D"), false));
    }

    @Test
    void aStoredChordTakesADefaultFromTheCommandThatHadIt() {
        // The user put Close Tab on New Tab's chord: New Tab has lost it, rather than the edit
        // silently not applying because New Tab came first in the table.
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), Map.of("closeTab", "Shortcut+Shift+T"), false);
        assertTrue(Keybindings.same(NEW_TAB, on.get("closeTab"), false));
        assertNull(on.get("newTab"));
    }

    @Test
    void twoStoredLinesOnOneChordKeepTheFirstAndTheSecondFallsBack() {
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("newTab", "Ctrl+Shift+Y");
        stored.put("closeTab", "Ctrl+Shift+Y");
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), stored, false);
        assertEquals(ctrlShift(KeyCode.Y), on.get("newTab"));
        assertEquals(CLOSE_TAB, on.get("closeTab"));
    }

    @Test
    void noChordEndsUpOnTwoCommands() {
        Map<String, String> stored = Map.of("commandPalette", "Ctrl+Shift+W", "renameTab", "Alt+X");
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), stored, false);
        java.util.Set<KeyCombination> seen = new java.util.HashSet<>();
        for (KeyCombination chord : on.values()) {
            if (chord != null) assertTrue(seen.add(Keybindings.normalize(chord, false)), on::toString);
        }
    }

    @Test
    void tableOrderIsKept() {
        assertEquals(
                java.util.List.copyOf(defaults().keySet()),
                java.util.List.copyOf(Keybindings.resolve(defaults(), Map.of("renameTab", "Alt+R"), false)
                        .keySet()));
    }

    @Test
    void shortcutIsTheKeyItIsOnThisPlatform() {
        // The defaults say Shortcut, the recorder says Ctrl or Meta, and equals() compares them as
        // written — two commands on one keystroke, unequal.
        KeyCombination shortcut = new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN);
        KeyCombination control = new KeyCodeCombination(KeyCode.T, KeyCombination.CONTROL_DOWN);
        KeyCombination meta = new KeyCodeCombination(KeyCode.T, KeyCombination.META_DOWN);
        assertFalse(shortcut.equals(control));
        assertTrue(Keybindings.same(shortcut, control, false));
        assertFalse(Keybindings.same(shortcut, meta, false));
        assertTrue(Keybindings.same(shortcut, meta, true));
        assertFalse(Keybindings.same(shortcut, control, true));
    }

    @Test
    void noChordIsTheSameAsNothing() {
        assertFalse(Keybindings.same(null, null, false));
        assertFalse(Keybindings.same(NEW_TAB, null, false));
    }

    @Test
    void whatIsStoredReadsBack() {
        java.util.List<KeyCombination> chords =
                new java.util.ArrayList<>(defaults().values());
        chords.add(Keybindings.of(KeyCode.F5, true, true, true, true));
        chords.add(Keybindings.of(KeyCode.OPEN_BRACKET, true, false, false, true));
        chords.add(Keybindings.of(KeyCode.PAGE_UP, false, true, false, false));
        for (KeyCombination chord : chords) {
            if (chord != null) assertEquals(chord, Keybindings.parse(Keybindings.stored(chord)), chord::getName);
        }
    }

    @Test
    void whatIsStoredReadsTheWayPeopleWriteIt() {
        assertEquals("Ctrl+Shift+Y", Keybindings.stored(ctrlShift(KeyCode.Y)));
        assertEquals("Shortcut+Shift+T", Keybindings.stored(NEW_TAB));
    }
}
