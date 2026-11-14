package com.termina.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/**
 * The one definition of how the command palette is driven from the keyboard, and of the legend that
 * tells the user so.
 *
 * <p>The arrows, PageUp/PageDown, Enter and Escape always work. A row and a page also move on the
 * chords the bindings in force give the four commands {@code nav.lineDown}, {@code nav.lineUp},
 * {@code nav.pageDown} and {@code nav.pageUp} (PAL-06, Pauta 6.0.1), looked up and not written
 * here: by default Emacs's own, {@code C-n}/{@code C-p} and {@code C-v}/{@code M-v}, which is what
 * the hands of anyone who reaches for {@code M-x} already do, and whatever Settings ▸ Keyboard has
 * moved them to. {@code M-<}/{@code M->} for the ends and {@code C-g} to cancel stay as they are:
 * Termina's key table has no command for them to follow. All of it is safe to take here: while the
 * palette is open it owns the keyboard, so nothing is stolen from the shell.
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
    static final int DEFAULT_PAGE = 8;

    private static final String SEPARATOR = "  ·  ";

    /** The chords no command stands behind, in {@link KeyChord} tokens, and what each means here. */
    static final Map<String, Action> EMACS = Map.of(
            "M-S-,", Action.FIRST,
            "M-S-.", Action.LAST,
            "C-g", Action.CANCEL);

    /** The four commands whose chords move the selection, and what each does to it. */
    static final Map<String, Action> MOVES = movesById();

    private static Map<String, Action> movesById() {
        Map<String, Action> moves = new java.util.LinkedHashMap<>();
        moves.put("nav.lineDown", Action.DOWN);
        moves.put("nav.lineUp", Action.UP);
        moves.put("nav.pageDown", Action.PAGE_DOWN);
        moves.put("nav.pageUp", Action.PAGE_UP);
        return java.util.Collections.unmodifiableMap(moves);
    }

    /**
     * The movement chords in force, token to action: for each of {@link #MOVES}, the token of the
     * chord the bindings give it. A command on no key gives none, and its arrow or page key still
     * moves.
     *
     * @param tokenOf the {@link KeyChord} token of the chord a command id is on now, or null
     */
    static Map<String, Action> moves(java.util.function.Function<String, @Nullable String> tokenOf) {
        Map<String, Action> out = new java.util.LinkedHashMap<>();
        MOVES.forEach((id, action) -> {
            String token = tokenOf.apply(id);
            if (token != null) out.putIfAbsent(token, action);
        });
        return out;
    }

    private PaletteKeys() {}

    /**
     * The action for a key, by {@link KeyChord} token ({@link PaletteNav#action} reads it off the
     * event): the fixed keys first, then the chords in force for a row and a page ({@code moves},
     * from {@link #moves}), then the ends and cancel. Plain
     * Home/End move the query field's caret when there is one (as in every search box); Ctrl/Cmd+Home
     * and Ctrl/Cmd+End always jump to the first/last row.
     */
    static Action resolve(@Nullable String token, boolean queryField, Map<String, Action> moves) {
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
                Action moved = moves.get(token);
                return moved != null ? moved : EMACS.getOrDefault(token, Action.NONE);
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

    /**
     * The row a navigation action lands on, among rows {@code selectable} accepts (so the cursor
     * steps over greyed-out rows), or -1 when none will do. Up/Down wrap; a page or first/last jump
     * lands on the nearest selectable row at or beyond its target, else the nearest before it.
     * {@link PaletteNav#navigate} applies it to the list. Pure.
     */
    static <T> int landing(List<T> items, int current, Action action, int page, Predicate<? super T> selectable) {
        int size = items.size();
        int idx = -1;
        if (action == Action.UP || action == Action.DOWN) {
            int dir = action == Action.DOWN ? 1 : -1;
            int start = current >= 0 ? current : dir > 0 ? -1 : size;
            for (int step = 1; step <= size && idx < 0; step++) {
                int i = Math.floorMod(start + dir * step, size);
                idx = selectable.test(items.get(i)) ? i : -1;
            }
        } else if (size > 0) {
            int target = target(action, current, size, page);
            int dir = action == Action.PAGE_DOWN || action == Action.FIRST ? 1 : -1;
            idx = seek(items, target, dir, selectable);
            if (idx < 0) idx = seek(items, target, -dir, selectable);
        }
        return idx;
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
     * The palette legend: "move", the palette's own entries (nulls skipped), "cancel". The chords
     * in force for a row down and a row up are shown beside the arrows, in the platform's notation,
     * which is what the menus use; with neither on a key, the arrows alone.
     *
     * @param moves the movement chords in force ({@link #moves})
     */
    static String legend(Map<String, Action> moves, @Nullable String... entries) {
        List<String> parts = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (Action line : List.of(Action.DOWN, Action.UP)) {
            moves.forEach((token, action) -> {
                if (action == line) lines.add(KeyChord.display(token));
            });
        }
        parts.add(hint("move", lines.isEmpty() ? "↑↓" : "↑↓ / " + String.join(" ", lines)));
        for (String entry : entries) {
            if (entry != null) parts.add(entry);
        }
        parts.add(hint("cancel", "Esc / " + KeyChord.display("C-g")));
        return String.join(SEPARATOR, parts);
    }
}
