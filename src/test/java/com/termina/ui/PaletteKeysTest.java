package com.termina.ui;

import com.termina.ui.PaletteKeys.Action;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.termina.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The palette moves on the fixed keys and on Emacs's, and its legend names only keys that reach it. */
class PaletteKeysTest {

    @BeforeAll
    static void english() {
        com.termina.i18n.Messages.init("en");
    }

    @Test
    void theFixedKeysAlwaysWork() {
        assertEquals(Action.DOWN, PaletteKeys.resolve("down", true));
        assertEquals(Action.UP, PaletteKeys.resolve("up", true));
        assertEquals(Action.PAGE_DOWN, PaletteKeys.resolve("pagedown", true));
        assertEquals(Action.PAGE_UP, PaletteKeys.resolve("pageup", true));
        assertEquals(Action.ACCEPT, PaletteKeys.resolve("enter", true));
        assertEquals(Action.CANCEL, PaletteKeys.resolve("escape", true));
        assertEquals(Action.FIRST, PaletteKeys.resolve("C-home", true));
        assertEquals(Action.LAST, PaletteKeys.resolve("C-end", true));
        assertEquals(Action.FIRST, PaletteKeys.resolve("Cmd-up", true));
        assertEquals(Action.LAST, PaletteKeys.resolve("Cmd-down", true));
    }

    @Test
    void plainHomeAndEndBelongToTheQueryFieldWhenThereIsOne() {
        assertEquals(Action.NONE, PaletteKeys.resolve("home", true));
        assertEquals(Action.NONE, PaletteKeys.resolve("end", true));
        assertEquals(Action.FIRST, PaletteKeys.resolve("home", false));
        assertEquals(Action.LAST, PaletteKeys.resolve("end", false));
    }

    @Test
    void theEmacsChordsNavigate() {
        // Editora's Emacs keymap, token for token.
        assertEquals(Action.DOWN, PaletteKeys.resolve("C-n", true));
        assertEquals(Action.UP, PaletteKeys.resolve("C-p", true));
        assertEquals(Action.PAGE_DOWN, PaletteKeys.resolve("C-v", true));
        assertEquals(Action.PAGE_UP, PaletteKeys.resolve("M-v", true));
        assertEquals(Action.FIRST, PaletteKeys.resolve("M-S-,", true));
        assertEquals(Action.LAST, PaletteKeys.resolve("M-S-.", true));
        assertEquals(Action.CANCEL, PaletteKeys.resolve("C-g", true));
    }

    @Test
    void anythingElseIsTyping() {
        assertEquals(Action.NONE, PaletteKeys.resolve("a", true));
        assertEquals(Action.NONE, PaletteKeys.resolve("n", true), "a bare n is a letter, not next");
        assertEquals(Action.NONE, PaletteKeys.resolve("C-a", true), "C-a is the query field's line start");
        assertEquals(Action.NONE, PaletteKeys.resolve("C-S-n", true), "the Emacs chords are unshifted");
        assertEquals(Action.NONE, PaletteKeys.resolve(null, true), "a modifier on its own");
    }

    @Test
    void upAndDownWrap() {
        assertEquals(1, PaletteKeys.target(Action.DOWN, 0, 3, 8));
        assertEquals(0, PaletteKeys.target(Action.DOWN, 2, 3, 8));
        assertEquals(2, PaletteKeys.target(Action.UP, 0, 3, 8));
        assertEquals(0, PaletteKeys.target(Action.DOWN, -1, 3, 8), "nothing selected: Down starts at the top");
        assertEquals(2, PaletteKeys.target(Action.UP, -1, 3, 8), "nothing selected: Up starts at the bottom");
    }

    @Test
    void pagingAndEndsClampInsteadOfWrapping() {
        assertEquals(8, PaletteKeys.target(Action.PAGE_DOWN, 0, 20, 8));
        assertEquals(19, PaletteKeys.target(Action.PAGE_DOWN, 15, 20, 8));
        assertEquals(0, PaletteKeys.target(Action.PAGE_UP, 3, 20, 8));
        assertEquals(7, PaletteKeys.target(Action.PAGE_UP, 15, 20, 8));
        assertEquals(0, PaletteKeys.target(Action.FIRST, 15, 20, 8));
        assertEquals(19, PaletteKeys.target(Action.LAST, 0, 20, 8));
        assertEquals(1, PaletteKeys.target(Action.PAGE_DOWN, 0, 20, 0), "a page is never less than one row");
    }

    @Test
    void nonNavigationAndEmptyListsHaveNoTarget() {
        assertEquals(-1, PaletteKeys.target(Action.ACCEPT, 0, 3, 8));
        assertEquals(-1, PaletteKeys.target(Action.NONE, 0, 3, 8));
        assertEquals(-1, PaletteKeys.target(Action.DOWN, 0, 0, 8));
        assertTrue(PaletteKeys.isNavigation(Action.PAGE_UP));
        assertFalse(PaletteKeys.isNavigation(Action.CANCEL));
    }

    @Test
    void theLegendNamesTheEmacsChordsBesideTheFixedKeys() {
        // In the platform's notation — the same the menus use — not the raw tokens.
        String move = "↑↓ / " + KeyChord.display("C-n") + " " + KeyChord.display("C-p");
        String cancel = "Esc / " + KeyChord.display("C-g");
        assertEquals(
                tr("pickerKeys.move", move) + "  ·  " + tr("pickerKeys.run", "↵") + "  ·  "
                        + tr("pickerKeys.cancel", cancel),
                PaletteKeys.legend(PaletteKeys.hint("run", "↵")));
        assertTrue(PaletteKeys.legend().contains("Ctrl") || PaletteKeys.legend().contains("⌃"), "a real key name");
    }

    @Test
    void anEntryWithNoKeyIsLeftOutNotShownKeyless() {
        assertNull(PaletteKeys.hint("run", ""));
        assertNull(PaletteKeys.hint("run", null));
        String legend = PaletteKeys.legend((String) null);
        assertEquals(2, legend.split("  ·  ").length, legend);
    }
}
