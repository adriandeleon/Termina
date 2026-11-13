package com.termina.config;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The commands that took the ids Pauta 6.0.1 names (ABOUT-01, PAL-01, DOCS-06, MLOG-02, FIND-04): a
 * key binding stored or synced under the old id still reaches its command.
 */
class CommandIdsTest {

    @Test
    void theCommandsHaveTheIdsPautaNames() {
        assertEquals("help.about", CommandIds.current("about"));
        assertEquals("palette.show", CommandIds.current("commandPalette"));
        assertEquals("help.documentation", CommandIds.current("documentation"));
        assertEquals("view.messageLog", CommandIds.current("messageLog"));
        assertEquals("find.show", CommandIds.current("find"));
        assertEquals("app.quit", CommandIds.current("app.quit"));
        assertEquals("palette.show", CommandIds.current("palette.show"));
    }

    @Test
    void aMapKeyedByIdIsBroughtToTheNewIdsAndTheNewIdWinsWhereBothAreThere() {
        assertEquals(
                Map.of("palette.show", "Alt+X", "app.quit", "Ctrl+Shift+Y", "help.about", "none"),
                CommandIds.renamed(Map.of("commandPalette", "Alt+X", "app.quit", "Ctrl+Shift+Y", "about", "none")));
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
                "keys.app.quit", "Ctrl+Shift+Y",
                "font.size", "15",
                // A setting that only happens to be named like an old id is not a binding.
                "about", "kept");
        assertEquals(
                Map.of(
                        "keys.palette.show", "Alt+X",
                        "keys.view.messageLog", "F9",
                        "keys.app.quit", "Ctrl+Shift+Y",
                        "font.size", "15",
                        "about", "kept"),
                CommandIds.migrateSettings(before));
        // Through the file format's own list, from the version that had the old ids.
        assertEquals(
                Map.of("keys.help.documentation", "F1"), ConfigJson.migrate(1, Map.of("keys.documentation", "F1")));
        assertEquals(Map.of("keys.documentation", "F1"), ConfigJson.migrate(2, Map.of("keys.documentation", "F1")));
        // And a file of version 2 takes only the second step; one of version 1 takes both.
        assertEquals(Map.of("keys.file.newTab", "F2"), ConfigJson.migrate(2, Map.of("keys.newTab", "F2")));
        assertEquals(Map.of("keys.file.newTab", "F2"), ConfigJson.migrate(1, Map.of("keys.newTab", "F2")));
        assertEquals(Map.of("keys.newTab", "F2"), ConfigJson.migrate(3, Map.of("keys.newTab", "F2")));
    }

    @Test
    void everyIdThatHadNoAreaTookOne() {
        // KBD-02 (Pauta 6.0.1): what was stored or synced under the bare id still reaches its command.
        assertEquals("file.newTab", CommandIds.current("newTab"));
        assertEquals("edit.copy", CommandIds.current("copy"));
        assertEquals("sync.now", CommandIds.current("syncNow"));
        assertEquals("settings.show", CommandIds.current("settings"));
        assertEquals("tab.goTo9", CommandIds.current("goToTab9"));
        assertEquals("view.toggleStatusBar", CommandIds.current("showStatusBar"));
        assertEquals(
                Map.of("keys.file.newTab", "Ctrl+Shift+Y", "keys.edit.copy", "none", "keys.settings.reset", "F2"),
                CommandIds.groupByArea(
                        Map.of("keys.newTab", "Ctrl+Shift+Y", "keys.copy", "none", "keys.settings.reset", "F2")));
        // Every new id has an area, and none is itself an old id, so a second pass changes nothing.
        for (Map.Entry<String, String> renamed : CommandIds.RENAMED.entrySet()) {
            org.junit.jupiter.api.Assertions.assertTrue(renamed.getValue().contains("."), renamed.toString());
            org.junit.jupiter.api.Assertions.assertFalse(
                    CommandIds.RENAMED.containsKey(renamed.getValue()), renamed.toString());
        }
    }

    /**
     * KBD-02 (Pauta 6.0.1): every command's id is two or more names joined by dots, the first its
     * area. Read from where a command gets its id, its title's key in the catalogue, and from the
     * default key maps, so a new command with a bare id fails here.
     */
    @Test
    void everyCommandIdHasItsAreaInFront() throws java.io.IOException {
        java.util.List<String> bare = new java.util.ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(
                java.nio.file.Path.of("src/main/resources/com/termina/i18n/messages.properties"))) {
            java.util.regex.Matcher key =
                    java.util.regex.Pattern.compile("^command\\.([^=]+)=").matcher(line);
            if (!key.find()) continue;
            String id = key.group(1).replaceFirst("\\.desc$", "");
            if (!id.matches("[a-z][A-Za-z]*(\\.[A-Za-z][A-Za-z0-9]*)+")) bare.add(id);
        }
        for (String map : java.util.List.of("keymap.json", "keymap.mac.json")) {
            String text = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/resources/com/termina/keymaps", map));
            java.util.regex.Matcher binding = java.util.regex.Pattern.compile(
                            "^\\s+\"([^\"]+)\"\\s*:\\s*\"", java.util.regex.Pattern.MULTILINE)
                    .matcher(text.substring(text.indexOf("\"bindings\"")));
            while (binding.find()) {
                if (!binding.group(1).contains(".")) bare.add(map + ": " + binding.group(1));
            }
        }
        assertEquals(java.util.List.of(), bare);
    }
}
