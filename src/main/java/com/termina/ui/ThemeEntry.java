package com.termina.ui;

import java.nio.file.Path;

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
    private final Path file;

    ThemeEntry(String id, String displayName, boolean dark, String chrome, TerminalPalette palette, Path file) {
        this.id = id;
        this.displayName = displayName;
        this.dark = dark;
        this.chrome = chrome;
        this.palette = palette;
        this.file = file;
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

    /** Whether it ships with Termina, rather than having been imported — and so cannot be removed. */
    public boolean isBuiltIn() {
        return file == null;
    }

    /** The file an imported theme lives in, or null for a built-in one. */
    Path file() {
        return file;
    }

    /** The control stylesheet URL, for {@code Application.setUserAgentStylesheet}. */
    public String stylesheet() {
        var url = ThemeEntry.class.getResource("/com/termina/styles/" + chrome + ".css");
        if (url == null) throw new IllegalStateException("missing stylesheet for theme " + id);
        return url.toExternalForm();
    }
}
