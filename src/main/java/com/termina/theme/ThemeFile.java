package com.termina.theme;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.termina.config.ConfigJson;
import org.jspecify.annotations.Nullable;

/**
 * An imported scheme as Termina stores it: a small JSON file in {@code <config>/themes/}, written
 * once at import and readable, and editable, by hand, like the settings file beside it.
 *
 * <pre>
 * {
 *   "schemaVersion" : 2,
 *   "name" : "Dracula",
 *   "background" : "#282a36",
 *   "foreground" : "#f8f8f2",
 *   "cursor" : "#f8f8f2",
 *   "selection" : "#44475a",
 *   "ansi" : [ "#21222c", … sixteen in all … , "#ffffff" ]
 * }
 * </pre>
 *
 * <p>Read and written by Jackson's {@code ObjectMapper} as a plain class, {@link Stored} (PLAT-14,
 * Pauta 6.0.1), with the mapper the settings file uses. The file carries a schema version like
 * every config file (SET-09). Until 0.15 it was a properties file, {@code <name>.properties}, with
 * the sixteen colours as {@code ansi.0} to {@code ansi.15}: version 1, or version 0 for one written
 * before the version was. Both are still read, and {@code ui.Themes} rewrites one as JSON the first
 * time it meets it.
 */
public final class ThemeFile {

    private ThemeFile() {}

    /** The version this build writes and reads. */
    public static final int SCHEMA_VERSION = 2;

    /** What a theme's file name ends in. */
    public static final String EXTENSION = ".json";

    /** What it ended in until 0.15, when the file was a properties file. */
    public static final String LEGACY_EXTENSION = ".properties";

    /** The key the version is stored under, the settings file's own name for it. */
    static final String VERSION_KEY = "schemaVersion";

    private static final int COLOURS = 16;

    /**
     * One step for every version below the current one, {@code v → v+1}, in order, over the file's
     * keys and values. Version 1 only added the version itself, so its step changes nothing;
     * version 2 is the JSON file, whose sixteen colours are one list.
     */
    static final List<UnaryOperator<Map<String, Object>>> MIGRATIONS =
            List.of(UnaryOperator.identity(), ThemeFile::ansiAsAList);

    /** The file as Jackson reads and writes it. A colour is {@code #rrggbb}. */
    @JsonPropertyOrder({"schemaVersion", "name", "background", "foreground", "cursor", "selection", "ansi"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Stored {
        public @Nullable Integer schemaVersion;
        public @Nullable String name;
        public @Nullable String background;
        public @Nullable String foreground;
        public @Nullable String cursor;
        public @Nullable String selection;
        public @Nullable List<@Nullable String> ansi;
    }

    /** Whether a file's text is the properties form from before 0.15, not JSON. Pure. */
    public static boolean isLegacy(String text) {
        return !withoutMark(text).stripLeading().startsWith("{");
    }

    /**
     * The schema version a stored file says it is: 0 for a file with none, or with one that is not
     * a number. Never throws, so the caller can decide what to do with a file before reading it:
     * one from a newer build is kept aside, not read (SET-10).
     */
    public static int versionOf(String text) {
        try {
            return versionOf(tree(text));
        } catch (ThemeImportException e) {
            return 0;
        }
    }

    private static int versionOf(Map<String, Object> tree) {
        Object version = tree.get(VERSION_KEY);
        if (version instanceof Number number) return Math.max(0, number.intValue());
        if (version instanceof String string) {
            try {
                return Math.max(0, Integer.parseInt(string.strip()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    public static String write(ColorScheme scheme) {
        Stored stored = new Stored();
        stored.schemaVersion = SCHEMA_VERSION;
        stored.name = scheme.name();
        stored.background = ColorScheme.hex(scheme.background());
        stored.foreground = ColorScheme.hex(scheme.foreground());
        Integer cursor = scheme.cursor();
        if (cursor != null) stored.cursor = ColorScheme.hex(cursor);
        Integer selection = scheme.selection();
        if (selection != null) stored.selection = ColorScheme.hex(selection);
        List<@Nullable String> ansi = new ArrayList<>();
        for (int colour : scheme.ansi()) ansi.add(ColorScheme.hex(colour));
        stored.ansi = ansi;
        try {
            return ConfigJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(stored)
                    + System.lineSeparator();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a theme of text and colours is always writable", e);
        }
    }

    public static ColorScheme read(String text) throws ThemeImportException {
        Map<String, Object> tree = tree(text);
        // An older file is brought up a version at a time; a newer one never gets here from the
        // themes folder, and read on its own is read as the current shape.
        for (int version = versionOf(tree); version < SCHEMA_VERSION; version++) {
            tree = MIGRATIONS.get(version).apply(tree);
        }
        Stored stored;
        try {
            stored = ConfigJson.mapper().convertValue(tree, Stored.class);
        } catch (IllegalArgumentException e) {
            throw new ThemeImportException(e, ThemeImportException.Reason.NOT_FORMAT, "Termina");
        }
        List<@Nullable String> listed = stored.ansi == null ? List.of() : stored.ansi;
        int[] ansi = new int[COLOURS];
        for (int i = 0; i < COLOURS; i++) {
            ansi[i] = required(i < listed.size() ? listed.get(i) : null, "ansi." + i);
        }
        return new ColorScheme(
                stored.name == null ? "" : stored.name,
                required(stored.background, "background"),
                required(stored.foreground, "foreground"),
                ansi,
                optional(stored.cursor),
                optional(stored.selection));
    }

    /**
     * The file's keys and values, whichever form it is in: the JSON object as it stands, or the
     * properties file's lines, every value text.
     */
    private static Map<String, Object> tree(String text) throws ThemeImportException {
        String content = withoutMark(text);
        try {
            if (!isLegacy(content)) {
                return ConfigJson.mapper().readValue(content, new TypeReference<LinkedHashMap<String, Object>>() {});
            }
            Properties properties = new Properties();
            properties.load(new StringReader(content));
            Map<String, Object> tree = new LinkedHashMap<>();
            for (String key : properties.stringPropertyNames()) tree.put(key, properties.getProperty(key));
            return tree;
        } catch (IOException | IllegalArgumentException e) {
            throw new ThemeImportException(e, ThemeImportException.Reason.NOT_FORMAT, "Termina");
        }
    }

    /**
     * Version 1 to 2: {@code ansi.0} to {@code ansi.15} become the list {@code ansi}, a colour the
     * file lacks leaving its place empty. A file that already has the list keeps it.
     */
    static Map<String, Object> ansiAsAList(Map<String, Object> before) {
        Map<String, Object> after = new LinkedHashMap<>();
        before.forEach((key, value) -> {
            if (!key.startsWith("ansi.")) after.put(key, value);
        });
        if (!(before.get("ansi") instanceof List<?>)) {
            List<@Nullable Object> ansi = new ArrayList<>();
            for (int i = 0; i < COLOURS; i++) ansi.add(before.get("ansi." + i));
            after.put("ansi", ansi);
        }
        return after;
    }

    private static int required(@Nullable String value, String key) throws ThemeImportException {
        if (value == null) throw new ThemeImportException(ThemeImportException.Reason.MISSING_COLOUR, key);
        return TextSchemes.hexColour(value);
    }

    private static @Nullable Integer optional(@Nullable String value) throws ThemeImportException {
        return value == null || value.isBlank() ? null : TextSchemes.hexColour(value);
    }

    /** The text without the byte-order mark an editor may have put in front of it. */
    private static String withoutMark(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
