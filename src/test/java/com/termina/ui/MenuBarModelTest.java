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

    private static final boolean[] ZOOM_ROWS = {true, false};

    /** ABOUT-01, PAL-01, DOCS-06, MLOG-02 (Pauta 6.0.1): the ids those rules name, as spelled. */
    @Test
    void theCommandsPautaNamesHaveItsIds() {
        List<String> ids = MenuBarModel.commandIds(MenuBarModel.menus(true));
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
        List<String> help = MenuBarModel.menus(true).stream()
                .filter(menu -> menu.titleKey().equals("menu.help"))
                .findFirst()
                .orElseThrow()
                .rows();
        assertTrue(help.containsAll(List.of("help.about", "help.documentation", "view.messageLog")), help.toString());
    }

    @Test
    void everyCommandIsInTheBarOnceAndNothingElseIs() {
        for (boolean zoomRow : ZOOM_ROWS) {
            List<String> ids = MenuBarModel.commandIds(MenuBarModel.menus(zoomRow));
            Set<String> seen = new HashSet<>();
            for (String id : ids) {
                assertTrue(seen.add(id), id + " twice");
                assertTrue(MenuBarModel.isCommand(id), id);
            }
            // The zoom commands are there either way: as rows, or behind the row's buttons.
            assertTrue(seen.containsAll(MenuBarModel.ZOOM_COMMANDS));
            // OS-01 (Pauta 6.0.1): on every OS, disabled where there is no bar to hide.
            assertTrue(seen.contains("hideMenuBar"));
        }
    }

    @Test
    void everyCommandHasATitleAndADescriptionInTheCatalogue() throws Exception {
        Properties english = new Properties();
        try (InputStream in = MenuBarModelTest.class.getResourceAsStream("/com/termina/i18n/messages.properties")) {
            english.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> missing = new ArrayList<>();
        for (String id : MenuBarModel.commandIds(MenuBarModel.menus(true))) {
            // KBD-03 (Pauta 6.0.1): command.<id> and command.<id>.desc, the numbered ones too.
            if (!english.containsKey("command." + id)) missing.add("command." + id);
            if (!english.containsKey("command." + id + ".desc")) missing.add("command." + id + ".desc");
        }
        for (MenuBarModel.Menu menu : MenuBarModel.menus(true)) {
            if (!english.containsKey(menu.titleKey())) missing.add(menu.titleKey());
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void rulesOnlyBetweenRows() {
        for (boolean zoomRow : ZOOM_ROWS) {
            for (MenuBarModel.Menu menu : MenuBarModel.menus(zoomRow)) {
                List<String> rows = menu.rows();
                assertFalse(rows.isEmpty(), menu.titleKey());
                assertFalse(rows.get(0).equals(MenuBarModel.SEPARATOR), menu.titleKey() + " starts with a rule");
                assertFalse(
                        rows.get(rows.size() - 1).equals(MenuBarModel.SEPARATOR), menu.titleKey() + " ends with one");
                for (int i = 1; i < rows.size(); i++) {
                    assertFalse(
                            rows.get(i).equals(MenuBarModel.SEPARATOR)
                                    && rows.get(i - 1).equals(MenuBarModel.SEPARATOR),
                            menu.titleKey() + " has two rules together");
                }
            }
        }
    }

    @Test
    void theZoomRowStandsForItsCommandsOrTheyAreRows() {
        List<String> withRow = MenuBarModel.menus(true).get(2).rows();
        assertEquals(MenuBarModel.ZOOM_ROW, withRow.get(0));
        for (String id : MenuBarModel.ZOOM_COMMANDS) assertFalse(withRow.contains(id), id);
        List<String> without = MenuBarModel.menus(false).get(2).rows();
        assertFalse(without.contains(MenuBarModel.ZOOM_ROW));
        assertTrue(without.containsAll(MenuBarModel.ZOOM_COMMANDS));
        assertEquals("fullScreen", without.get(without.indexOf(MenuBarModel.SEPARATOR) + 1), "on its own");
    }

    @Test
    void theNumberedChordsAreTheNineAndNothingElse() {
        assertTrue(MenuBarModel.isNumbered("goToTab1"));
        assertTrue(MenuBarModel.isNumbered("newTabProfile9"));
        assertFalse(MenuBarModel.isNumbered("newTabProfile"));
        assertFalse(MenuBarModel.isNumbered("goToTabs"));
        assertFalse(MenuBarModel.isNumbered("newTab"));
        assertFalse(MenuBarModel.isNumbered("goToTab10"));
        long numbered = MenuBarModel.commandIds(MenuBarModel.menus(true)).stream()
                .filter(MenuBarModel::isNumbered)
                .count();
        assertEquals(2L * WindowRules.TAB_CHORD_COUNT, numbered);
    }
}
