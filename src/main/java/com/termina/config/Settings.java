package com.termina.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Properties;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * User preferences, stored as a plain properties file in {@code ~/.termina/settings.properties}.
 *
 * <p>Properties rather than JSON or TOML because it needs no dependency, and because a terminal's
 * settings file is one someone will reasonably open in a text editor. Every getter falls back to a
 * default, so a hand-edited file with a typo degrades to the default for that one key instead of
 * failing to load. The syntax is properties with backslashes taken literally, in UTF-8 — see
 * {@link SettingsFormat} — so a Windows path can be typed as it is.
 */
public final class Settings {

    public static final String THEME = "theme";
    public static final String FONT_FAMILY = "font.family";
    public static final String FONT_SIZE = "font.size";
    public static final String FONT_ZOOM = "font.zoom";
    public static final String SCROLLBACK_LINES = "terminal.scrollback";
    public static final String CURSOR_SHAPE = "terminal.cursorShape";
    public static final String ALT_IS_META = "input.altIsMeta";
    public static final String CONFIRM_PASTE = "input.confirmPaste";
    /** Whether a selection is copied to the clipboard as it is made, without asking. */
    public static final String COPY_ON_SELECT = "input.copyOnSelect";
    /** Whether programs may set the clipboard with OSC 52. Never read it: that is not offered at all. */
    public static final String CLIPBOARD_WRITE = "terminal.clipboardWrite";
    /** Whether keystrokes are predicted while SSH is in the foreground and its echo is slow. */
    public static final String TYPE_AHEAD = "terminal.typeAhead";
    /**
     * The user's key bindings, one key per command: {@code keys.newTab=Ctrl+Shift+Y}, in {@code
     * KeyCombination} notation. See {@link #keyBindings()}.
     */
    public static final String KEYS_PREFIX = "keys.";
    /** A binding's value meaning "no chord at all", as against blank or absent, which is the default. */
    public static final String UNBOUND = "none";

    public static final String SHELL = "terminal.shell";
    public static final String PROFILE_DEFAULT = "profiles.default";
    public static final String PROFILE_HIDDEN = "profiles.hidden";
    /** Everything under here belongs to {@code com.termina.shell}; see {@link #profileBlock()}. */
    public static final String PROFILE_PREFIX = "profile.";

    public static final String BELL = "terminal.bell";
    /** Whether a program's notification (OSC 9, OSC 777) reaches the desktop. The tab is marked either way. */
    public static final String NOTIFICATIONS = "terminal.notifications";

    public static final String HIDE_TAB_BAR_WHEN_SINGLE = "ui.hideTabBarWhenSingle";
    public static final String SHOW_MENU_BAR = "ui.showMenuBar";
    public static final String MENU_BAR_IN_TITLE_BAR = "window.menuBarInTitleBar";
    public static final String SHOW_SCROLL_BAR = "ui.showScrollBar";
    public static final String CONFIRM_CLOSE = "ui.confirmClose";
    public static final String WINDOW_X = "window.x";
    public static final String WINDOW_Y = "window.y";
    public static final String WINDOW_WIDTH = "window.width";
    public static final String WINDOW_HEIGHT = "window.height";
    public static final String WINDOW_MAXIMIZED = "window.maximized";
    public static final String WINDOW_OPACITY = "window.opacity";
    public static final String UI_LANGUAGE = "ui.language";
    public static final String LINK_OPEN_COMMAND = "links.openCommand";
    public static final String UPDATE_CHECK = "updates.check";
    public static final String LAST_UPDATE_CHECK = "updates.lastCheckEpochMs";
    public static final String DISMISSED_UPDATE = "updates.dismissedVersion";
    /** Everything under here belongs to settings sync; see {@link #isSyncKey}. */
    public static final String SYNC_PREFIX = "sync.";

    public static final String SYNC_ENABLED = "sync.enabled";
    public static final String SYNC_REPOSITORY = "sync.repository";
    public static final String SYNC_BRANCH = "sync.branch";
    public static final String SYNC_AUTO = "sync.auto";
    public static final String SYNC_INTERVAL = "sync.intervalMinutes";

    public static final String DEFAULT_SYNC_BRANCH = "main";
    public static final int DEFAULT_SYNC_INTERVAL = 15;
    public static final int MIN_SYNC_INTERVAL = 5;
    public static final int MAX_SYNC_INTERVAL = 24 * 60;

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

