package com.termina.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The window's modes (WIN-10, MENU-04, Pauta 6.0.1): one truth table, and Simple mode's shorter bar. */
class ChromeTest {

    private static final Chrome.Modes NONE = Chrome.Modes.NONE;
    private static final Chrome.Modes ZEN = new Chrome.Modes(true, false);
    private static final Chrome.Modes EXPERT = new Chrome.Modes(false, true);

    @Test
    void zenShowsNoChromeAndExpertKeepsOnlyTheStatusBar() {
        // With every setting on: what each mode leaves.
        assertEquals("menu tabs status", shown(NONE, true, true, true));
        assertEquals("", shown(ZEN, true, true, true));
        assertEquals("status", shown(EXPERT, true, true, true));
    }

    @Test
    void aModeNeverShowsWhatTheSettingsHide() {
        // A mode only takes away: the status bar switched off in Settings stays off in Expert.
        for (Chrome.Modes modes : List.of(NONE, ZEN, EXPERT)) {
            assertEquals("", shown(modes, false, false, false), modes.toString());
        }
        assertEquals("tabs", shown(NONE, false, true, false));
        assertEquals("", shown(EXPERT, false, true, false));
    }

    private static String shown(Chrome.Modes modes, boolean menu, boolean tabs, boolean status) {
        StringBuilder out = new StringBuilder();
        if (Chrome.menuBar(menu, modes)) out.append("menu ");
        if (Chrome.tabBar(tabs, modes)) out.append("tabs ");
        if (Chrome.statusBar(status, modes)) out.append("status");
        return out.toString().strip();
    }

    @Test
    void zenAndExpertAreOneAtATime() {
        assertFalse(NONE.focus());
        assertTrue(ZEN.focus());
        assertTrue(EXPERT.focus());
        assertEquals(ZEN, NONE.toggleZen());
        assertEquals(NONE, ZEN.toggleZen());
        assertEquals(EXPERT, NONE.toggleExpert());
        assertEquals(NONE, EXPERT.toggleExpert());
        // Entering one leaves the other.
        assertEquals(ZEN, EXPERT.toggleZen());
        assertEquals(EXPERT, ZEN.toggleExpert());
        assertThrows(IllegalArgumentException.class, () -> new Chrome.Modes(true, true));
    }

    @Test
    void simpleModesBarIsAShorterCutOfTheFullOne() {
        List<MenuBarModel.Menu> full = MenuBarModel.menus(false);
        List<MenuBarModel.Menu> simple = MenuBarModel.menus(true);
        assertEquals(full, MenuBarModel.menus(), "the full bar is the one every other caller gets");
        assertEquals(5, full.size());
        assertEquals(4, simple.size(), "fewer menus");

        // Every row of the simple bar is a row of the full bar's menu of the same title.
        for (MenuBarModel.Menu menu : simple) {
            MenuBarModel.Menu same = full.stream()
                    .filter(m -> m.titleKey().equals(menu.titleKey()))
                    .findFirst()
                    .orElseThrow();
            Set<String> rows = new HashSet<>(same.rows());
            for (String row : menu.rows()) {
                assertTrue(rows.contains(row), row + " is not in the full " + menu.titleKey());
                assertTrue(row.equals(MenuBarModel.SEPARATOR) || MenuBarModel.isCommand(row), row);
            }
            assertTrue(menu.rows().size() < same.rows().size(), menu.titleKey() + " is shorter");
            assertEquals(List.of(), menu.unlisted(), "a command with no row belongs to the full bar's menu");
            assertFalse(menu.rows().getFirst().equals(MenuBarModel.SEPARATOR));
            assertFalse(menu.rows().getLast().equals(MenuBarModel.SEPARATOR));
        }
        List<String> ids = MenuBarModel.commandIds(simple);
        assertEquals(ids.size(), new HashSet<>(ids).size(), "no command twice");
        // The way back to the full bar, the palette, Settings and the way out are all there.
        assertTrue(ids.containsAll(List.of("view.toggleSimpleMode", "palette.show", "settings.show", "app.quit")));
        // The full bar's View menu has the three modes.
        assertTrue(MenuBarModel.commandIds(full)
                .containsAll(List.of("view.toggleSimpleMode", "view.toggleZen", "view.toggleExpert")));
    }
}
