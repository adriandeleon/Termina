package com.termina.theme;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Reads a colour scheme from a file another terminal wrote, whichever of the formats it is.
 *
 * <p>By extension first, and by content where the extension says nothing: a Ghostty theme usually
 * has none at all, and an Alacritty one is often {@code .toml} and sometimes not. A file this cannot
 * read says why, in words a person can act on, rather than importing something half-right.
 */
public final class ThemeImport {

    /** The extensions a file chooser should offer. */
    public static final List<String> EXTENSIONS =
            List.of("*.itermcolors", "*.terminal", "*.json", "*.toml", "*.conf", "*");

    /** Past this a "theme" is not one, and reading it is a waste of memory. */
    static final int MAX_BYTES = 4 * 1024 * 1024;

    private ThemeImport() {}

    /**
     * Every scheme in the file — one, except for a Windows Terminal {@code settings.json}, which can
     * hold many.
     *
     * @param fileName the file's name, for its extension and for the name of a scheme that does not
     *     carry one
     */
    public static List<ColorScheme> read(String fileName, byte[] content) throws ThemeImportException {
        if (content.length > MAX_BYTES) throw new ThemeImportException("too large to be a colour scheme");
        String lower = fileName.toLowerCase(Locale.ROOT);
        String name = baseName(fileName);
        if (lower.endsWith(".itermcolors")) return List.of(ItermColors.read(content, name));
        if (lower.endsWith(".terminal")) return List.of(AppleTerminalProfile.read(content, name));
        if (lower.endsWith(".json")) return WindowsTerminalSchemes.read(content, name);
        String text = new String(content, StandardCharsets.UTF_8);
        String trimmed = text.stripLeading();
        // No helpful extension: a property list or JSON says so in its first character.
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<plist") || trimmed.startsWith("<!DOCTYPE")) {
            return List.of(
                    trimmed.contains("Ansi 0 Color")
                            ? ItermColors.read(content, name)
                            : AppleTerminalProfile.read(content, name));
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[{")) return WindowsTerminalSchemes.read(content, name);
        TextSchemes.Format format = lower.endsWith(".toml") ? TextSchemes.Format.ALACRITTY : TextSchemes.sniff(text);
        if (format == null) {
            throw new ThemeImportException(
                    "not a colour scheme this can read (iTerm2, Terminal, Windows Terminal, Ghostty, kitty or Alacritty)");
        }
        return List.of(TextSchemes.read(text, format, name));
    }

    /** {@code Dracula.itermcolors} → {@code Dracula}. */
    static String baseName(String fileName) {
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
