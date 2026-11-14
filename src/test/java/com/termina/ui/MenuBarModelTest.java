package com.termina.ui;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The menu bar's shape as ids (MENU-01), on every combination the platforms produce. */
class MenuBarModelTest {

    /** ABOUT-01, PAL-01, DOCS-06, MLOG-02 (Pauta 6.0.1): the ids those rules name, as spelled. */
    @Test
    void theCommandsPautaNamesHaveItsIds() {
        List<String> ids = MenuBarModel.commandIds(MenuBarModel.menus());
        assertTrue(ids.containsAll(
                List.of("help.about", "palette.show", "help.documentation", "view.messageLog", "find.show")));
        // FIND-04: every other find bar has a command of its own, and KBD-01: so do the logs' buttons.
        assertTrue(ids.containsAll(List.of(
                "debugLog.find",
                "debugLog.refresh",
                "debugLog.copy",
                "debugLog.export",
                "debugLog.clear",
                "messageLog.copy",
                "messageLog.clear")));
        // And the buttons of Settings, Report a Problem and About, and About's links.
        for (SettingsActions.Action action : SettingsActions.Action.values()) {
            assertTrue(ids.contains(action.id()), action.id());
        }
        assertTrue(ids.containsAll(List.of(
                "report.copy",
                "report.open",
                "help.copyDetails",
                "help.website",
                "help.sourceCode",
                "help.pautaRules")));
        for (String old : List.of("about", "commandPalette", "documentation", "messageLog", "find")) {
            assertFalse(ids.contains(old), old);
        }
        // About, Documentation and the Message Log are rows of the Help menu.
        List<String> help = MenuBarModel.menus().stream()
                .filter(menu -> menu.titleKey().equals("menu.help"))
                .findFirst()
                .orElseThrow()
                .rows();
        assertTrue(help.containsAll(List.of("help.about", "help.documentation", "view.messageLog")), help.toString());
    }

    @Test
    void theHelpMenuRunsInPautasOrder() {
        // DOCS-07 (Pauta 6.0.1): Command Palette, Documentation, the keyboard reference, Check for
        // Updates, Message Log, Debug Log, Report a Problem, Pauta Conformance, About. Termina has
        // no Welcome page, whose row drops; What's New is its own, beside the documentation.
        List<String> help = MenuBarModel.menus().stream()
                .filter(menu -> menu.titleKey().equals("menu.help"))
                .findFirst()
                .orElseThrow()
                .rows()
                .stream()
                .filter(MenuBarModel::isCommand)
                .toList();
        assertEquals(
                List.of(
                        "palette.show",
                        "help.documentation",
                        "help.whatsNew",
                        "help.keyboardShortcuts",
                        "help.checkForUpdates",
                        "view.messageLog",
                        "help.debugLog",
                        "help.reportProblem",
                        "help.doctor",
                        "help.pauta",
                        "help.about"),
                help);
        // And it is a row of that menu alone.
        List<String> view = MenuBarModel.menus().get(2).rows();
        assertFalse(view.contains("palette.show"));
        assertFalse(view.getLast().equals(MenuBarModel.SEPARATOR));
    }

    @Test
    void onlyWhatDiscardsIsDrawnAsDangerous() {
        // MENU-12 (Pauta 6.0.1): the scrollback does not come back; a closed tab does.
        assertTrue(WindowRules.isDestructive("edit.clearScrollback"));
        for (String id : List.of("file.closeTab", "file.closeOtherTabs", "file.closeWindow", "app.quit", "edit.copy")) {
            assertFalse(WindowRules.isDestructive(id), id);
        }
        assertFalse(WindowRules.isDestructive(null));
    }

