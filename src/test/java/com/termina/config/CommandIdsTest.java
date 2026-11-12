package com.termina.config;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The four commands that took the ids Pauta 6.0.1 names (ABOUT-01, PAL-01, DOCS-06, MLOG-02): a
 * key binding stored or synced under the old id still reaches its command.
 */
class CommandIdsTest {

    @Test
    void theFourCommandsHaveTheIdsPautaNames() {
        assertEquals("help.about", CommandIds.current("about"));
        assertEquals("palette.show", CommandIds.current("commandPalette"));
        assertEquals("help.documentation", CommandIds.current("documentation"));
        assertEquals("view.messageLog", CommandIds.current("messageLog"));
        assertEquals("newTab", CommandIds.current("newTab"));
        assertEquals("palette.show", CommandIds.current("palette.show"));
    }

    @Test
    void aMapKeyedByIdIsBroughtToTheNewIdsAndTheNewIdWinsWhereBothAreThere() {
        assertEquals(
                Map.of("palette.show", "Alt+X", "newTab", "Ctrl+Shift+Y", "help.about", "none"),
                CommandIds.renamed(Map.of("commandPalette", "Alt+X", "newTab", "Ctrl+Shift+Y", "about", "none")));
        assertEquals(
                Map.of("palette.show", "F1"),
                CommandIds.renamed(Map.of("commandPalette", "Alt+X", "palette.show", "F1")));
        assertEquals(Map.of(), CommandIds.renamed(Map.of()));
    }

    @Test
    void theSettingsStepRenamesTheBindingsAndLeavesEveryOtherKey() {
        Map<String, String> before = Map.of(
                "keys.commandPalette", "Alt+X",
                "keys.messageLog", "F9",
                "keys.newTab", "Ctrl+Shift+Y",
                "font.size", "15",
                // A setting that only happens to be named like an old id is not a binding.
                "about", "kept");
        assertEquals(
                Map.of(
                        "keys.palette.show", "Alt+X",
                        "keys.view.messageLog", "F9",
                        "keys.newTab", "Ctrl+Shift+Y",
                        "font.size", "15",
                        "about", "kept"),
                CommandIds.migrateSettings(before));
        // Through the file format's own list, from the version that had the old ids.
        assertEquals(
                Map.of("keys.help.documentation", "F1"), ConfigJson.migrate(1, Map.of("keys.documentation", "F1")));
        assertEquals(Map.of("keys.documentation", "F1"), ConfigJson.migrate(2, Map.of("keys.documentation", "F1")));
    }
}
