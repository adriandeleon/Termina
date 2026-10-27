package com.termina.theme;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

import org.jspecify.annotations.Nullable;

/**
 * An imported scheme as Termina stores it: a small properties file in {@code <config>/themes/},
 * written once at import and readable — and editable — by hand, like the settings file beside it.
 *
 * <pre>
 * name=Dracula
 * background=#282a36
 * foreground=#f8f8f2
 * cursor=#f8f8f2
 * selection=#44475a
 * ansi.0=#21222c
 * …
 * ansi.15=#ffffff
 * </pre>
 */
public final class ThemeFile {

    private ThemeFile() {}

    public static String write(ColorScheme scheme) {
        StringBuilder out = new StringBuilder();
        out.append("# A colour scheme imported into Termina. Edit freely; it is read at startup.\n");
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
