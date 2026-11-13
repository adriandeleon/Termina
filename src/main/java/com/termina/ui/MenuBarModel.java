package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu bar as command ids (MENU-01, Pauta 2.0.2): which menus there are, what each lists and in
 * what order. {@link TerminalWindow} assembles the bar from this and refreshes it from the {@link
 * CommandRegistry}; neither this nor the bar holds a rule about what a command does, is called or
 * whether it can run.
 *
 * <p>A row is a command id, {@link #SEPARATOR}, or a submenu that groups command ids (MENU-01, Pauta
 * 6.0.1): New Tab With Profile, whose rows are the nine numbered profile commands. Nothing in the
 * bar is built by hand with a title and a handler of its own. Each menu also names the commands
 * that belong to it without a row of their own — palette and chord only — so the keyboard settings
 * and the palette group them under it.
 *
 * <p>Pure: no JavaFX, so the shape is tested without a toolkit.
 */
final class MenuBarModel {

    /** A rule between two groups of rows. */
    static final String SEPARATOR = "-";

    /**
     * The New Tab With Profile submenu: its rows are {@link #submenuRows}, the numbered profile
     * commands, each shown under the name of the profile it opens and only while there is one.
     */
    static final String PROFILE_SUBMENU = "+newTabProfile";

    /** View's zoom commands, in the order of their rows. */
    static final List<String> ZOOM_COMMANDS = List.of("zoomIn", "zoomOut", "actualSize", "fullScreen");

    /** The command ids a submenu row groups, in order; empty for a row that is not a submenu. */
    static List<String> submenuRows(String row) {
        return row.equals(PROFILE_SUBMENU) ? numbered(WindowRules.PROFILE_CHORD_ID) : List.of();
    }

    /** The catalogue key of a submenu row's title. */
    static String submenuTitleKey(String row) {
        if (row.equals(PROFILE_SUBMENU)) return "menu.newTabProfile";
        throw new IllegalArgumentException(row + " is not a submenu");
    }

    /**
     * One menu.
     *
     * @param titleKey the catalogue key of its title
     * @param rows what it lists, in order
     * @param unlisted commands that belong to it with no row: palette and chord only
     */
    record Menu(String titleKey, List<String> rows, List<String> unlisted) {}

    private MenuBarModel() {}

    /** The bar: the same rows on every OS. */
    static List<Menu> menus() {
        List<String> fileUnlisted = new ArrayList<>(List.of("exportConfiguration", "importConfiguration"));
        // The Settings window's buttons (KBD-01): palette and chord only, beside Settings itself.
        for (SettingsActions.Action action : SettingsActions.Action.values()) fileUnlisted.add(action.id());
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
                        "configureProfiles",
                        // The way out that is not closing the last window (WIN-14), last.
                        SEPARATOR,
                        "app.quit"),
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
                        "find.show",
                        "findNext",
                        "findPrevious",
                        SEPARATOR,
                        "previousPrompt",
                        "nextPrompt",
                        "selectLastOutput"),
                // The link under the pointer: the right-click menu's two link rows, as commands
                // (KBD-01); and the find bar's switch, whose row is the switch itself (FIND-16).
                List.of("openLink", "copyLinkAddress", "findRegex"));

        // Each zoom command a row of its own, as every row of the bar is one command with its
        // icon, title and chord (MENU-01, MENU-02). The row of four buttons the bar once carried
        // is the right-click menu's alone. Full Screen last and on its own.
        List<String> viewRows = new ArrayList<>(ZOOM_COMMANDS.subList(0, 3));
        viewRows.add(SEPARATOR);
        viewRows.add("fullScreen");
        // On every OS (OS-01, Pauta 6.0.1): disabled, with its reason, where there is no bar in
        // the window to hide.
        viewRows.add(SEPARATOR);
        viewRows.add("hideMenuBar");
        viewRows.add("showTabBar");
        viewRows.add("showStatusBar");
        // The UI zoom's three commands: palette and chord only. View's zoom rows are the
        // terminal text's, which is the zoom a terminal's user reaches for.
        Menu view = new Menu("menu.view", viewRows, List.of("uiZoomIn", "uiZoomOut", "uiZoomReset", "focusMenuBar"));

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

        // Pauta's order (DOCS-07, Pauta 6.0.1), less what Termina does not have: the Command
        // Palette first, the reference, the update check, the two logs, then About last.
        Menu help = new Menu(
                "menu.help",
                List.of(
                        "palette.show",
                        "help.documentation",
                        "whatsNew",
                        "keyboardShortcuts",
                        SEPARATOR,
                        "checkForUpdates",
                        "view.messageLog",
                        "debugLog",
                        "reportProblem",
                        "help.doctor",
                        "help.pauta",
                        SEPARATOR,
                        "help.about"),
                // What the two log windows' buttons and right-click rows do (KBD-01): palette and
                // chord only, the windows themselves being where they are used.
                List.of(
                        "debugLog.find",
                        "debugLog.refresh",
                        "debugLog.copy",
                        "debugLog.export",
                        "debugLog.clear",
                        "messageLog.copy",
                        "messageLog.clear",
                        "report.copy",
                        "report.open",
                        "help.copyDetails",
                        "help.website",
                        "help.sourceCode",
                        "help.pautaRules",
                        "help.pautaDocs",
                        "help.copyPauta",
                        "help.copyDoctor"));

        return List.of(file, edit, view, window, help);
    }

    /** Every command id in the bar, rows and unlisted, in order; a submenu's rows in its place. */
    static List<String> commandIds(List<Menu> menus) {
        List<String> ids = new ArrayList<>();
        for (Menu menu : menus) {
            for (String row : menu.rows()) {
                if (isCommand(row)) ids.add(row);
                else ids.addAll(submenuRows(row));
            }
            ids.addAll(menu.unlisted());
        }
        return ids;
    }

    /** Whether a row runs a command, rather than being a rule or a submenu. */
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
