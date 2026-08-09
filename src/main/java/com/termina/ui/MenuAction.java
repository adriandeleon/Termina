package com.termina.ui;

import java.util.Locale;

import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * One action, defined once and used twice: as a menu item and as a key binding.
 *
 * <p>Both are needed, and they must not drift. The menu cannot supply the binding on its own —
 * JavaFX fires accelerators only after the event has bubbled unconsumed, and {@link TerminalView}
 * consumes {@code Ctrl+<letter>} first, encoding it as a control byte for the shell. So the binding
 * is a scene-level filter, which runs before any of that. Keeping the {@link KeyCombination} in one
 * place is what stops the menu from advertising a shortcut the filter does not implement.
 *
 * @param label menu text
 * @param accelerator the chord, or null for a menu-only action
 * @param action what to run
 */
public record MenuAction(String label, KeyCombination accelerator, Runnable action) {

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    /**
     * The platform's chord for an application action on a letter key.
     *
     * <p>macOS gets {@code Cmd+key}. Everywhere else it is {@code Ctrl+Shift+key}, not
     * {@code Ctrl+key}, because plain Ctrl+letter belongs to the shell — Ctrl+T is readline's
     * transpose, Ctrl+W deletes a word, Ctrl+N is next-history. This is why every Linux terminal
     * uses Ctrl+Shift for its own commands.
     */
    public static KeyCombination appChord(KeyCode key) {
        return appChord(key, MAC);
    }

    /** Package-visible so both platform branches are testable; {@link #MAC} is read once at load. */
    static KeyCombination appChord(KeyCode key, boolean mac) {
        return mac
                ? new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN)
                : new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    }

    /**
     * The platform's chord for jumping to a tab by number.
     *
     * <p>Not {@link #appChord}: Ctrl+Shift+1 is not what anyone reaches for, and both platforms
     * already have a convention — Cmd+1 on macOS, Alt+1 everywhere else, which is what GNOME
     * Terminal and Konsole use. It costs the shell Alt+digit (readline's digit-argument), which is
     * the trade every terminal that offers this has already made.
     */
    public static KeyCombination tabChord(KeyCode digit) {
        return tabChord(digit, MAC);
    }

    /** Package-visible so both platform branches are testable. */
    static KeyCombination tabChord(KeyCode digit, boolean mac) {
        return mac
                ? new KeyCodeCombination(digit, KeyCombination.SHORTCUT_DOWN)
                : new KeyCodeCombination(digit, KeyCombination.ALT_DOWN);
    }

    /** A chord that already includes Shift on every platform (tab navigation, for instance). */
    public static KeyCombination shiftChord(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    }

    public static MenuAction of(String label, KeyCombination accelerator, Runnable action) {
        return new MenuAction(label, accelerator, action);
    }

    public static MenuAction of(String label, Runnable action) {
        return new MenuAction(label, null, action);
    }

    /** Builds the menu item. The accelerator is shown for discoverability; the filter runs it. */
    public MenuItem toMenuItem() {
        MenuItem item = new MenuItem(label);
        if (accelerator != null) item.setAccelerator(accelerator);
        item.setOnAction(e -> action.run());
        return item;
    }

    public boolean matches(javafx.scene.input.KeyEvent event) {
        return accelerator != null && accelerator.match(event);
    }
}
