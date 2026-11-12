package com.termina.config;

import java.util.Map;
import java.util.TreeMap;

/**
 * The command ids that have been renamed, old to new, so that whatever was stored or synced under
 * an old id still reaches its command.
 *
 * <p>A command's id is what a stored key binding is keyed by: {@code keys.<id>} in
 * {@code settings.json}, and a line of the synced key-bindings files. Pauta 6.0.1 names four
 * commands outright (ABOUT-01, PAL-01, DOCS-06, MLOG-02), and those took the names it gives. The
 * settings file is brought forward by a migration step ({@link ConfigJson#MIGRATIONS}); the sync
 * files, which another computer on an older Termina may still be writing, are read through
 * {@link #renamed(Map)} every time.
 */
public final class CommandIds {

    /** Old id to the id the command has now. */
    static final Map<String, String> RENAMED = Map.of(
            "about", "help.about",
            "commandPalette", "palette.show",
            "documentation", "help.documentation",
            "messageLog", "view.messageLog");

    private CommandIds() {}

    /** The id a command has now, given one it has or used to have. */
    public static String current(String id) {
        return RENAMED.getOrDefault(id, id);
    }

    /**
     * A map keyed by command id, with every old id replaced by the current one. Where both are
     * there, the current id's value stands: it was written by a build that knew the new name.
     */
    public static Map<String, String> renamed(Map<String, String> byId) {
        Map<String, String> out = new TreeMap<>();
        byId.forEach((id, value) -> {
            if (!RENAMED.containsKey(id)) out.put(id, value);
        });
        byId.forEach((id, value) -> {
            String now = RENAMED.get(id);
            if (now != null) out.putIfAbsent(now, value);
        });
        return out;
    }

    /**
     * The settings file's step from schema 1 to 2: each {@code keys.<old id>} becomes
     * {@code keys.<new id>}. Every other key is left as it is.
     */
    static Map<String, String> migrateSettings(Map<String, String> values) {
        Map<String, String> bindings = new TreeMap<>();
        Map<String, String> out = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(Settings.KEYS_PREFIX) && key.length() > Settings.KEYS_PREFIX.length()) {
                bindings.put(key.substring(Settings.KEYS_PREFIX.length()), value);
            } else {
                out.put(key, value);
            }
        });
        renamed(bindings).forEach((id, value) -> out.put(Settings.KEYS_PREFIX + id, value));
        return out;
    }
}
