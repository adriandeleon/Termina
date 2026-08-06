package com.termina.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * User preferences, stored as a plain properties file in {@code ~/.termina/settings.properties}.
 *
 * <p>Properties rather than JSON or TOML because it needs no dependency, and because a terminal's
 * settings file is one someone will reasonably open in a text editor. Every getter falls back to a
 * default, so a hand-edited file with a typo degrades to the default for that one key instead of
 * failing to load.
 */
public final class Settings {

    public static final String THEME = "theme";
    public static final String FONT_FAMILY = "font.family";
    public static final String FONT_SIZE = "font.size";
    public static final String SCROLLBACK_LINES = "terminal.scrollback";
    public static final String CURSOR_SHAPE = "terminal.cursorShape";
    public static final String ALT_IS_META = "input.altIsMeta";
    public static final String SHELL = "terminal.shell";
    public static final String BELL = "terminal.bell";
    public static final String HIDE_TAB_BAR_WHEN_SINGLE = "ui.hideTabBarWhenSingle";

    /** Cursor appearance. Names match the DECSCUSR families a program can also request. */
    public enum CursorShape {
        BLOCK,
        UNDERLINE,
        BAR
    }

    /** Empty means "pick the best available monospace face for this platform". */
    public static final String DEFAULT_FONT_FAMILY = "";

    public static final double DEFAULT_FONT_SIZE = 13;
    public static final double MIN_FONT_SIZE = 7;
    public static final double MAX_FONT_SIZE = 40;

    public static final int DEFAULT_SCROLLBACK = 5000;
    public static final int MIN_SCROLLBACK = 0;
    /** A hard ceiling: scrollback is retained in memory, so this is a footprint decision. */
    public static final int MAX_SCROLLBACK = 200_000;

    private final Path file;
    private final Properties properties = new Properties();
    private Runnable onChange = () -> {};

    public Settings(Path file) {
        this.file = file;
    }

    /** The standard location. Honours {@code TERMINA_CONFIG_DIR} so a dev run can be isolated. */
    public static Path defaultFile() {
        String override = System.getenv("TERMINA_CONFIG_DIR");
        Path dir = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".termina");
        return dir.resolve("settings.properties");
    }

    /** Notified after any successful change, so the UI can re-apply live. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> {} : onChange;
    }

    public void load() {
        if (!Files.isReadable(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            // A corrupt or unreadable file falls back to defaults rather than refusing to start:
            // settings are a convenience, and a terminal that will not open is not one.
            System.getLogger(Settings.class.getName())
                    .log(System.Logger.Level.WARNING, "could not read " + file + "; using defaults", e);
        }
    }

    public void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "Termina settings");
            }
        } catch (IOException e) {
            System.getLogger(Settings.class.getName())
                    .log(System.Logger.Level.WARNING, "could not write " + file, e);
        }
    }

    public Path file() {
        return file;
    }

    // ---------------------------------------------------------------- typed accessors

    public String themeId() {
        return properties.getProperty(THEME, "editora-dark");
    }

    public void setThemeId(String id) {
        put(THEME, id);
    }

    /** Blank means auto-select; {@link com.termina.ui.MonospaceFonts} resolves it. */
    public String fontFamily() {
        return properties.getProperty(FONT_FAMILY, DEFAULT_FONT_FAMILY);
    }

    public void setFontFamily(String family) {
        put(FONT_FAMILY, family == null ? "" : family);
    }

    public double fontSize() {
        return clamp(readDouble(FONT_SIZE, DEFAULT_FONT_SIZE), MIN_FONT_SIZE, MAX_FONT_SIZE);
    }

    public void setFontSize(double size) {
        put(FONT_SIZE, String.valueOf(clamp(size, MIN_FONT_SIZE, MAX_FONT_SIZE)));
    }

    public int scrollbackLines() {
        return (int) clamp(readInt(SCROLLBACK_LINES, DEFAULT_SCROLLBACK), MIN_SCROLLBACK, MAX_SCROLLBACK);
    }

    public void setScrollbackLines(int lines) {
        put(SCROLLBACK_LINES, String.valueOf((int) clamp(lines, MIN_SCROLLBACK, MAX_SCROLLBACK)));
    }

    public CursorShape cursorShape() {
        String raw = properties.getProperty(CURSOR_SHAPE, CursorShape.BLOCK.name());
        try {
            return CursorShape.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CursorShape.BLOCK;
        }
    }

    public void setCursorShape(CursorShape shape) {
        put(CURSOR_SHAPE, shape.name());
    }

    public boolean altIsMeta() {
        return readBoolean(ALT_IS_META, true);
    }

    public void setAltIsMeta(boolean altIsMeta) {
        put(ALT_IS_META, String.valueOf(altIsMeta));
    }

    /** Blank means the user's own shell, as {@code $SHELL} reports it. */
    public String shell() {
        return properties.getProperty(SHELL, "");
    }

    public void setShell(String shell) {
        put(SHELL, shell == null ? "" : shell.trim());
    }

    public boolean bell() {
        return readBoolean(BELL, true);
    }

    public void setBell(boolean bell) {
        put(BELL, String.valueOf(bell));
    }

    /**
     * Hide the tab strip while only one tab is open.
     *
     * <p>Defaults to on, matching iTerm2, GNOME Terminal and Windows Terminal: a strip showing a
     * single tab is a row of chrome that conveys nothing.
     */
    public boolean hideTabBarWhenSingle() {
        return readBoolean(HIDE_TAB_BAR_WHEN_SINGLE, true);
    }

    public void setHideTabBarWhenSingle(boolean hide) {
        put(HIDE_TAB_BAR_WHEN_SINGLE, String.valueOf(hide));
    }

    /** Clears every stored value; every accessor then reports its default. */
    public void resetToDefaults() {
        properties.clear();
        save();
        onChange.run();
    }

    // ---------------------------------------------------------------- internals

    private void put(String key, String value) {
        String previous = properties.getProperty(key);
        if (value.equals(previous)) return;
        properties.setProperty(key, value);
        save();
        onChange.run();
    }

    private double readDouble(String key, double fallback) {
        try {
            String raw = properties.getProperty(key);
            return raw == null ? fallback : Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int readInt(String key, int fallback) {
        try {
            String raw = properties.getProperty(key);
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean readBoolean(String key, boolean fallback) {
        String raw = properties.getProperty(key);
        if (raw == null) return fallback;
        String v = raw.trim();
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
