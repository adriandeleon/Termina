package com.termina.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The shortcut reference's rows: which to show for a query, and how they group.
 *
 * <p>The rows themselves come from the window — its menus and its binding table, so the list
 * cannot say a chord the filter does not implement — plus the handful of keys the terminal view
 * handles on its own. This class only decides what to do with them, and is pure for the usual
 * reason.
 */
final class ShortcutReference {

    private ShortcutReference() {}

    /**
     * @param group the menu it lives under, or the terminal group for view-level keys
     * @param label what it does, as the menu says it
     * @param keys the chord as the platform displays it, or a gesture ("Middle-click")
     */
    record Shortcut(String group, String label, String keys) {}

    /** Case-insensitive substring over everything a row shows; blank keeps every row. */
    static List<Shortcut> matching(List<Shortcut> all, String query) {
        if (query == null || query.isBlank()) return all;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Shortcut> hits = new ArrayList<>();
        for (Shortcut shortcut : all) {
            if (shortcut.label().toLowerCase(Locale.ROOT).contains(needle)
                    || shortcut.keys().toLowerCase(Locale.ROOT).contains(needle)
                    || shortcut.group().toLowerCase(Locale.ROOT).contains(needle)) {
                hits.add(shortcut);
            }
        }
        return hits;
    }

    /**
     * Rows by group, groups in the order they were first seen — menu order, since the menus are
     * walked first — and rows in their own order within each.
     */
    static LinkedHashMap<String, List<Shortcut>> grouped(List<Shortcut> shortcuts) {
        LinkedHashMap<String, List<Shortcut>> groups = new LinkedHashMap<>();
        for (Shortcut shortcut : shortcuts) {
            groups.computeIfAbsent(shortcut.group(), g -> new ArrayList<>()).add(shortcut);
        }
        return groups;
    }
}
