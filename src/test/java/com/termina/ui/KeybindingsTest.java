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
        defaults.put("file.newTab", NEW_TAB);
        defaults.put("file.closeTab", CLOSE_TAB);
        defaults.put("palette.show", PALETTE);
        defaults.put("tab.rename", null);
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
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), Map.of("file.newTab", "Ctrl+Shift+Y"), false);
        assertEquals(ctrlShift(KeyCode.Y), on.get("file.newTab"));
        assertEquals(CLOSE_TAB, on.get("file.closeTab"));
    }

    @Test
    void aCommandWithNoDefaultCanBeGivenOne() {
        assertEquals(
                ctrlShift(KeyCode.R),
                Keybindings.resolve(defaults(), Map.of("tab.rename", "Ctrl+Shift+R"), false)
                        .get("tab.rename"));
    }

    @Test
    void noneUnbinds() {
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), Map.of("file.newTab", "none"), false);
        assertTrue(on.containsKey("file.newTab"));
        assertNull(on.get("file.newTab"));
    }

    @Test
    void anUnreadableOrBlankLineIsTheDefault() {
        // Hand-edited: clamp on read, as every other setting does.
        Map<String, String> stored = Map.of("file.newTab", "Banana+X", "file.closeTab", "  ", "palette.show", "Ctrl+");
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
        Map<String, KeyCombination> on =
                Keybindings.resolve(defaults(), Map.of("file.closeTab", "Shortcut+Shift+T"), false);
        assertTrue(Keybindings.same(NEW_TAB, on.get("file.closeTab"), false));
        assertNull(on.get("file.newTab"));
    }

    @Test
    void twoStoredLinesOnOneChordKeepTheFirstAndTheSecondFallsBack() {
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("file.newTab", "Ctrl+Shift+Y");
        stored.put("file.closeTab", "Ctrl+Shift+Y");
        Map<String, KeyCombination> on = Keybindings.resolve(defaults(), stored, false);
        assertEquals(ctrlShift(KeyCode.Y), on.get("file.newTab"));
        assertEquals(CLOSE_TAB, on.get("file.closeTab"));
    }

    @Test
    void noChordEndsUpOnTwoCommands() {
        Map<String, String> stored = Map.of("palette.show", "Ctrl+Shift+W", "tab.rename", "Alt+X");
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
                java.util.List.copyOf(Keybindings.resolve(defaults(), Map.of("tab.rename", "Alt+R"), false)
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
