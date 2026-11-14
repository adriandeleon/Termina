package com.termina.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The glyph table (MENU-14, Pauta 6.0.1): an entry for every command a menu lists, each a shape
 * that draws, and none borrowed from a different action.
 */
@Tag("fx")
class MenuIconsTest {

    /** An SVGPath is a Node, and constructing one asks the toolkit for the platform stylesheet. */
    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
    }

    @Test
    void everyRowOfTheBarHasAGlyph() {
        List<String> without = new ArrayList<>();
        for (MenuBarModel.Menu menu : MenuBarModel.menus()) {
            for (String row : menu.rows()) {
                if (row.equals(MenuBarModel.SEPARATOR)) continue;
                List<String> ids = MenuBarModel.isCommand(row) ? List.of(row) : MenuBarModel.submenuRows(row);
                for (String id : ids) if (MenuIcons.forCommand(id) == null) without.add(id);
            }
        }
        assertEquals(List.of(), without);
    }

    @Test
    void everyGlyphDraws() {
        // An SVG path JavaFX cannot read draws nothing and says nothing: the row shows a gap.
        for (String id : MenuIcons.commandsWithGlyphs()) {
            Node glyph = MenuIcons.command(id);
            assertTrue(glyph.getBoundsInLocal().getWidth() > 0, id + " has no width");
            assertTrue(glyph.getBoundsInLocal().getHeight() > 0, id + " has no height");
            // No larger than its grid: a glyph that overflows it was drawn for the other one.
            SVGPath path = (SVGPath) glyph;
            boolean outline = path.getStyleClass().contains("menu-icon-line");
            double grid = outline ? 16 : 24;
            assertTrue(path.getBoundsInLocal().getMaxX() <= grid + 0.5, id + " is wider than its grid");
            assertTrue(path.getBoundsInLocal().getMaxY() <= grid + 0.5, id + " is taller than its grid");
            assertTrue(outline || path.getStyleClass().contains("menu-icon"), id + " has no colour rule");
        }
    }

    @Test
    void aGlyphIsSharedOnlyByTheSameActionInAnotherPlace() {
        // Copy is Copy in each log and in the terminal; a profile row wears its profile's mark
        // once shown. Anything else on one glyph is a command borrowing another's.
        Map<String, Set<String>> byPath = new HashMap<>();
        for (String id : MenuIcons.commandsWithGlyphs()) {
            String path = ((SVGPath) MenuIcons.command(id)).getContent();
            byPath.computeIfAbsent(path, key -> new TreeSet<>()).add(id);
        }
        List<Set<String>> shared =
                byPath.values().stream().filter(ids -> ids.size() > 1).toList();
        Set<String> profiles = new TreeSet<>();
        for (int number = 1; number <= WindowRules.TAB_CHORD_COUNT; number++) {
            profiles.add(WindowRules.PROFILE_CHORD_ID + number);
        }
        Set<Set<String>> kin = Set.of(
                new TreeSet<>(List.of("edit.copy", "debugLog.copy", "messageLog.copy")),
                new TreeSet<>(List.of("edit.clearScrollback", "debugLog.clear", "messageLog.clear")),
                new TreeSet<>(List.of("find.show", "debugLog.find")),
                profiles);
        for (Set<String> ids : shared) assertTrue(kin.contains(ids), "one glyph for " + ids);
    }

    @Test
    void aCommandNoMenuListsHasNoneAndAskingForOneSaysSo() {
        assertNull(MenuIcons.forCommand(null));
        assertNull(MenuIcons.forCommand("tab.goTo3"));
        assertThrows(IllegalStateException.class, () -> MenuIcons.command("tab.goTo3"));
        assertNotNull(MenuIcons.zoom());
    }
}