    @Test
    void everyCommandIsInTheBarOnceAndNothingElseIs() {
        List<String> ids = MenuBarModel.commandIds(MenuBarModel.menus());
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            assertTrue(seen.add(id), id + " twice");
            assertTrue(MenuBarModel.isCommand(id), id);
        }
        assertTrue(seen.containsAll(MenuBarModel.ZOOM_COMMANDS));
        // OS-01 (Pauta 6.0.1): on every OS, disabled where there is no bar to hide.
        assertTrue(seen.contains("view.toggleMenuBar"));
    }

    @Test
    void everyRowIsACommandARuleOrASubmenuOfCommands() {
        // MENU-01 (Pauta 6.0.1): the model lists command ids only, beside the rules and the
        // submenus that group them. A row built by hand, with a title and a handler of its own,
        // would be a row that is none of the three.
        int submenus = 0;
        for (MenuBarModel.Menu menu : MenuBarModel.menus()) {
            for (String row : menu.rows()) {
                if (row.equals(MenuBarModel.SEPARATOR) || MenuBarModel.isCommand(row)) continue;
                submenus++;
                List<String> grouped = MenuBarModel.submenuRows(row);
                assertFalse(grouped.isEmpty(), row + " groups nothing");
                for (String id : grouped) assertTrue(MenuBarModel.isCommand(id), id);
                assertFalse(MenuBarModel.submenuTitleKey(row).isBlank());
            }
        }
        assertEquals(1, submenus, "New Tab With Profile");
        assertEquals(
                List.of("file.newTabProfile1", "file.newTabProfile9"),
                List.of(
                        MenuBarModel.submenuRows(MenuBarModel.PROFILE_SUBMENU).getFirst(),
                        MenuBarModel.submenuRows(MenuBarModel.PROFILE_SUBMENU).getLast()));
        assertEquals(List.of(), MenuBarModel.submenuRows("file.newTab"));
    }

    @Test
    void everyCommandHasATitleAndADescriptionInTheCatalogue() throws Exception {
        Properties english = new Properties();
        try (InputStream in = MenuBarModelTest.class.getResourceAsStream("/com/termina/i18n/messages.properties")) {
            english.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> missing = new ArrayList<>();
        for (String id : MenuBarModel.commandIds(MenuBarModel.menus())) {
            // KBD-03 (Pauta 6.0.1): command.<id> and command.<id>.desc, the numbered ones too.
            if (!english.containsKey("command." + id)) missing.add("command." + id);
            if (!english.containsKey("command." + id + ".desc")) missing.add("command." + id + ".desc");
        }
        for (MenuBarModel.Menu menu : MenuBarModel.menus()) {
            if (!english.containsKey(menu.titleKey())) missing.add(menu.titleKey());
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void rulesOnlyBetweenRows() {
        for (MenuBarModel.Menu menu : MenuBarModel.menus()) {
            List<String> rows = menu.rows();
            assertFalse(rows.isEmpty(), menu.titleKey());
            assertFalse(rows.get(0).equals(MenuBarModel.SEPARATOR), menu.titleKey() + " starts with a rule");
            assertFalse(rows.get(rows.size() - 1).equals(MenuBarModel.SEPARATOR), menu.titleKey() + " ends with one");
            for (int i = 1; i < rows.size(); i++) {
                assertFalse(
                        rows.get(i).equals(MenuBarModel.SEPARATOR)
                                && rows.get(i - 1).equals(MenuBarModel.SEPARATOR),
                        menu.titleKey() + " has two rules together");
            }
        }
    }

    @Test
    void eachZoomCommandIsARowOfItsOwn() {
        // MENU-01, MENU-02 (Pauta 6.0.1): no row of four buttons in the bar; Full Screen on its own.
        List<String> view = MenuBarModel.menus().get(2).rows();
        assertEquals(
                List.of("view.zoomIn", "view.zoomOut", "view.actualSize", MenuBarModel.SEPARATOR, "view.fullScreen"),
                view.subList(0, 5));
        for (String row : view) assertFalse(row.startsWith("+"), row);
    }

    @Test
    void theNumberedChordsAreTheNineAndNothingElse() {
        assertTrue(MenuBarModel.isNumbered("tab.goTo1"));
        assertTrue(MenuBarModel.isNumbered("file.newTabProfile9"));
        assertFalse(MenuBarModel.isNumbered("file.newTabProfile"));
        assertFalse(MenuBarModel.isNumbered("goToTabs"));
        assertFalse(MenuBarModel.isNumbered("file.newTab"));
        assertFalse(MenuBarModel.isNumbered("goToTab10"));
        long numbered = MenuBarModel.commandIds(MenuBarModel.menus()).stream()
                .filter(MenuBarModel::isNumbered)
                .count();
        assertEquals(2L * WindowRules.TAB_CHORD_COUNT, numbered);
    }
}
