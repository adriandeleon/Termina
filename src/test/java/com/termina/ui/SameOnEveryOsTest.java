package com.termina.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.termina.Os;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The app as each OS (OS-03, Pauta 6.0.1): the test sets Windows, macOS and Linux in turn through
 * {@link Os#runAs} and compares the command ids, the menu bar's rows and the Settings pages. A
 * branch on the OS in any of the three is what it is there to catch.
 *
 * <p>Only lists built when asked are compared, and each is asked for once before any override:
 * a class that worked the OS out as it loaded would keep the override for the rest of the run.
 */
class SameOnEveryOsTest {

    /** What a person can find: every command, every row of the bar, every page of Settings. */
    private record Lists(
            List<String> commandIds, List<String> menuRows, List<String> settingsPages, List<String> boundCommands) {}

    @Test
    void theCommandsTheMenuRowsAndTheSettingsPagesAreTheSameOnAllThree() {
        Lists asThisOs = lists(Os.current()); // loads what the lists need, as the system this is
        Map<Os.Kind, Lists> seen = new EnumMap<>(Os.Kind.class);
        try {
            for (Os.Kind os : List.of(Os.Kind.WINDOWS, Os.Kind.MAC, Os.Kind.LINUX)) {
                Os.runAs(os);
                assertEquals(os, Os.current());
                seen.put(os, lists(os));
            }
        } finally {
            Os.runAs(null);
        }
        assertEquals(seen.get(Os.Kind.LINUX), seen.get(Os.Kind.WINDOWS));
        assertEquals(seen.get(Os.Kind.LINUX), seen.get(Os.Kind.MAC));
        assertEquals(asThisOs, seen.get(Os.Kind.LINUX), "and the same with no override");

        Lists any = seen.get(Os.Kind.LINUX);
        assertFalse(any.commandIds().isEmpty());
        assertFalse(any.menuRows().isEmpty());
        assertEquals(6, any.settingsPages().size(), any.settingsPages().toString());
        assertFalse(any.boundCommands().isEmpty());
    }

    private static Lists lists(Os.Kind os) {
        List<MenuBarModel.Menu> menus = MenuBarModel.menus();
        List<String> rows = new ArrayList<>();
        for (MenuBarModel.Menu menu : menus) {
            for (String row : menu.rows()) rows.add(menu.titleKey() + " > " + row);
        }
        // The keymap is the one thing chosen by OS, a file for each: the chords differ, the
        // commands that have one do not.
        List<String> bound =
                List.copyOf(new TreeSet<>(Keymap.load(os == Os.Kind.MAC).keySet()));
        return new Lists(MenuBarModel.commandIds(menus), rows, SettingsWindow.Category.keys(), bound);
    }
}
