package com.termina.config;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON config format (PLAT-14, SET-09, Pauta 2.0.2). */
class ConfigJsonTest {

    @Test
    void whatIsWrittenReadsBackAsTheSameText() throws Exception {
        Map<String, String> values = Map.of(
                "font.size", "13",
                "window.opacity", "0.85",
                "ui.showMenuBar", "false",
                "profile.1.name", "007",
                "terminal.shell", "C:\\Windows\\System32\\wsl.exe",
                "keys.file.newTab", "Shortcut+Shift+T");
        ConfigJson.Document read = ConfigJson.read(ConfigJson.write(values));
        assertEquals(ConfigJson.SCHEMA_VERSION, read.schemaVersion());
        assertEquals(values, read.values());
    }

    @Test
    void numbersAndBooleansAreTypedOnlyWhenTheyReadBackExactly() {
        String json = ConfigJson.write(Map.of("a", "13", "b", "true", "c", "007", "d", "+3", "e", "1.50"));
        assertTrue(json.contains("\"a\" : 13"), json);
        assertTrue(json.contains("\"b\" : true"), json);
        assertTrue(json.contains("\"c\" : \"007\""), json);
        assertTrue(json.contains("\"d\" : \"+3\""), json);
        assertTrue(json.contains("\"e\" : \"1.50\""), json);
    }

    @Test
    void everyFileSaysItsVersion() throws Exception {
        assertTrue(ConfigJson.write(Map.of()).contains("\"schemaVersion\" : " + ConfigJson.SCHEMA_VERSION));
        assertThrows(ConfigJson.FormatException.class, () -> ConfigJson.read("{\"settings\": {}}"));
    }

    @Test
    void whatIsNotThisFormatIsRefusedNotGuessed() {
        assertThrows(ConfigJson.FormatException.class, () -> ConfigJson.read("theme=editora-light"));
        assertThrows(ConfigJson.FormatException.class, () -> ConfigJson.read("[1, 2]"));
        assertThrows(ConfigJson.FormatException.class, () -> ConfigJson.read("{\"schemaVersion\": \"one\"}"));
    }

    @Test
    void aValueThisBuildCannotHoldIsDroppedNotFatal() throws Exception {
        ConfigJson.Document read = ConfigJson.read(
                "{\"schemaVersion\": 1, \"settings\": {\"x\": {\"nested\": 1}, \"y\": null, \"z\": \"ok\"}}");
        assertEquals(Map.of("z", "ok"), read.values());
    }

    @Test
    void thereIsAStepForEveryVersionBeforeThisOne() {
        // SET-09: one migration per version, so an old file is always brought forward.
        assertEquals(ConfigJson.SCHEMA_VERSION - 1, ConfigJson.MIGRATIONS.size());
        assertEquals(Map.of("k", "v"), ConfigJson.migrate(1, Map.of("k", "v")));
        assertFalse(ConfigJson.isCanonicalInteger("007"));
    }

    /** PLAT-14 (Pauta 6.0.1): the file goes through Jackson's ObjectMapper as a plain class. */
    @Test
    void theFileIsReadAndWrittenByTheObjectMapper() throws Exception {
        String text = ConfigJson.write(java.util.Map.of("font.size", "13", "ui.showMenuBar", "true", "name", "007"));
        ConfigJson.Stored stored = ConfigJson.mapper().readValue(text, ConfigJson.Stored.class);
        assertEquals(ConfigJson.SCHEMA_VERSION, stored.schemaVersion);
        assertEquals(13, stored.settings.get("font.size"), "a number in the file");
        assertEquals(Boolean.TRUE, stored.settings.get("ui.showMenuBar"));
        assertEquals("007", stored.settings.get("name"), "what would not read back as itself stays text");
        assertEquals(
                java.util.Map.of("font.size", "13", "ui.showMenuBar", "true", "name", "007"),
                ConfigJson.read(text).values());
        // The version first, then the values sorted by key, one to a line.
        assertTrue(text.indexOf("schemaVersion") < text.indexOf("settings"), text);
        assertTrue(
                text.indexOf("font.size") < text.indexOf("name") && text.indexOf("name") < text.indexOf("ui."), text);

        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/termina/config/ConfigJson.java"));
        for (String byHand : java.util.List.of("JsonGenerator", "JsonParser", "JsonFactory", "JsonToken")) {
            org.junit.jupiter.api.Assertions.assertFalse(source.contains(byHand), byHand);
        }
    }
}
