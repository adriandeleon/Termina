package com.termina.ui;

import javafx.scene.input.KeyCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Emacs editing table for the query field, and the caret math behind it. */
class TextInputKeysTest {

    private static final String TEXT = "abc\n  hello\nlonger line here\n";
    //  offsets: a0 b1 c2 \n3 | (sp)4 (sp)5 h6 e7 l8 l9 o10 \n11 | l12 ... e27 \n28

    @Test
    void lineStartAndEnd() {
        assertEquals(0, TextInputKeys.lineStart(TEXT, 2)); // within "abc"
        assertEquals(3, TextInputKeys.lineEnd(TEXT, 1)); // end of "abc" (before \n)
        assertEquals(4, TextInputKeys.lineStart(TEXT, 8)); // within "  hello"
        assertEquals(11, TextInputKeys.lineEnd(TEXT, 8)); // end of "  hello"
    }

    @Test
    void lineEndAtEndOfTextHasNoNewline() {
        assertEquals(7, TextInputKeys.lineEnd("one\ntwo", 5));
    }

    @Test
    void caretsOutsideTheTextAreClamped() {
        assertEquals(0, TextInputKeys.lineStart("abc", -4));
        assertEquals(3, TextInputKeys.lineEnd("abc", 99));
    }

    @Test
    void theTableIsEditorasEmacsKeymapForTheSameCommands() {
        // Caret movement, the kills, the clipboard trio and undo — the chords a query field needs.
        for (String chord : new String[] {
            "C-a", "C-e", "C-f", "C-b", "M-f", "M-b", "C-d", "C-k", "M-d", "C-w", "M-w", "C-y", "C-/", "C-S--", "C-S-/"
        }) {
            assertTrue(TextInputKeys.EMACS.containsKey(chord), chord);
        }
        // Not here: these are the palette's own, and must reach its list before the field.
        for (String chord : new String[] {"C-n", "C-p", "C-v", "M-v", "C-g"}) {
            assertFalse(TextInputKeys.EMACS.containsKey(chord), chord);
        }
    }

    // --- KEY_TYPED swallow rule (macOS Option-composed characters must be typable) ---

    @Test
    void anOptionTypedCharacterIsNeverSwallowedOnItsOwn() {
        // German/Spanish Mac layouts type @ [ ] { } | \ ~ with Option. No handled press, no
        // Command/Control: it is text, on macOS as everywhere else.
        assertFalse(TextInputKeys.swallowTyped(false, true, false, false));
        assertFalse(TextInputKeys.swallowTyped(false, false, false, false));
    }

    @Test
    void theCharacterPairedWithAHandledPressIsSwallowed() {
        assertTrue(TextInputKeys.swallowTyped(true, true, false, false)); // Option-f ran word forward: no "ƒ"
        assertTrue(TextInputKeys.swallowTyped(true, false, false, false));
    }

    @Test
    void commandAndControlByProductsAreSwallowedOnMacOnly() {
        assertTrue(TextInputKeys.swallowTyped(false, true, true, false));
        assertTrue(TextInputKeys.swallowTyped(false, true, false, true));
        // Elsewhere Ctrl+Alt is AltGr — a character typed with Control down can be real text.
        assertFalse(TextInputKeys.swallowTyped(false, false, false, true));
    }

    @Test
    void aModifierOnItsOwnIsNoEditAndNoException() {
        assertNull(KeyChord.token(KeyCode.CONTROL, true, false, false, false));
        assertNull(TextInputKeys.actionFor(null));
        assertNull(TextInputKeys.actionFor(KeyChord.token(KeyCode.SHIFT, false, false, false, true)));
    }
}
