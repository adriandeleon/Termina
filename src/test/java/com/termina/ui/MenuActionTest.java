package com.termina.ui;

import java.util.Locale;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which chord an application action claims.
 *
 * <p>The decision that is ours is <b>whether Shift is required</b>. Off macOS it must be, because
 * the plain shortcut chord there is {@code Ctrl+<letter>}, which belongs to the shell — Ctrl+T is
 * readline's transpose, Ctrl+W deletes a word, Ctrl+C is SIGINT. Claiming one for a menu command
 * takes it away from every program running in the terminal, silently.
 *
 * <p>Whether {@code SHORTCUT_DOWN} resolves to Cmd or Ctrl is <em>not</em> ours — JavaFX decides
 * that against the running platform, so a test on macOS cannot observe the Linux modifier and must
 * not pretend to.
 */
class MenuActionTest {

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    /** A press with the platform's own shortcut modifier held. */
    private static KeyEvent shortcutPress(KeyCode code, boolean shift) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, !MAC, false, MAC);
    }

    @Test
    void onMacTheShortcutModifierAloneIsEnough() {
        KeyCombination chord = MenuAction.appChord(KeyCode.T, true);
        assertTrue(chord.match(shortcutPress(KeyCode.T, false)));
        assertFalse(chord.match(shortcutPress(KeyCode.T, true)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"T", "W", "N", "C", "V", "A", "K"})
    void elsewhereShiftIsRequiredSoThePlainControlChordStaysWithTheShell(String key) {
        KeyCode code = KeyCode.valueOf(key);
        KeyCombination chord = MenuAction.appChord(code, false);

        assertTrue(chord.match(shortcutPress(code, true)));
        // The point of the whole rule: unshifted, that chord is the shell's.
        assertFalse(
                chord.match(shortcutPress(code, false)), "plain shortcut+" + key + " must stay available to the shell");
    }

    @Test
    void theMenuItemAdvertisesExactlyTheChordTheFilterMatches() {
        // The reason MenuAction exists: the menu item and the key binding come from one value, so
        // the menu cannot advertise a shortcut that nothing implements.
        MenuAction action = MenuAction.of("New Tab", MenuAction.appChord(KeyCode.T), () -> {});
        assertEquals(action.accelerator(), action.toMenuItem().getAccelerator());
        assertTrue(action.matches(shortcutPress(KeyCode.T, !MAC)));
    }

    @Test
    void anActionWithoutAChordIsMenuOnly() {
        MenuAction action = MenuAction.of("Something", () -> {});
        assertNull(action.toMenuItem().getAccelerator());
        assertFalse(action.matches(shortcutPress(KeyCode.T, false)));
    }

    @Test
    void tabNavigationAlwaysRequiresShift() {
        KeyCombination chord = MenuAction.shiftChord(KeyCode.CLOSE_BRACKET);
        assertTrue(chord.match(shortcutPress(KeyCode.CLOSE_BRACKET, true)));
        assertFalse(chord.match(shortcutPress(KeyCode.CLOSE_BRACKET, false)));
    }

    @Test
    void theMenuItemRunsTheActionItWasGiven() {
        int[] calls = {0};
        MenuAction action = MenuAction.of("X", MenuAction.appChord(KeyCode.X), () -> calls[0]++);
        action.toMenuItem().fire();
        assertEquals(1, calls[0]);
    }

    @Test
    void aTabChordIsThePlatformConvention() {
        // Not appChord: Ctrl+Shift+1 is not what anyone reaches for, and both platforms already
        // have a convention — Cmd+1 on macOS, Alt+1 everywhere else.
        assertEquals(
                new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN),
                MenuAction.tabChord(KeyCode.DIGIT1, true));
        assertEquals(
                new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.ALT_DOWN),
                MenuAction.tabChord(KeyCode.DIGIT1, false));
    }

    @Test
    void closeWindowNeverSharesCloseTabsChord() {
        // Off macOS appChord(W) is Ctrl+Shift+W and shiftChord(W) was the same chord, so Close
        // Window's binding was dead while two menus and the palette advertised it.
        for (boolean mac : new boolean[] {true, false}) {
            assertNotEquals(MenuAction.appChord(KeyCode.W, mac), MenuAction.closeWindowChord(mac), "mac=" + mac);
        }
        assertTrue(MenuAction.closeWindowChord(false).match(shortcutPress(KeyCode.Q, true)));
    }

    @Test
    void theProfileChordsStayOffTheMacScreenshotKeys() {
        // Cmd+Shift+3, 4 and 5 are macOS's screenshots, taken before an application sees them.
        for (int n = 1; n <= 9; n++) {
            KeyCode digit = KeyCode.valueOf("DIGIT" + n);
            KeyCombination mac = MenuAction.profileChord(digit, true);
            assertEquals(new KeyCodeCombination(digit, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN), mac);
            assertEquals(
                    new KeyCodeCombination(digit, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                    MenuAction.profileChord(digit, false));
        }
    }

    @Test
    void theProfileChordsCollideWithNoOtherDigitChord() {
        // register() throws on two defaults on one chord; this says so before a window is built.
        for (boolean mac : new boolean[] {true, false}) {
            java.util.List<KeyCombination> all = new java.util.ArrayList<>();
            for (int n = 1; n <= 9; n++) {
                KeyCode digit = KeyCode.valueOf("DIGIT" + n);
                all.add(Keybindings.normalize(MenuAction.profileChord(digit, mac), mac));
                all.add(Keybindings.normalize(MenuAction.tabChord(digit, mac), mac));
            }
            all.add(Keybindings.normalize(new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN), mac));
            assertTrue(MenuAction.duplicateAccelerators(all).isEmpty(), "mac=" + mac);
        }
    }

    @Test
    void thePaletteIsNotOnAKeyTheShellOwns() {
        // Alt+X is M-x in emacs -nw and zsh's execute-named-cmd. Ctrl/Cmd+Shift+P is Windows
        // Terminal's and VS Code's, and Ctrl+Shift+letter is the application's on every platform.
        assertEquals(
                new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                MenuAction.DEFAULT_PALETTE_CHORD);
        assertFalse(MenuAction.DEFAULT_PALETTE_CHORD.match(
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.X, false, false, true, false)));
    }

    @Test
    void duplicateAcceleratorsAreNamedOnceEach() {
        KeyCombination w = MenuAction.shiftChord(KeyCode.W);
        KeyCombination t = MenuAction.shiftChord(KeyCode.T);
        assertTrue(MenuAction.duplicateAccelerators(java.util.List.of(w, t)).isEmpty());
        assertEquals(java.util.List.of(w), MenuAction.duplicateAccelerators(java.util.Arrays.asList(w, null, t, w, w)));
    }
}
