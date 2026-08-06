package com.termina.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
                chord.match(shortcutPress(code, false)),
                "plain shortcut+" + key + " must stay available to the shell");
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
}
