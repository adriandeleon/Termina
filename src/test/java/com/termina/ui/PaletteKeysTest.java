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

    /** The movement chords as the app starts with them: the defaults of {@code keymaps/lists.json}. */
    private static final java.util.Map<String, Action> DEFAULTS =
            PaletteKeys.moves(id -> KeyChord.of(Keymap.lists().get(id), false));

    @BeforeAll
    static void english() {
        com.termina.i18n.Messages.init("en");
    }

    @Test
    void theFixedKeysAlwaysWork() {
        assertEquals(Action.DOWN, PaletteKeys.resolve("down", true, DEFAULTS));
        assertEquals(Action.UP, PaletteKeys.resolve("up", true, DEFAULTS));
        assertEquals(Action.PAGE_DOWN, PaletteKeys.resolve("pagedown", true, DEFAULTS));
        assertEquals(Action.PAGE_UP, PaletteKeys.resolve("pageup", true, DEFAULTS));
        assertEquals(Action.ACCEPT, PaletteKeys.resolve("enter", true, DEFAULTS));
        assertEquals(Action.CANCEL, PaletteKeys.resolve("escape", true, DEFAULTS));
        assertEquals(Action.FIRST, PaletteKeys.resolve("C-home", true, DEFAULTS));
        assertEquals(Action.LAST, PaletteKeys.resolve("C-end", true, DEFAULTS));
        assertEquals(Action.FIRST, PaletteKeys.resolve("Cmd-up", true, DEFAULTS));
        assertEquals(Action.LAST, PaletteKeys.resolve("Cmd-down", true, DEFAULTS));
    }

    @Test
    void plainHomeAndEndBelongToTheQueryFieldWhenThereIsOne() {
        assertEquals(Action.NONE, PaletteKeys.resolve("home", true, DEFAULTS));
        assertEquals(Action.NONE, PaletteKeys.resolve("end", true, DEFAULTS));
        assertEquals(Action.FIRST, PaletteKeys.resolve("home", false, DEFAULTS));
        assertEquals(Action.LAST, PaletteKeys.resolve("end", false, DEFAULTS));
    }

    /**
     * PAL-06 (Pauta 6.0.1): a row and a page move on the chords the bindings give the four
     * {@code nav} commands, looked up and not written into the palette.
     */
    @Test
    void aRowAndAPageMoveOnTheChordsBoundToTheirCommands() {
        java.util.Map<String, String> bound = new java.util.HashMap<>();
        bound.put("nav.lineDown", "C-j");
        bound.put("nav.lineUp", "C-k");
        bound.put("nav.pageDown", "M-f");
        // nav.pageUp is on no key.
        java.util.Map<String, Action> moves = PaletteKeys.moves(bound::get);
        assertEquals(Action.DOWN, PaletteKeys.resolve("C-j", true, moves));
        assertEquals(Action.UP, PaletteKeys.resolve("C-k", true, moves));
        assertEquals(Action.PAGE_DOWN, PaletteKeys.resolve("M-f", true, moves));
        // The chords they were on do nothing now: none of the four is a fixed letter.
        for (String was : new String[] {"C-n", "C-p", "C-v", "M-v"}) {
            assertEquals(Action.NONE, PaletteKeys.resolve(was, true, moves), was);
        }
        // And the arrows and the page keys move whatever the bindings say, with none at all too.
        for (java.util.Map<String, Action> any : java.util.List.of(moves, java.util.Map.<String, Action>of())) {
            assertEquals(Action.DOWN, PaletteKeys.resolve("down", true, any));
            assertEquals(Action.UP, PaletteKeys.resolve("up", true, any));
            assertEquals(Action.PAGE_DOWN, PaletteKeys.resolve("pagedown", true, any));
            assertEquals(Action.PAGE_UP, PaletteKeys.resolve("pageup", true, any));
        }
        // The legend names the chords in force, and only the arrows when a row is on no key.
        assertTrue(PaletteKeys.legend(moves).contains(KeyChord.display("C-j")));
        assertFalse(PaletteKeys.legend(moves).contains(KeyChord.display("C-n")));
        assertTrue(PaletteKeys.legend(java.util.Map.of()).startsWith(tr("pickerKeys.move", "↑↓")));
    }

    @Test
    void theFourCommandsAndTheirDefaultsAreTheListKeyMaps() {
        assertEquals(
                java.util.List.of("nav.lineDown", "nav.lineUp", "nav.pageDown", "nav.pageUp"),
                java.util.List.copyOf(PaletteKeys.MOVES.keySet()));
        assertEquals(PaletteKeys.MOVES.keySet(), Keymap.lists().keySet());
        assertEquals(
                java.util.Map.of("C-n", Action.DOWN, "C-p", Action.UP, "C-v", Action.PAGE_DOWN, "M-v", Action.PAGE_UP),
                DEFAULTS);
        // They are not application chords: the window's key filter passes them over, and the
        // platform key maps, which may bind no plain Ctrl or Alt with a letter, do not hold them.
        for (String id : PaletteKeys.MOVES.keySet()) {
            assertTrue(WindowRules.actsOnlyInLists(id), id);
            assertFalse(Keymap.load(false).containsKey(id), id);
            assertFalse(Keymap.load(true).containsKey(id), id);
            assertTrue(Keymap.defaultFor(id) != null, id);
        }
        assertFalse(WindowRules.actsOnlyInLists("file.newTab"));
        assertFalse(WindowRules.actsOnlyInLists(null));
    }

    @Test
    void theEmacsChordsNavigate() {
        // By default: Emacs's own, from keymaps/lists.json; the ends and cancel from the table.
        assertEquals(Action.DOWN, PaletteKeys.resolve("C-n", true, DEFAULTS));
        assertEquals(Action.UP, PaletteKeys.resolve("C-p", true, DEFAULTS));
        assertEquals(Action.PAGE_DOWN, PaletteKeys.resolve("C-v", true, DEFAULTS));
        assertEquals(Action.PAGE_UP, PaletteKeys.resolve("M-v", true, DEFAULTS));
        assertEquals(Action.FIRST, PaletteKeys.resolve("M-S-,", true, DEFAULTS));
        assertEquals(Action.LAST, PaletteKeys.resolve("M-S-.", true, DEFAULTS));
        assertEquals(Action.CANCEL, PaletteKeys.resolve("C-g", true, DEFAULTS));
    }

    @Test
    void anythingElseIsTyping() {
        assertEquals(Action.NONE, PaletteKeys.resolve("a", true, DEFAULTS));
        assertEquals(Action.NONE, PaletteKeys.resolve("n", true, DEFAULTS), "a bare n is a letter, not next");
        assertEquals(Action.NONE, PaletteKeys.resolve("C-a", true, DEFAULTS), "C-a is the query field's line start");
        assertEquals(Action.NONE, PaletteKeys.resolve("C-S-n", true, DEFAULTS), "the Emacs chords are unshifted");
        assertEquals(Action.NONE, PaletteKeys.resolve(null, true, DEFAULTS), "a modifier on its own");
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
                PaletteKeys.legend(DEFAULTS, PaletteKeys.hint("run", "↵")));
        assertTrue(
                PaletteKeys.legend(DEFAULTS).contains("Ctrl")
                        || PaletteKeys.legend(DEFAULTS).contains("⌃"),
                "a real key name");
    }

    @Test
    void anEntryWithNoKeyIsLeftOutNotShownKeyless() {
        assertNull(PaletteKeys.hint("run", ""));
        assertNull(PaletteKeys.hint("run", null));
        String legend = PaletteKeys.legend(DEFAULTS, (String) null);
        assertEquals(2, legend.split("  ·  ").length, legend);
    }
}