    /**
     * The furthest a remembered window position is believed. Nothing real sits a hundred thousand
     * pixels from the origin; a value past that is a corrupt line, and restoring it would open the
     * window somewhere no screen is.
     */
    public static final double MAX_WINDOW_COORDINATE = 100_000;

    private final Path file;
    private final Properties properties = new Properties();
    private final List<Runnable> onChange = new ArrayList<>();

    /** What the file held when this process last loaded or saved it: the base a save diffs against. */
    private volatile Map<String, String> baseline = Map.of();

    /** One per settings file in this process; a {@link FileLock} does not exclude its own JVM. */
    private static final java.util.concurrent.ConcurrentMap<Path, Object> MONITORS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Why the last save failed, or null when it succeeded. */
    private volatile @Nullable String lastSaveError;

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

    /**
     * Notified after any change, so the UI can re-apply live.
     *
     * <p>A list, not a slot. The first consumer was the window manager, re-applying to every
     * terminal; the settings window is the second, reloading its controls when a chord or the
     * palette changes something while it is open. A single-consumer setter had the second silently
     * replace the first.
     */
    public void addOnChange(Runnable listener) {
        if (listener != null) onChange.add(listener);
    }

    public void removeOnChange(Runnable listener) {
        onChange.remove(listener);
    }

    private void fireChange() {
        for (Runnable listener : List.copyOf(onChange)) listener.run();
    }

    /**
     * Reads the file, if there is one. Never throws: whatever goes wrong, the app opens on defaults.
     *
     * <p>Settings are a convenience, and a terminal that will not open is not one. The format itself
     * cannot fail ({@link SettingsFormat}); what is left is a file that cannot be read at all, or a
     * bug. Either way the file is copied aside first, as {@code settings.properties.bad-<time>}, so
     * the next save — which writes only the keys this process changes, but still writes — cannot be
     * the end of whatever the user had in it.
     */
    public void load() {
        if (!Files.exists(file)) return;
        try {
            Map<String, String> read = SettingsFormat.parse(SettingsFormat.decode(Files.readAllBytes(file)));
            read.forEach(properties::setProperty);
        } catch (IOException | RuntimeException e) {
            Path backup = backUpUnreadable(file, System.currentTimeMillis());
            System.getLogger(Settings.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "could not read " + file + "; using defaults"
                                    + (backup == null ? "" : "; the file is kept as " + backup),
                            e);
        }
        baseline = snapshot();
    }

