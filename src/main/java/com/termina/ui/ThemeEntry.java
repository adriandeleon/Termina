package com.termina.ui;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * A theme the user can choose: one of the built-in {@link Theme}s, or a scheme imported from
 * another terminal (see {@link Themes}). Everything that draws asks one of these, so the two kinds
 * are indistinguishable past the picker.
 */
public final class ThemeEntry {

    private final String id;
    private final String displayName;
    private final boolean dark;
    private final String chrome;
    private final TerminalPalette palette;
    private final @Nullable Path file;

    ThemeEntry(
            String id, String displayName, boolean dark, String chrome, TerminalPalette palette, @Nullable Path file) {
        this.id = id;
        this.displayName = displayName;
        this.dark = dark;
        this.chrome = chrome;
        this.palette = palette;
        this.file = file;
    }

    /** This theme under another id and name: what a picker shows for "Follow the System". */
    ThemeEntry as(String otherId, String otherName) {
        return new ThemeEntry(otherId, otherName, dark, chrome, palette, file);
    }

    static ThemeEntry of(Theme theme) {
        return new ThemeEntry(theme.id(), theme.displayName(), theme.isDark(), theme.chrome(), theme.palette(), null);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isDark() {
        return dark;
    }

    public TerminalPalette palette() {
        return palette;
    }

    /**
     * What a window drawn in this theme tells JavaFX it is, through its scene's preferences. JavaFX
     * styles what it draws itself from that — the window buttons of an extended title bar, and on
     * Windows and macOS the native frame's light or dark variant — and left alone it is the
     * desktop's setting, not ours: a dark theme on a light desktop got dark grey buttons on a dark
     * header.
     */
    public javafx.application.ColorScheme colorScheme() {
        return dark ? javafx.application.ColorScheme.DARK : javafx.application.ColorScheme.LIGHT;
    }

    /** Whether it ships with Termina, rather than having been imported — and so cannot be removed. */
    public boolean isBuiltIn() {
        return file == null;
    }

    /** The file an imported theme lives in, or null for a built-in one. */
    @Nullable
    Path file() {
        return file;
    }

    /** The control theme this entry draws windows in: an Editora sheet, through AtlantaFX. */
    ChromeTheme chromeTheme() {
        return new ChromeTheme(chrome, dark);
    }

    /** The control stylesheet URL, for {@code Application.setUserAgentStylesheet}. */
    public String stylesheet() {
        return chromeTheme().stylesheetUrl();
    }
}
