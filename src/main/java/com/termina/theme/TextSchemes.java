package com.termina.theme;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The three plain-text formats: Ghostty's ({@code palette = 0=#1d1f21}), kitty's ({@code color0
 * #1d1f21}) and Alacritty's TOML ({@code [colors.normal] black = "#1d1f21"}). iTerm2-Color-Schemes
 * publishes every scheme in all three, so between them they cover most of what a user will find.
 *
 * <p>Only the colour keys are read; a whole configuration file can be imported and everything else
 * in it — fonts, key bindings — is ignored.
 */
final class TextSchemes {

    enum Format {
        GHOSTTY,
        KITTY,
        ALACRITTY
    }

    private static final String[] ALACRITTY_NAMES = {
        "black", "red", "green", "yellow", "blue", "magenta", "cyan", "white"
    };

    private TextSchemes() {}

    /** Which of the three a file is, from its text: an Alacritty table, a Ghostty palette line, a kitty colour line. */
    static @Nullable Format sniff(String file) {
        String text = withoutBom(file);
        boolean ghostty = false;
        boolean kitty = false;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("[colors")) return Format.ALACRITTY;
            if (line.matches("palette\\s*=.*")) ghostty = true;
            if (line.matches("color\\d{1,2}\\s+\\S.*")) kitty = true;
        }
        if (ghostty) return Format.GHOSTTY;
        if (kitty) return Format.KITTY;
        return null;
    }

    /**
     * The text without a leading byte-order mark. Notepad writes one, and it is not whitespace, so
     * {@code strip} kept it and the first line — {@code palette = 0=…}, {@code [colors.primary]} —
     * no longer read as itself.
     */
    static String withoutBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    static ColorScheme read(String file, Format format, String name) throws ThemeImportException {
        String text = withoutBom(file);
        return switch (format) {
            case GHOSTTY -> ghostty(text, name);
            case KITTY -> kitty(text, name);
            case ALACRITTY -> alacritty(text, name);
        };
    }

    private static ColorScheme ghostty(String text, String name) throws ThemeImportException {
        @Nullable Integer[] ansi = new Integer[16];
        Map<String, Integer> named = new HashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals < 0) continue;
            String key = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip();
            if (key.equals("palette")) {
                int inner = value.indexOf('=');
                if (inner < 0) continue;
                int index = index(value.substring(0, inner).strip());
                if (index >= 0)
                    ansi[index] = hexColour(value.substring(inner + 1).strip());
            } else {
                named.put(key, hexColour(value, true));
            }
        }
        return scheme(
                name,
                ansi,
                named.get("background"),
                named.get("foreground"),
                named.get("cursor-color"),
                named.get("selection-background"));
    }

    private static ColorScheme kitty(String text, String name) throws ThemeImportException {
        @Nullable Integer[] ansi = new Integer[16];
        Map<String, Integer> named = new HashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) continue;
            String key = parts[0];
            if (key.matches("color\\d{1,2}")) {
                int index = index(key.substring(5));
                if (index >= 0) ansi[index] = hexColour(parts[1].strip());
            } else {
                named.put(key, hexColour(parts[1].strip(), true));
            }
        }
        return scheme(
                name,
                ansi,
                named.get("background"),
                named.get("foreground"),
                named.get("cursor"),
                named.get("selection_background"));
    }

    private static ColorScheme alacritty(String text, String name) throws ThemeImportException {
        @Nullable Integer[] ansi = new Integer[16];
        Map<String, Integer> values = new HashMap<>();
        String table = "";
        for (String raw : text.split("\\R")) {
            String line = stripComment(raw).strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) {
                table = line.replaceAll("[\\[\\]\\s]", "").toLowerCase(Locale.ROOT);
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) continue;
            String key = line.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            values.put(
                    table + "." + key,
                    hexColour(unquote(line.substring(equals + 1).strip()), true));
        }
        for (int i = 0; i < 8; i++) {
            ansi[i] = values.get("colors.normal." + ALACRITTY_NAMES[i]);
            ansi[i + 8] = values.get("colors.bright." + ALACRITTY_NAMES[i]);
        }
        return scheme(
                name,
                ansi,
                values.get("colors.primary.background"),
                values.get("colors.primary.foreground"),
                values.get("colors.cursor.cursor"),
                values.get("colors.selection.background"));
    }

    private static ColorScheme scheme(
            String name,
            @Nullable Integer[] ansi,
            @Nullable Integer background,
            @Nullable Integer foreground,
            @Nullable Integer cursor,
            @Nullable Integer selection)
            throws ThemeImportException {
        int[] colours = new int[16];
        for (int i = 0; i < 16; i++) {
            if (ansi[i] == null)
                throw new ThemeImportException(ThemeImportException.Reason.MISSING_COLOUR, "color" + i);
            colours[i] = ansi[i];
        }
        if (background == null || foreground == null)
            throw new ThemeImportException(
                    ThemeImportException.Reason.MISSING_COLOUR, background == null ? "background" : "foreground");
        return new ColorScheme(name, background, foreground, colours, cursor, selection);
    }

    private static int index(String digits) {
        try {
            int index = Integer.parseInt(digits);
            return index >= 0 && index < 16 ? index : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A TOML comment, outside a quoted string. */
    private static String stripComment(String line) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == quote) quoted = false;
            } else if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** {@code #rrggbb}, {@code rrggbb} or {@code 0xrrggbb}. */
    static int hexColour(String text) throws ThemeImportException {
        Integer colour = hexColour(text, false);
        if (colour == null) throw new ThemeImportException(ThemeImportException.Reason.NOT_A_COLOUR, text);
        return colour;
    }

    /**
     * As {@link #hexColour(String)}, or null for a value that is not a colour when {@code lenient} —
     * a key in a configuration file that happens to hold something else.
     */
    static @Nullable Integer hexColour(String text, boolean lenient) throws ThemeImportException {
        String hex = text.strip();
        if (hex.startsWith("#")) hex = hex.substring(1);
        else if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.length() == 3 && hex.matches("[0-9a-fA-F]{3}")) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        }
        if (hex.length() == 6 && hex.matches("[0-9a-fA-F]{6}")) return Integer.parseInt(hex, 16);
        if (lenient) return null;
        throw new ThemeImportException(ThemeImportException.Reason.NOT_A_COLOUR, text);
    }
}
