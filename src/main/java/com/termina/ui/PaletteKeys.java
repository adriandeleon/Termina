package com.termina.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javafx.scene.control.ListView;
import javafx.scene.input.KeyEvent;

import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/**
 * The one definition of how the command palette is driven from the keyboard, and of the legend that
 * tells the user so.
 *
 * <p>The arrows, PageUp/PageDown, Enter and Escape always work. On top of those the palette answers
 * to Emacs's own movement — {@code C-n}/{@code C-p} a row, {@code C-v}/{@code M-v} a page,
 * {@code M-<}/{@code M->} the ends, {@code C-g} to cancel — which is what the hands of anyone who
 * reaches for {@code M-x} already do. They are safe to take here: while the palette is open it owns
 * the keyboard, so nothing is stolen from the shell. The table is Editora's Emacs keymap, token for
 * token, so the two palettes move alike.
 *
 * <p>The legend is built from the same table, so it can only name keys that reach the palette.
 */
final class PaletteKeys {

    /** What a key press means to the palette. */
    enum Action {
        NONE,
        UP,
        DOWN,
        PAGE_UP,
        PAGE_DOWN,
        FIRST,
        LAST,
        ACCEPT,
        CANCEL
    }

    /** Rows moved by PageUp/PageDown when the list cannot say how many it shows. */
    private static final int DEFAULT_PAGE = 8;

    private static final String SEPARATOR = "  ·  ";

    /** The Emacs movement chords, in {@link KeyChord} tokens, and what each means here. */
    static final Map<String, Action> EMACS = Map.of(
            "C-n", Action.DOWN,
            "C-p", Action.UP,
            "C-v", Action.PAGE_DOWN,
            "M-v", Action.PAGE_UP,
            "M-S-,", Action.FIRST,
            "M-S-.", Action.LAST,
            "C-g", Action.CANCEL);

    private PaletteKeys() {}

    /**
     * The action for a key press. {@code queryField} says whether a text field has the focus: then
     * plain Home/End stay with its caret.
     */
    static Action action(KeyEvent e, boolean queryField) {
        return resolve(KeyChord.of(e), queryField);
    }

    /**
     * The pure decision behind {@link #action}: the fixed keys first, then the Emacs table. Plain
     * Home/End move the query field's caret when there is one (as in every search box); Ctrl/Cmd+Home
     * and Ctrl/Cmd+End always jump to the first/last row.
     */
    static Action resolve(@Nullable String token, boolean queryField) {
        if (token == null) return Action.NONE;
        switch (token) {
            case "down":
                return Action.DOWN;
            case "up":
                return Action.UP;
            case "pagedown":
                return Action.PAGE_DOWN;
            case "pageup":
                return Action.PAGE_UP;
            case "enter":
                return Action.ACCEPT;
            case "escape":
                return Action.CANCEL;
            case "C-home", "Cmd-home", "Cmd-up":
                return Action.FIRST;
            case "C-end", "Cmd-end", "Cmd-down":
                return Action.LAST;
            case "home":
                return queryField ? Action.NONE : Action.FIRST;
            case "end":
                return queryField ? Action.NONE : Action.LAST;
            default:
                return EMACS.getOrDefault(token, Action.NONE);
        }
    }

    /**
     * The row a navigation action lands on, or -1 when {@code action} is not navigation or the list
     * is empty. Up/Down wrap around; the page and first/last jumps stop at the ends. {@code current}
     * may be -1 (nothing selected). Pure.
     */
    static int target(Action action, int current, int size, int page) {
        if (size <= 0) return -1;
        int step = Math.max(1, page);
        return switch (action) {
            case DOWN -> current < 0 ? 0 : Math.floorMod(current + 1, size);
            case UP -> current < 0 ? size - 1 : Math.floorMod(current - 1, size);
            case PAGE_DOWN -> Math.min(size - 1, Math.max(0, current) + step);
            case PAGE_UP -> Math.max(0, Math.max(0, current) - step);
            case FIRST -> 0;
            case LAST -> size - 1;
            default -> -1;
        };
    }

    /** How many rows a page moves: one less than the list shows, so a row of context remains. */
    static int page(ListView<?> list) {
        double cell = list.getFixedCellSize();
        if (cell > 0 && list.getHeight() > cell) return Math.max(1, (int) (list.getHeight() / cell) - 1);
        return DEFAULT_PAGE;
    }

    /**
     * Applies a navigation action to {@code list}, landing only on rows {@code selectable} accepts
     * (so the cursor steps over greyed-out rows). Up/Down wrap; a page or first/last jump lands on
     * the nearest selectable row at or beyond its target, else the nearest before it. Returns false —
     * leaving the event for someone else — only when {@code action} is not navigation.
     */
    static <T> boolean navigate(ListView<T> list, Action action, Predicate<? super T> selectable) {
        if (!isNavigation(action)) return false;
        List<T> items = list.getItems();
        int size = items.size();
        int current = list.getSelectionModel().getSelectedIndex();
        int idx = -1;
        if (action == Action.UP || action == Action.DOWN) {
            int dir = action == Action.DOWN ? 1 : -1;
            int start = current >= 0 ? current : dir > 0 ? -1 : size;
            for (int step = 1; step <= size && idx < 0; step++) {
                int i = Math.floorMod(start + dir * step, size);
                idx = selectable.test(items.get(i)) ? i : -1;
            }
        } else if (size > 0) {
            int target = target(action, current, size, page(list));
            int dir = action == Action.PAGE_DOWN || action == Action.FIRST ? 1 : -1;
            idx = seek(items, target, dir, selectable);
            if (idx < 0) idx = seek(items, target, -dir, selectable);
        }
        if (idx >= 0) {
            list.getSelectionModel().select(idx);
            list.scrollTo(idx);
        }
        return true;
    }

    private static <T> int seek(List<T> items, int from, int dir, Predicate<? super T> selectable) {
        for (int i = from; i >= 0 && i < items.size(); i += dir) {
            if (selectable.test(items.get(i))) return i;
        }
        return -1;
    }

    static boolean isNavigation(Action action) {
        return switch (action) {
            case UP, DOWN, PAGE_UP, PAGE_DOWN, FIRST, LAST -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- legend

    /**
     * One legend entry: the localised {@code pickerKeys.<what>} pattern filled with the keys that
     * trigger it, or null when there are none (the entry is then left out of the legend).
     */
    static @Nullable String hint(String what, String keys) {
        return keys == null || keys.isBlank() ? null : tr("pickerKeys." + what, keys);
    }

    /**
     * The palette legend: "move", the palette's own entries (nulls skipped), "cancel". The Emacs
     * chords are shown beside the fixed keys in the platform's notation, which is what the menus use.
     */
    static String legend(@Nullable String... entries) {
        List<String> parts = new ArrayList<>();
        parts.add(hint("move", "↑↓ / " + KeyChord.display("C-n") + " " + KeyChord.display("C-p")));
        for (String entry : entries) {
            if (entry != null) parts.add(entry);
        }
        parts.add(hint("cancel", "Esc / " + KeyChord.display("C-g")));
        return String.join(SEPARATOR, parts);
    }
}
