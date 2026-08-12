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
    public static final String FONT_ZOOM = "font.zoom";
    public static final String SCROLLBACK_LINES = "terminal.scrollback";
    public static final String CURSOR_SHAPE = "terminal.cursorShape";
    public static final String ALT_IS_META = "input.altIsMeta";
    public static final String SHELL = "terminal.shell";
    public static final String PROFILE_DEFAULT = "profiles.default";
    public static final String PROFILE_HIDDEN = "profiles.hidden";
    /** Everything under here belongs to {@code com.termina.shell}; see {@link #profileBlock()}. */
    public static final String PROFILE_PREFIX = "profile.";

    public static final String BELL = "terminal.bell";
    public static final String HIDE_TAB_BAR_WHEN_SINGLE = "ui.hideTabBarWhenSingle";
    public static final String SHOW_MENU_BAR = "ui.showMenuBar";
    public static final String SHOW_SCROLL_BAR = "ui.showScrollBar";
    public static final String CONFIRM_CLOSE = "ui.confirmClose";
    public static final String WINDOW_WIDTH = "window.width";
    public static final String WINDOW_HEIGHT = "window.height";
    public static final String WINDOW_MAXIMIZED = "window.maximized";
    public static final String WINDOW_OPACITY = "window.opacity";
    public static final String UI_LANGUAGE = "ui.language";
    public static final String LINK_OPEN_COMMAND = "links.openCommand";
    public static final String UPDATE_CHECK = "updates.check";
    public static final String LAST_UPDATE_CHECK = "updates.lastCheckEpochMs";
    public static final String DISMISSED_UPDATE = "updates.dismissedVersion";

    /** Cursor appearance. Names match the DECSCUSR families a program can also request. */
    public enum CursorShape {
        BLOCK,
        UNDERLINE,
        BAR
    }

    /** Empty means "pick the best available monospace face for this platform". */
    public static final String DEFAULT_FONT_FAMILY = "";

    public static final double DEFAULT_FONT_SIZE = 13;
    public static final double DEFAULT_FONT_ZOOM = 1.0;
    public static final double MIN_FONT_ZOOM = 0.3;
    public static final double MAX_FONT_ZOOM = 3.0;
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
        return defaultFile("");
    }

    /**
     * @param override a directory from {@code --config-dir}, taking precedence over the environment
     *     variable, which in turn takes precedence over the standard location
     */
    public static Path defaultFile(String override) {
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).resolve("settings.properties");
        }
        return defaultFileFromEnvironment();
    }

    private static Path defaultFileFromEnvironment() {
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
            System.getLogger(Settings.class.getName()).log(System.Logger.Level.WARNING, "could not write " + file, e);
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

    /**
     * The zoom multiplier, which is not the font size.
     *
     * <p>Kept apart so that 100% means the size the user chose rather than the one shipped, and so
     * that resetting the zoom returns to their preference instead of overwriting it. They were one
     * number until the zoom row needed a percentage to show.
     */
    public double fontZoom() {
        return clamp(readDouble(FONT_ZOOM, DEFAULT_FONT_ZOOM), MIN_FONT_ZOOM, MAX_FONT_ZOOM);
    }

    public void setFontZoom(double zoom) {
        put(FONT_ZOOM, String.valueOf(clamp(zoom, MIN_FONT_ZOOM, MAX_FONT_ZOOM)));
    }

    /**
     * What the terminal is actually rendered at. Clamped to the font-size range as well as the
     * zoom range: three times a large preferred size is past anything worth laying out.
     */
    public double effectiveFontSize() {
        return clamp(fontSize() * fontZoom(), MIN_FONT_SIZE, MAX_FONT_SIZE);
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

    /**
     * Which profile a new tab opens as. Blank means the first one, which is the system shell.
     *
     * <p>An id rather than an index, so that installing a shell — which changes what discovery
     * finds and therefore where everything sits in the list — does not silently change which
     * profile the {@code +} button opens.
     */
    public String defaultProfileId() {
        return properties.getProperty(PROFILE_DEFAULT, "");
    }

    public void setDefaultProfileId(String id) {
        put(PROFILE_DEFAULT, id == null ? "" : id.trim());
    }

    /** Comma-separated ids of discovered profiles to leave out of the menu. */
    public String hiddenProfileIds() {
        return properties.getProperty(PROFILE_HIDDEN, "");
    }

    public void setHiddenProfileIds(String ids) {
        put(PROFILE_HIDDEN, ids == null ? "" : ids.trim());
    }

    /**
     * Every {@code profile.*} key, for the package that owns their shape.
     *
     * <p>Settings holds the storage and {@code com.termina.shell} holds the format. The alternative
     * — typed accessors here returning profiles — would have the configuration package and the
     * shell package each importing the other, to describe a block of keys only one of them ever
     * reads.
     */
    public java.util.Map<String, String> profileBlock() {
        java.util.Map<String, String> block = new java.util.LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(PROFILE_PREFIX)) block.put(name, properties.getProperty(name));
        }
        return block;
    }

    /**
     * Replaces every {@code profile.*} key at once.
     *
     * <p>One write and one change broadcast for the whole list. Setting them one at a time would
     * save the file and re-apply every setting to every terminal in every window per key, which for
     * a handful of profiles is dozens of full re-applications for one edit.
     */
    public void replaceProfileBlock(java.util.Map<String, String> block) {
        java.util.Map<String, String> previous = profileBlock();
        java.util.Map<String, String> next = block == null ? java.util.Map.of() : block;
        if (previous.equals(next)) return;
        for (String name : previous.keySet()) properties.remove(name);
        for (java.util.Map.Entry<String, String> entry : next.entrySet()) {
            if (entry.getValue() != null) properties.setProperty(entry.getKey(), entry.getValue());
        }
        save();
        onChange.run();
    }

    /**
     * What to run to open a clicked file. Blank means the desktop's own opener.
     *
     * <p>Blank by default because a terminal has no business deciding which application owns a
     * user's files. Set to something like {@code editora {file}:{line}} and a clicked stack-trace
     * frame lands on the line it names — which the desktop opener cannot do, since {@code open} and
     * {@code xdg-open} take a file and nothing else.
     */
    public String linkOpenCommand() {
        return properties.getProperty(LINK_OPEN_COMMAND, "");
    }

    public void setLinkOpenCommand(String command) {
        put(LINK_OPEN_COMMAND, command == null ? "" : command.trim());
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

    /**
     * Show the in-window menu bar.
     *
     * <p>No effect on macOS, where the menus belong to the screen menu bar and there is nothing in
     * the window to hide.
     */
    public boolean showMenuBar() {
        return readBoolean(SHOW_MENU_BAR, true);
    }

    public void setShowMenuBar(boolean show) {
        put(SHOW_MENU_BAR, String.valueOf(show));
    }

    /** Show a scrollbar for the scrollback. The wheel works either way. */
    /**
     * Whether to ask before closing a tab that is running something.
     *
     * <p>On by default: the prompt only appears when a shell has children, so it is rare enough to
     * be worth reading. Off for anyone who would rather not be asked — the same preference macOS
     * Terminal offers, and the polite thing to give someone alongside a new interruption.
     */
    public boolean confirmClose() {
        return readBoolean(CONFIRM_CLOSE, true);
    }

    public void setConfirmClose(boolean confirm) {
        put(CONFIRM_CLOSE, String.valueOf(confirm));
    }

    public boolean showScrollBar() {
        return readBoolean(SHOW_SCROLL_BAR, true);
    }

    public void setShowScrollBar(boolean show) {
        put(SHOW_SCROLL_BAR, String.valueOf(show));
    }

    /** Width a new window opens at, or 0 when none has been recorded yet. */
    public double windowWidth() {
        return readDouble(WINDOW_WIDTH, 0);
    }

    public double windowHeight() {
        return readDouble(WINDOW_HEIGHT, 0);
    }

    public boolean windowMaximized() {
        return readBoolean(WINDOW_MAXIMIZED, false);
    }

    /**
     * Records the geometry a window was last left at.
     *
     * <p>Written directly instead of through {@code put}, deliberately: that fires the change
     * broadcast, which re-applies every setting to every terminal in every window. Geometry is not
     * something the UI reacts to, and a resize is not something to repaint the whole app over.
     */
    public void setWindowGeometry(double width, double height, boolean maximized) {
        properties.setProperty(WINDOW_WIDTH, String.valueOf(Math.round(width)));
        properties.setProperty(WINDOW_HEIGHT, String.valueOf(Math.round(height)));
        properties.setProperty(WINDOW_MAXIMIZED, String.valueOf(maximized));
        save();
    }

    /** How see-through a terminal window is, 0.6 to 1.0. Clamped by the caller. */
    public double windowOpacity() {
        return readDouble(WINDOW_OPACITY, 1.0);
    }

    public void setWindowOpacity(double opacity) {
        put(WINDOW_OPACITY, String.valueOf(opacity));
    }

    /** UI language code, or blank for "follow the system". */
    public String uiLanguage() {
        return properties.getProperty(UI_LANGUAGE, "");
    }

    public void setUiLanguage(String code) {
        put(UI_LANGUAGE, code == null ? "" : code);
    }

    /** Check GitHub for a newer release at startup, at most once a day. */
    public boolean updateCheck() {
        return readBoolean(UPDATE_CHECK, true);
    }

    public void setUpdateCheck(boolean check) {
        put(UPDATE_CHECK, String.valueOf(check));
    }

    public long lastUpdateCheck() {
        return (long) readDouble(LAST_UPDATE_CHECK, 0);
    }

    public void setLastUpdateCheck(long epochMs) {
        put(LAST_UPDATE_CHECK, String.valueOf(epochMs));
    }

    /** The version the user has already been shown and acted on. */
    public String dismissedUpdate() {
        return properties.getProperty(DISMISSED_UPDATE, "");
    }

    public void setDismissedUpdate(String version) {
        put(DISMISSED_UPDATE, version == null ? "" : version);
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