    /**
     * Copies a settings file that failed to load beside itself, named for when.
     *
     * @return the copy, or null when even that failed (the warning still names the original)
     */
    static @Nullable Path backUpUnreadable(Path file, long epochMs) {
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMs));
        Path backup = file.resolveSibling(file.getFileName() + ".bad-" + stamp);
        try {
            return Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Writes this process's changes into the file — only those — through a temporary and a rename.
     *
     * <p>Only the changes, because more than one Termina can be running on one config folder, and
     * each used to rewrite the whole file from its own memory on every change and on every window
     * close: change the theme in one, close a window of the other, and the theme was back. So a save
     * takes a lock (a {@link FileLock} on {@code settings.properties.lock}, plus a monitor for two
     * {@code Settings} in one process, which a file lock does not separate), re-reads the file,
     * applies what differs between memory and what was last loaded or saved — set or removed — and
     * writes the result. Keys another process changed meanwhile stay as it left them; this process
     * does not adopt them until it next loads.
     *
     * <p>Through a temporary because rewriting in place truncates first and fills afterwards, and a
     * kill or a power cut between the two leaves an empty file — which the next launch reads as "no
     * settings", profiles included, without a word. The temporary's name is unique to the save, so
     * two writers never share one. The rename is atomic where the filesystem offers it and a plain
     * replace where it does not, which still leaves either the whole new file or the old one.
     */
    public synchronized void save() {
        Path temp = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent == null) throw new IOException("no folder to write " + file + " into");
            Files.createDirectories(parent);
            Object monitor = MONITORS.computeIfAbsent(file.toAbsolutePath().normalize(), k -> new Object());
            synchronized (monitor) {
                try (FileChannel lockChannel = FileChannel.open(
                                lockFileFor(file), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        FileLock held = lockChannel.lock()) {
                    Map<String, String> mine = snapshot();
                    Map<String, String> merged = merge(readOnDisk(), baseline, mine);
                    temp = Files.createTempFile(parent, file.getFileName() + ".", ".tmp");
                    Files.writeString(temp, SettingsFormat.format(merged), StandardCharsets.UTF_8);
                    moveIntoPlace(temp, file);
                    temp = null;
                    baseline = mine;
                }
            }
            lastSaveError = null;
        } catch (IOException | RuntimeException e) {
            lastSaveError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.getLogger(Settings.class.getName()).log(System.Logger.Level.WARNING, "could not write " + file, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // The leftover temporary is the lesser problem; the warning above names the file.
                }
            }
        }
    }

    /** What the file holds now, or what this process last knew it to hold if it cannot be read. */
    private Map<String, String> readOnDisk() {
        if (!Files.exists(file)) return new java.util.HashMap<>();
        try {
            return SettingsFormat.parse(SettingsFormat.decode(Files.readAllBytes(file)));
        } catch (IOException | RuntimeException e) {
            return new java.util.HashMap<>(baseline);
        }
    }

    /**
     * The file to write: what is on disk, with every key that differs between {@code base} (what this
     * process last loaded or saved) and {@code mine} (its memory now) set to {@code mine}'s value, or
     * removed where {@code mine} has none. Everything else on disk is left as found.
     */
    static Map<String, String> merge(Map<String, String> onDisk, Map<String, String> base, Map<String, String> mine) {
        Map<String, String> merged = new java.util.HashMap<>(onDisk);
        Set<String> keys = new java.util.HashSet<>(base.keySet());
        keys.addAll(mine.keySet());
        for (String key : keys) {
            String now = mine.get(key);
            if (java.util.Objects.equals(now, base.get(key))) continue;
            if (now == null) merged.remove(key);
            else merged.put(key, now);
        }
        return merged;
    }

    private Map<String, String> snapshot() {
        Map<String, String> copy = new java.util.HashMap<>();
        for (String name : properties.stringPropertyNames()) copy.put(name, properties.getProperty(name));
        return copy;
    }

    /** Where saves queue up between processes: beside the file, never replaced, never deleted. */
    static Path lockFileFor(Path file) {
        return file.resolveSibling(file.getFileName() + ".lock");
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * What went wrong the last time the file was written, or null when it was written.
     *
     * <p>Exposed because a control showing the new value while the file still holds the old one is
     * a lie the user discovers at the next launch. The settings window puts this on the row that
     * names the file.
     */
    public @Nullable String lastSaveError() {
        return lastSaveError;
    }

    public Path file() {
        return file;
    }

    /** The folder the settings file is in, which also holds what settings sync keeps on this computer. */
    public Path directory() {
        Path parent = file.toAbsolutePath().getParent();
        return parent == null ? Path.of(".") : parent;
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
        if (!Double.isFinite(size)) return;
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
        if (!Double.isFinite(zoom)) return;
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

    /**
     * The user's key bindings, by command id with the prefix stripped: {@code newTab -> Ctrl+Shift+Y},
     * or {@code newTab -> none} for a command the user has unbound. A command that is absent, or
     * whose line is blank, keeps its default.
     *
     * <p>Strings rather than chords, because this class knows nothing of the toolkit. Nothing is
     * validated here: the window resolves the whole table at once, and a line that does not parse,
     * names a command that does not exist, or takes a chord another line already took is dropped
     * there — clamped on read, like every other setting, because the file is hand-edited.
     *
     * <p>Keyed by command rather than by chord, unlike Editora's, so a hand-edited file reads as a
     * list of what each command is on. {@code keys.commandPalette} predates the rest and is the
     * palette's line unchanged.
     */
    public java.util.Map<String, String> keyBindings() {
        java.util.Map<String, String> bindings = new java.util.TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(KEYS_PREFIX) || name.length() == KEYS_PREFIX.length()) continue;
            String value = properties.getProperty(name).trim();
            if (!value.isEmpty()) bindings.put(name.substring(KEYS_PREFIX.length()), value);
        }
        return bindings;
    }

    /**
     * Replaces every binding at once — one write and one change broadcast, because a rebind that
     * takes a chord from another command changes two lines, and two broadcasts would re-apply the
     * table in between with the chord on both.
     */
    public void replaceKeyBindings(java.util.Map<String, String> bindings) {
        java.util.Map<String, String> next = new java.util.TreeMap<>();
        if (bindings != null) {
            bindings.forEach((id, chord) -> {
                if (id != null && !id.isBlank() && chord != null && !chord.isBlank()) next.put(id.trim(), chord.trim());
            });
        }
        if (next.equals(keyBindings())) return;
        properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith(KEYS_PREFIX))
                .forEach(properties::remove);
        next.forEach((id, chord) -> properties.setProperty(KEYS_PREFIX + id, chord));
        save();
        fireChange();
    }

    /** Whether a paste containing a line break asks first. On by default: at a prompt every line runs. */
    public boolean confirmPaste() {
        return readBoolean(CONFIRM_PASTE, true);
    }

    public void setConfirmPaste(boolean confirmPaste) {
        put(CONFIRM_PASTE, String.valueOf(confirmPaste));
    }

    /**
     * Whether a selection goes to the clipboard as it is made. Off by default: it replaces whatever
     * the clipboard held with every highlight, which surprises anyone who has not asked for it. On
     * Linux the selection is PRIMARY either way, which is the middle-click half of the same habit.
     */
    public boolean copyOnSelect() {
        return readBoolean(COPY_ON_SELECT, false);
    }

    public void setCopyOnSelect(boolean copyOnSelect) {
        put(COPY_ON_SELECT, String.valueOf(copyOnSelect));
    }

    public boolean clipboardWrite() {
        return readBoolean(CLIPBOARD_WRITE, true);
    }

    public void setClipboardWrite(boolean allowed) {
        put(CLIPBOARD_WRITE, String.valueOf(allowed));
    }

    public boolean typeAhead() {
        return readBoolean(TYPE_AHEAD, true);
    }

    public void setTypeAhead(boolean enabled) {
        put(TYPE_AHEAD, String.valueOf(enabled));
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
        fireChange();
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

    /** Whether programs may post desktop notifications. On: a program asks only when it has news. */
    public boolean notifications() {
        return readBoolean(NOTIFICATIONS, true);
    }

    public void setNotifications(boolean notifications) {
        put(NOTIFICATIONS, String.valueOf(notifications));
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

    /**
     * Put the menu bar in the title bar: one row holds the menus, the window title and the window
     * buttons, and the terminal gains the row the native title bar took.
     *
     * <p>Off by default. Applies to windows opened after the change — a stage's style is fixed
     * when it is first shown — and never on macOS, where the menus are in the screen menu bar.
     */
    public boolean menuBarInTitleBar() {
        return readBoolean(MENU_BAR_IN_TITLE_BAR, false);
    }

    public void setMenuBarInTitleBar(boolean inTitleBar) {
        put(MENU_BAR_IN_TITLE_BAR, String.valueOf(inTitleBar));
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

    /**
     * Where a window was last left, or empty when no position has been recorded.
     *
     * <p>An {@code OptionalDouble} rather than a sentinel: unlike a size, where zero cannot be a
     * real value, zero is a perfectly good position — the corner of the primary screen. A value out
     * of range or not a number reads as unset, so a corrupt line opens the window where a fresh
     * install would rather than off every screen.
     */
    public OptionalDouble windowX() {
        return readCoordinate(WINDOW_X);
    }

    public OptionalDouble windowY() {
        return readCoordinate(WINDOW_Y);
    }

    private OptionalDouble readCoordinate(String key) {
        if (properties.getProperty(key) == null) return OptionalDouble.empty();
        double value = readDouble(key, Double.NaN);
        return isSaneCoordinate(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    /** Whether a window coordinate is one worth remembering or restoring. */
    static boolean isSaneCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_WINDOW_COORDINATE;
    }

    public boolean windowMaximized() {
        return readBoolean(WINDOW_MAXIMIZED, false);
    }

    /**
     * Records the geometry a window was last left at.
     *
     * <p>Written quietly, deliberately: the change broadcast re-applies every setting to every
     * terminal in every window. Geometry is not something the UI reacts to, and a resize is not
     * something to repaint the whole app over.
     */
    public void setWindowGeometry(double width, double height, boolean maximized) {
        properties.setProperty(WINDOW_WIDTH, String.valueOf(Math.round(width)));
        properties.setProperty(WINDOW_HEIGHT, String.valueOf(Math.round(height)));
        properties.setProperty(WINDOW_MAXIMIZED, String.valueOf(maximized));
        save();
    }

    /** Size and position in one write. A position that is not sane is left unrecorded. */
    public void setWindowGeometry(double x, double y, double width, double height, boolean maximized) {
        if (isSaneCoordinate(x) && isSaneCoordinate(y)) {
            properties.setProperty(WINDOW_X, String.valueOf(Math.round(x)));
            properties.setProperty(WINDOW_Y, String.valueOf(Math.round(y)));
        }
        setWindowGeometry(width, height, maximized);
    }

    /** How see-through a terminal window is, 0.6 to 1.0. Clamped by the caller. */
    public double windowOpacity() {
        return readDouble(WINDOW_OPACITY, 1.0);
    }

    public void setWindowOpacity(double opacity) {
        if (!Double.isFinite(opacity)) return;
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

    /** Bookkeeping, so quiet: nothing on screen depends on when the last check ran. */
    public void setLastUpdateCheck(long epochMs) {
        putQuietly(LAST_UPDATE_CHECK, String.valueOf(epochMs));
    }

    /** The version the user has already been shown and acted on. */
    public String dismissedUpdate() {
        return properties.getProperty(DISMISSED_UPDATE, "");
    }

    public void setDismissedUpdate(String version) {
        putQuietly(DISMISSED_UPDATE, version == null ? "" : version);
    }

    // ---------------------------------------------------------------- settings sync

    /** Whether settings sync is connected. It also needs a usable repository; see {@code SettingsSync}. */
    public boolean syncEnabled() {
        return readBoolean(SYNC_ENABLED, false);
    }

    public void setSyncEnabled(boolean enabled) {
        put(SYNC_ENABLED, String.valueOf(enabled));
    }

    /** The repository's URL, as git takes it; blank when none was entered. */
    public String syncRepository() {
        return properties.getProperty(SYNC_REPOSITORY, "").trim();
    }

    public void setSyncRepository(String url) {
        put(SYNC_REPOSITORY, url == null ? "" : url.trim());
    }

    /** The branch the synced files live on. Blank reads as {@value #DEFAULT_SYNC_BRANCH}. */
    public String syncBranch() {
        String branch = properties.getProperty(SYNC_BRANCH, "").trim();
        return branch.isEmpty() ? DEFAULT_SYNC_BRANCH : branch;
    }

    public void setSyncBranch(String branch) {
        put(SYNC_BRANCH, branch == null ? "" : branch.trim());
    }

    /** Sync after startup, shortly after a change, and every {@link #syncIntervalMinutes()}. */
    public boolean syncAuto() {
        return readBoolean(SYNC_AUTO, true);
    }

    public void setSyncAuto(boolean auto) {
        put(SYNC_AUTO, String.valueOf(auto));
    }

    /** Clamped: a one-minute interval is a fetch a minute against someone's repository host. */
    public int syncIntervalMinutes() {
        return (int) clamp(readInt(SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL), MIN_SYNC_INTERVAL, MAX_SYNC_INTERVAL);
    }

    public void setSyncIntervalMinutes(int minutes) {
        put(SYNC_INTERVAL, String.valueOf((int) clamp(minutes, MIN_SYNC_INTERVAL, MAX_SYNC_INTERVAL)));
    }

    /**
     * Puts everything back to its default — except the profiles.
     *
     * <p>Hand-written profiles are hours of someone's work, and "Reset to Defaults" is a button
     * people press to get a font back. The chosen default and the hidden ids go with them, because
     * a kept profile that is no longer the default, or that reappears in the menu, is the same
     * surprise in smaller print. The rule is {@link #keysToReset}.
     *
     * <p>Settings sync stays connected for the same reason: a reset is about this computer's
     * preferences, and quietly disconnecting it from the repository is not something anyone asks a
     * font reset for. The key bindings it clears are a change like any other, and sync sends it.
     */
    public void resetToDefaults() {
        for (String key : keysToReset(properties.stringPropertyNames())) properties.remove(key);
        save();
        fireChange();
    }

    /** Which of the stored keys a reset removes: everything but the profiles, their two keys and sync. */
    public static Set<String> keysToReset(Set<String> stored) {
        Set<String> reset = new LinkedHashSet<>();
        for (String key : stored) {
            if (!isProfileKey(key) && !isSyncKey(key)) reset.add(key);
        }
        return reset;
    }

    /** The keys that connect settings sync, which a reset leaves alone. */
    public static boolean isSyncKey(String key) {
        return key.startsWith(SYNC_PREFIX);
    }

    /** The keys that belong to the user's profiles, which a reset leaves alone. */
    public static boolean isProfileKey(String key) {
        return key.startsWith(PROFILE_PREFIX)
                || key.startsWith(PROFILE_STYLE_PREFIX)
                || PROFILE_DEFAULT.equals(key)
                || PROFILE_HIDDEN.equals(key);
    }

    /**
     * A profile's own font and theme, by profile id: {@code profileStyle.<id>.font}, {@code .fontSize}
     * and {@code .theme}. Apart from the {@code profile.N.*} block because any profile can have one —
     * the system shell and the discovered ones too, which are not stored at all — and spared by a reset
     * with the profiles they belong to.
     */
    public static final String PROFILE_STYLE_PREFIX = "profileStyle.";

    /** What a profile overrides; a blank or null part follows the global setting. */
    public record ProfileStyle(String fontFamily, @Nullable Double fontSize, String themeId) {
        public static final ProfileStyle NONE = new ProfileStyle("", null, "");

        public ProfileStyle {
            fontFamily = fontFamily == null ? "" : fontFamily.strip();
            themeId = themeId == null ? "" : themeId.strip();
            if (fontSize != null && (fontSize.isNaN() || fontSize.isInfinite())) fontSize = null;
            if (fontSize != null) fontSize = clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
        }

        public boolean isEmpty() {
            return fontFamily.isEmpty() && fontSize == null && themeId.isEmpty();
        }
    }

    /**
     * The size a profile's pane draws at: its own size if it has one, else the global one, either way
     * through the global zoom — zooming is the window's gesture and applies to every pane alike.
     */
    public double effectiveFontSize(ProfileStyle style) {
        double base = style == null || style.fontSize() == null ? fontSize() : style.fontSize();
        return clamp(base * fontZoom(), MIN_FONT_SIZE, MAX_FONT_SIZE);
    }

    /** A profile's style, clamped as it is read: the file is hand-edited. */
    public ProfileStyle profileStyle(String profileId) {
        if (profileId == null || profileId.isBlank()) return ProfileStyle.NONE;
        String prefix = PROFILE_STYLE_PREFIX + profileId + ".";
        String size = properties.getProperty(prefix + "fontSize");
        Double fontSize = null;
        if (size != null && !size.isBlank()) {
            try {
                fontSize = Double.parseDouble(size.strip());
            } catch (NumberFormatException garbled) {
                fontSize = null;
            }
        }
        return new ProfileStyle(
                properties.getProperty(prefix + "font"), fontSize, properties.getProperty(prefix + "theme"));
    }

    /** Stores a profile's style — an empty part removes its key — with one save and one broadcast. */
    public void setProfileStyle(String profileId, ProfileStyle style) {
        if (profileId == null || profileId.isBlank()) return;
        String prefix = PROFILE_STYLE_PREFIX + profileId + ".";
        ProfileStyle next = style == null ? ProfileStyle.NONE : style;
        boolean changed = setOrRemove(prefix + "font", next.fontFamily());
        changed |= setOrRemove(prefix + "fontSize", next.fontSize() == null ? "" : String.valueOf(next.fontSize()));
        changed |= setOrRemove(prefix + "theme", next.themeId());
        if (!changed) return;
        save();
        fireChange();
    }

    private boolean setOrRemove(String key, String value) {
        String previous = properties.getProperty(key);
        if (value.isEmpty()) return properties.remove(key) != null;
        if (value.equals(previous)) return false;
        properties.setProperty(key, value);
        return true;
    }

    // ---------------------------------------------------------------- internals

    private void put(String key, String value) {
        if (putQuietly(key, value)) fireChange();
    }

    /**
     * Stores and saves without the change broadcast.
     *
     * <p>For bookkeeping — timestamps, a dismissed version, geometry — that nothing on screen is
     * waiting to hear about: the broadcast rebuilds fonts and re-measures every terminal in every
     * window, and the first of these keys is written moments after the first window appears.
     *
     * @return whether anything changed
     */
    private boolean putQuietly(String key, String value) {
        String previous = properties.getProperty(key);
        if (value.equals(previous)) return false;
        properties.setProperty(key, value);
        save();
        return true;
    }

    /**
     * A finite number, or the fallback.
     *
     * <p>{@code Double.parseDouble} happily returns NaN and the infinities for the strings that
     * spell them, and a NaN clamps to itself — straight into {@code Font.font(family, NaN)}.
     */
    private double readDouble(String key, double fallback) {
        try {
            String raw = properties.getProperty(key);
            if (raw == null) return fallback;
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : fallback;
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

    /** NaN is between no two numbers; it lands on the floor rather than passing through. */
    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
