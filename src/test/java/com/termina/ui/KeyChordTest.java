package com.termina.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Key presses become Editora's chord tokens, and the tokens become something JavaFX can display. */
class KeyChordTest {

    @Test
    void modifiersComeInAFixedOrder() {
        assertEquals("C-n", KeyChord.token(KeyCode.N, true, false, false, false));
        assertEquals("M-x", KeyChord.token(KeyCode.X, false, true, false, false));
        assertEquals("C-M-Cmd-S-a", KeyChord.token(KeyCode.A, true, true, true, true));
        assertEquals("M-S-,", KeyChord.token(KeyCode.COMMA, false, true, false, true));
        assertEquals("C-S-/", KeyChord.token(KeyCode.SLASH, true, false, false, true));
    }

    @Test
    void namedKeysUseTheirKeymapNames() {
        assertEquals("enter", KeyChord.token(KeyCode.ENTER, false, false, false, false));
        assertEquals("escape", KeyChord.token(KeyCode.ESCAPE, false, false, false, false));
        assertEquals("pagedown", KeyChord.token(KeyCode.PAGE_DOWN, false, false, false, false));
        assertEquals("C-home", KeyChord.token(KeyCode.HOME, true, false, false, false));
        assertEquals("f11", KeyChord.token(KeyCode.F11, false, false, false, false));
    }

    @Test
    void aNumpadDigitIsTheDigit() {
        assertEquals("M-6", KeyChord.token(KeyCode.NUMPAD6, false, true, false, false));
        assertEquals("M-6", KeyChord.token(KeyCode.DIGIT6, false, true, false, false));
    }

    @Test
    void aModifierOnItsOwnIsNotAChord() {
        assertNull(KeyChord.token(KeyCode.CONTROL, true, false, false, false));
        assertNull(KeyChord.token(KeyCode.SHIFT, false, false, false, true));
        assertNull(KeyChord.token(KeyCode.UNDEFINED, false, false, false, false));
        assertNull(KeyChord.token(null, false, false, false, false));
    }

    @Test
    void aTokenBecomesTheCombinationItNames() {
        assertEquals(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), KeyChord.toCombination("C-n"));
        assertEquals(new KeyCodeCombination(KeyCode.X, KeyCombination.ALT_DOWN), KeyChord.toCombination("M-x"));
        assertEquals(
                new KeyCodeCombination(KeyCode.COMMA, KeyCombination.ALT_DOWN, KeyCombination.SHIFT_DOWN),
                KeyChord.toCombination("M-S-,"));
        assertEquals(new KeyCodeCombination(KeyCode.PAGE_UP), KeyChord.toCombination("pageup"));
        assertEquals(new KeyCodeCombination(KeyCode.DIGIT6, KeyCombination.ALT_DOWN), KeyChord.toCombination("M-6"));
        assertEquals(new KeyCodeCombination(KeyCode.END, KeyCombination.META_DOWN), KeyChord.toCombination("Cmd-end"));
    }

    @Test
    void aMinusIsAKeyNotAStrayDash() {
        // "C-S--" is Ctrl+Shift+Minus (Emacs undo); the trailing dash is the key.
        assertEquals(
                new KeyCodeCombination(KeyCode.MINUS, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
                KeyChord.toCombination("C-S--"));
    }

    @Test
    void anUnknownTokenHasNoCombinationButStillDisplays() {
        assertNull(KeyChord.toCombination("C-nosuchkey"));
        assertNull(KeyChord.toCombination(""));
        assertNull(KeyChord.toCombination(null));
        assertEquals("C-nosuchkey", KeyChord.display("C-nosuchkey"), "a legend never goes blank");
    }

    @Test
    void displayIsThePlatformsNotation() {
        String shown = KeyChord.display("C-n");
        assertNotNull(shown);
        assertEquals(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN).getDisplayText(), shown);
    }
}
