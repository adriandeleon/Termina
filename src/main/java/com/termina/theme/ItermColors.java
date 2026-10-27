package com.termina.theme;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * An iTerm2 {@code .itermcolors} file — an XML property list of colours, each a dictionary of
 * {@code Red Component}, {@code Green Component} and {@code Blue Component} between 0 and 1. The
 * largest public collection of schemes (iTerm2-Color-Schemes) is published in this format first.
 */
final class ItermColors {

    private ItermColors() {}

    static ColorScheme read(byte[] file, String name) throws ThemeImportException {
        if (!(PlistXml.parse(file) instanceof Map<?, ?> colours))
            throw new ThemeImportException("not an iTerm2 colour file");
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) {
            Integer colour = colour(colours.get("Ansi " + i + " Color"));
            if (colour == null) throw new ThemeImportException("no Ansi " + i + " Color");
            ansi[i] = colour;
        }
        Integer background = colour(colours.get("Background Color"));
        Integer foreground = colour(colours.get("Foreground Color"));
        if (background == null || foreground == null) {
            throw new ThemeImportException("no Background Color or Foreground Color");
        }
        return new ColorScheme(
                name,
                background,
                foreground,
                ansi,
                colour(colours.get("Cursor Color")),
                colour(colours.get("Selection Color")));
    }

    private static @Nullable Integer colour(@Nullable Object entry) throws ThemeImportException {
        if (entry == null) return null;
        if (!(entry instanceof Map<?, ?> c)) throw new ThemeImportException("a colour that is not a dictionary");
        return ColorScheme.rgb(
                component(c, "Red Component"), component(c, "Green Component"), component(c, "Blue Component"));
    }

    private static double component(Map<?, ?> colour, String key) throws ThemeImportException {
        Object value = colour.get(key);
        if (value instanceof Number n) return n.doubleValue();
        throw new ThemeImportException("a colour with no " + key);
    }
}
