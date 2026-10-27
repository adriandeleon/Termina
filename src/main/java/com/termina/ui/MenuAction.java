package com.termina.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.jspecify.annotations.Nullable;

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
 * @param description one line saying what the action does, shown under the palette's list for the
 *     highlighted row; empty when there is nothing a label did not already say
 * @param keywords words the palette also finds the action by, besides its label — the menu it lives
 *     in, so "file new" reaches New Tab; matched only when the label itself does not match, and
 *     ranked below every label match. Never shown.
 * @param id what the user's key bindings call this action — the catalogue key without its {@code
 *     menu.} prefix ({@code newTab}), stored as {@code keys.newTab}. Null for an action that cannot
 *     be rebound: the palette's per-tab and per-theme rows, which are built fresh on every open.
 *     Never translated, because a binding written in one language has to survive a switch to
 *     another.
 */
public record MenuAction(
        String label,
        @Nullable KeyCombination accelerator,
        Runnable action,
        String description,
        String keywords,
        @Nullable String id) {

    public MenuAction {
        if (description == null) description = "";
        if (keywords == null) keywords = "";
    }

    /** The chord that opens the command palette unless the user has chosen another: Emacs's M-x. */
    public static final KeyCombination DEFAULT_PALETTE_CHORD =
            new KeyCodeCombination(KeyCode.X, KeyCombination.ALT_DOWN);

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

    /**
     * The chord for closing a window.
     *
     * <p>Not {@link #shiftChord}{@code (W)}: off macOS that is {@code Ctrl+Shift+W}, which is also
     * what {@link #appChord}{@code (W)} gives Close Tab, and the filter runs bindings in order — so
     * Close Window's chord was dead while two menus and the palette advertised it. macOS keeps
     * {@code Cmd+Shift+W}, its own convention; everywhere else it is {@code Ctrl+Shift+Q}, which is
     * GNOME Terminal's and Konsole's.
     */
    public static KeyCombination closeWindowChord() {
        return closeWindowChord(MAC);
    }

    /** Package-visible so both platform branches are testable. */
    static KeyCombination closeWindowChord(boolean mac) {
        return mac
                ? new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                : new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    }

    /**
     * The accelerators that appear more than once in a binding table.
     *
     * <p>Two bindings on one chord is not an error JavaFX reports: the filter runs the first and
     * the second is simply never reached, while its menu item goes on advertising it. Asked at
     * registration so a dev run fails loudly, and by the tests over both platform branches.
     */
    static List<KeyCombination> duplicateAccelerators(List<KeyCombination> accelerators) {
        Set<KeyCombination> seen = new HashSet<>();
        List<KeyCombination> duplicates = new ArrayList<>();
        for (KeyCombination accelerator : accelerators) {
            if (accelerator == null) continue;
            if (!seen.add(accelerator) && !duplicates.contains(accelerator)) duplicates.add(accelerator);
        }
        return duplicates;
    }

    /** A chord that already includes Shift on every platform (tab navigation, for instance). */
    public static KeyCombination shiftChord(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    }

    public static MenuAction of(String label, @Nullable KeyCombination accelerator, Runnable action) {
        return new MenuAction(label, accelerator, action, "", "", null);
    }

    public static MenuAction of(String label, Runnable action) {
        return new MenuAction(label, null, action, "", "", null);
    }

    /** The same action with a one-line description for the palette. */
    public MenuAction describedAs(String description) {
        return new MenuAction(label, accelerator, action, description, keywords, id);
    }

    /** The same action, findable in the palette by {@code keywords} as well as by its label. */
    public MenuAction withKeywords(String keywords) {
        return new MenuAction(label, accelerator, action, description, keywords, id);
    }

    /** The same action on another chord: the one the user's key bindings give it. */
    public MenuAction withAccelerator(@Nullable KeyCombination accelerator) {
        return new MenuAction(label, accelerator, action, description, keywords, id);
    }

    /** The id of an action that has one, as every registered binding does. */
    String requireId() {
        if (id == null) throw new IllegalStateException("a binding needs an id: " + label);
        return id;
    }

    /** The same action, rebindable under {@code id}. */
    public MenuAction withId(String id) {
        return new MenuAction(label, accelerator, action, description, keywords, id);
    }

    /**
     * Whether this is the same action as {@code other}, whatever it is labelled or bound to. The
     * palette relabels the menu-bar toggle and the window rebinds the palette; both need to find the
     * original in a list.
     */
    public boolean sameActionAs(MenuAction other) {
        return other != null && other.action == action;
    }

    /**
     * The chord a stored setting names, or {@code fallback} when it names none.
     *
     * <p>The settings file is meant to be hand-edited, and {@link KeyCombination#valueOf} throws on
     * anything it cannot read. A chord that does not parse is treated like one that was never set
     * rather than like an error, because the alternative is a palette with no way in.
     */
    public static @Nullable KeyCombination parseChord(@Nullable String text, @Nullable KeyCombination fallback) {
        if (text == null || text.isBlank()) return fallback;
        try {
            return KeyCombination.valueOf(text.trim());
        } catch (IllegalArgumentException unreadable) {
            return fallback;
        }
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
