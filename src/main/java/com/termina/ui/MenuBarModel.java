package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu bar as command ids (MENU-01, Pauta 2.0.2): which menus there are, what each lists and in
 * what order. {@link TerminalWindow} assembles the bar from this and refreshes it from the {@link
 * CommandRegistry}; neither this nor the bar holds a rule about what a command does, is called or
 * whether it can run.
 *
 * <p>A row is a command id, {@link #SEPARATOR}, or one of the two placeholders for something that
 * is not a command: the New Tab With Profile submenu, whose rows are the profiles there are when it
 * opens, and the zoom row, whose buttons are the four zoom commands. Each menu also names the
 * commands that belong to it without a row of their own — palette and chord only — so the keyboard
 * settings and the palette group them under it.
 *
 * <p>Pure: no JavaFX, so the shape is tested without a toolkit.
 */
final class MenuBarModel {

    /** A rule between two groups of rows. */
    static final String SEPARATOR = "-";

    /** The New Tab With Profile submenu: one row per profile, made when it opens. */
    static final String PROFILE_SUBMENU = "+newTabProfile";

    /** The zoom row: one row of buttons running {@link #ZOOM_COMMANDS}. */
    static final String ZOOM_ROW = "+zoomRow";

    /** The commands the zoom row's buttons run, which are View's rows where the row cannot be drawn. */
    static final List<String> ZOOM_COMMANDS = List.of("zoomIn", "zoomOut", "actualSize", "fullScreen");

    /**
     * One menu.
     *
     * @param titleKey the catalogue key of its title
     * @param rows what it lists, in order
     * @param unlisted commands that belong to it with no row: palette and chord only
     */
    record Menu(String titleKey, List<String> rows, List<String> unlisted) {}

    private MenuBarModel() {}

    /**
     * The bar.
     *
     * @param zoomRow whether View can carry the zoom row; where it cannot (a system menu bar draws
     *     no JavaFX node) the four zoom commands are rows instead
     */
    static List<Menu> menus(boolean zoomRow) {
        List<String> fileUnlisted = new ArrayList<>(numbered(WindowRules.PROFILE_CHORD_ID));
        fileUnlisted.addAll(List.of("exportConfiguration", "importConfiguration"));
        Menu file = new Menu(
                "menu.file",
                List.of(
                        "newTab",
                        PROFILE_SUBMENU,
                        "newWindow",
                        SEPARATOR,
                        "closeTab",
                        "closeOtherTabs",
                        "closeTabsToTheLeft",
                        "closeTabsToTheRight",
                        "closeWindow",
                        "reopenClosedTab",
                        SEPARATOR,
                        "settings",
                        "syncNow",
                        "configureProfiles"),
                fileUnlisted);

        Menu edit = new Menu(
                "menu.edit",
                List.of(
                        "copy",
                        "paste",
                        "selectAll",
                        SEPARATOR,
                        "clearScrollback",
                        SEPARATOR,
                        "find",
                        "findNext",
                        "findPrevious",
                        SEPARATOR,
                        "previousPrompt",
                        "nextPrompt",
                        "selectLastOutput"),
                // The link under the pointer: the right-click menu's two link rows, as commands
                // (KBD-01); and the find bar's switch, whose row is the switch itself (FIND-16).
                List.of("openLink", "copyLinkAddress", "findRegex"));

        List<String> viewRows = new ArrayList<>();
        if (zoomRow) {
            viewRows.add(ZOOM_ROW);
        } else {
            // Full Screen last and on its own, which is where macOS's own View menus put it.
            viewRows.addAll(ZOOM_COMMANDS.subList(0, 3));
            viewRows.add(SEPARATOR);
            viewRows.add("fullScreen");
        }
        // On every OS (OS-01, Pauta 6.0.1): disabled, with its reason, where there is no bar in
        // the window to hide.
        viewRows.add(SEPARATOR);
        viewRows.add("hideMenuBar");
        viewRows.add("showStatusBar");
        viewRows.add(SEPARATOR);
        viewRows.add("commandPalette");
        Menu view = new Menu("menu.view", viewRows, List.of());

        List<String> windowUnlisted = new ArrayList<>();
        for (String direction : List.of("Left", "Right", "Up", "Down")) windowUnlisted.add("focusPane" + direction);
        windowUnlisted.addAll(numbered(WindowRules.TAB_CHORD_ID));
        windowUnlisted.add("tabMenu");
        Menu window = new Menu(
                "menu.window",
                List.of(
                        "nextTab",
                        "previousTab",
                        SEPARATOR,
                        "moveTabLeft",
                        "moveTabRight",
                        SEPARATOR,
                        "renameTab",
                        "duplicateTab",
                        "pinTab",
                        SEPARATOR,
                        "splitRight",
                        "splitDown",
                        "maximizePane",
                        "movePaneToNewTab"),
                windowUnlisted);

        // Pauta's order (DOCS-07), less what Termina does not have yet: the reference, the update
        // check, the two logs, then About last.
        Menu help = new Menu(
                "menu.help",
                List.of(
                        "documentation",
                        "whatsNew",
                        "keyboardShortcuts",
                        SEPARATOR,
                        "checkForUpdates",
                        "messageLog",
                        "debugLog",
                        "reportProblem",
                        SEPARATOR,
                        "about"),
                List.of());

        return List.of(file, edit, view, window, help);
    }

    /** Every command id in the bar, rows and unlisted, in order; placeholders expanded. */
    static List<String> commandIds(List<Menu> menus) {
        List<String> ids = new ArrayList<>();
        for (Menu menu : menus) {
            for (String row : menu.rows()) {
                if (row.equals(ZOOM_ROW)) ids.addAll(ZOOM_COMMANDS);
                else if (isCommand(row)) ids.add(row);
            }
            ids.addAll(menu.unlisted());
        }
        return ids;
    }

    /** Whether a row runs a command, rather than being a rule or a placeholder. */
    static boolean isCommand(String row) {
        return !row.equals(SEPARATOR) && !row.startsWith("+");
    }

    /**
     * The numbered chords' ids, 1 to 9: commands the palette lists by what they reach (a tab's
     * title, a profile's name) rather than by number, so they are not palette rows of their own.
     */
    static boolean isNumbered(String id) {
        for (String prefix : List.of(WindowRules.PROFILE_CHORD_ID, WindowRules.TAB_CHORD_ID)) {
            if (id.startsWith(prefix)
                    && id.length() == prefix.length() + 1
                    && Character.isDigit(id.charAt(prefix.length()))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> numbered(String prefix) {
        List<String> ids = new ArrayList<>();
        for (int number = 1; number <= WindowRules.TAB_CHORD_COUNT; number++) ids.add(prefix + number);
        return ids;
    }
}
