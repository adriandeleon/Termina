package com.termina.theme;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Properties;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * An imported scheme as Termina stores it: a small properties file in {@code <config>/themes/},
 * written once at import and readable — and editable — by hand, like the settings file beside it.
 *
 * <pre>
 * schemaVersion=1
 * name=Dracula
 * background=#282a36
 * foreground=#f8f8f2
 * cursor=#f8f8f2
 * selection=#44475a
 * ansi.0=#21222c
 * …
 * ansi.15=#ffffff
 * </pre>
 *
 * <p>The file carries a schema version like every config file (SET-09, Pauta 6.0.1). A file with
 * none is version 0, which is what every build before the version wrote.
 */
public final class ThemeFile {

    private ThemeFile() {}

    /** The version this build writes and reads. */
    public static final int SCHEMA_VERSION = 1;

    /** The key the version is stored under, the settings file's own name for it. */
    static final String VERSION_KEY = "schemaVersion";

    /**
     * One step for every version below the current one, {@code v → v+1}, in order. Version 1 only
     * added the version itself, so its step changes nothing.
     */
    static final List<UnaryOperator<Properties>> MIGRATIONS = List.of(UnaryOperator.identity());

    /**
     * The schema version a stored file says it is: 0 for a file with none, or with one that is not
     * a number. Never throws, so the caller can decide what to do with a file before reading it:
     * one from a newer build is kept aside, not read (SET-10).
     */
    public static int versionOf(String text) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
            return Math.max(
                    0, Integer.parseInt(properties.getProperty(VERSION_KEY, "0").strip()));
        } catch (IOException | IllegalArgumentException e) {
            return 0;
        }
    }

    public static String write(ColorScheme scheme) {
        StringBuilder out = new StringBuilder();
        out.append("# A colour scheme imported into Termina. Edit freely; it is read at startup.\n");
        out.append(VERSION_KEY).append('=').append(SCHEMA_VERSION).append('\n');
        out.append("name=").append(escape(scheme.name())).append('\n');
        out.append("background=").append(ColorScheme.hex(scheme.background())).append('\n');
        out.append("foreground=").append(ColorScheme.hex(scheme.foreground())).append('\n');
        if (scheme.cursor() != null)
            out.append("cursor=").append(ColorScheme.hex(scheme.cursor())).append('\n');
        if (scheme.selection() != null) {
            out.append("selection=").append(ColorScheme.hex(scheme.selection())).append('\n');
        }
        int[] ansi = scheme.ansi();
        for (int i = 0; i < 16; i++)
            out.append("ansi.")
                    .append(i)
                    .append('=')
                    .append(ColorScheme.hex(ansi[i]))
                    .append('\n');
        return out.toString();
    }

    public static ColorScheme read(String text) throws ThemeImportException {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            throw new ThemeImportException(e, ThemeImportException.Reason.NOT_FORMAT, "Termina");
        }
        // An older file is brought up a version at a time; a newer one never gets here from the
        // themes folder, and read on its own is read as the current shape.
        for (int version = versionOf(text); version < SCHEMA_VERSION; version++) {
            properties = MIGRATIONS.get(version).apply(properties);
        }
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) ansi[i] = required(properties, "ansi." + i);
        return new ColorScheme(
                properties.getProperty("name", ""),
                required(properties, "background"),
                required(properties, "foreground"),
                ansi,
                optional(properties, "cursor"),
                optional(properties, "selection"));
    }

    private static int required(Properties properties, String key) throws ThemeImportException {
        String value = properties.getProperty(key);
        if (value == null) throw new ThemeImportException(ThemeImportException.Reason.MISSING_COLOUR, key);
        return TextSchemes.hexColour(value);
    }

    private static @Nullable Integer optional(Properties properties, String key) throws ThemeImportException {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? null : TextSchemes.hexColour(value);
    }

    /** A name as a properties value: the format's own escapes for what it would otherwise read. */
    private static String escape(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) continue;
                    out.append(c);
                }
            }
        }
        if (!out.isEmpty() && (out.charAt(0) == ' ' || out.charAt(0) == '#' || out.charAt(0) == '!'))
            out.insert(0, '\\');
        return out.toString();
    }
}
