package com.termina.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reading and writing a config folder's JSON files in a test, as a hand would. */
final class StoredConfig {

    private StoredConfig() {}

    /** Both files' text, preferences then state; what a test means by "what was stored". */
    static String stored(Path dir) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String name : new String[] {Settings.SETTINGS_FILE, Settings.STATE_FILE}) {
            Path path = dir.resolve(name);
            if (Files.exists(path)) out.append(Files.readString(path));
        }
        return out.toString();
    }

    /** How a key and value appear in a stored file. */
    static String entry(String key, String value) {
        return ConfigJson.write(Map.of(key, value))
                .lines()
                .filter(line -> line.contains("\"" + key + "\""))
                .findFirst()
                .orElseThrow()
                .trim()
                .replaceAll(",$", "");
    }

    /** Writes a settings.json holding these keys and values, as hand-editing would leave it. */
    static void writeSettings(Path dir, String... keysAndValues) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) values.put(keysAndValues[i], keysAndValues[i + 1]);
        Files.writeString(dir.resolve(Settings.SETTINGS_FILE), ConfigJson.write(values));
    }

    /** The same for the state file. */
    static void writeState(Path dir, String... keysAndValues) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) values.put(keysAndValues[i], keysAndValues[i + 1]);
        Files.writeString(dir.resolve(Settings.STATE_FILE), ConfigJson.write(values));
    }
}
