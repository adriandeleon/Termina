package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.termina.config.Settings;
import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/**
 * The open windows, and the state they share.
 *
 * <p>Settings, the settings window and the theme are app-wide: one preferences window rather than
 * one per terminal window, and a theme change that reaches every tab everywhere. Sessions are not
 * shared — each tab owns its own shell.
 */
public final class WindowManager {

    private final Settings settings;
    private final List<TerminalWindow> windows = new ArrayList<>();
    private @Nullable SettingsWindow settingsWindow;
    private @Nullable AboutWindow aboutWindow;

    private final com.termina.update.UpdateService updates = new com.termina.update.UpdateService();

    /**
     * The newer release the menus advertise, or null. Held app-wide so every window's Help menu
     * agrees. Cleared once the user has acted on it, so the item goes back to being the check.
     */
    private com.termina.update.@Nullable ReleaseInfo availableUpdate;

    /**
     * The newest release known of, whether or not the user has already acted on it. About shows
     * this one: a dismissed update is still a fact about this build, and "You are up to date" after
     * clicking through to the release page would be a lie.
     */
    private com.termina.update.@Nullable ReleaseInfo knownUpdate;

    private final List<Runnable> updateListeners = new ArrayList<>();
    private java.util.function.Consumer<String> openLink = url -> {};

    /**
     * The shell profiles, app-wide.
     *
     * <p>One list for the whole application rather than one per window: discovery starts a
     * subprocess on Windows, and doing that again for every window would be paying for the same
     * answer repeatedly. Started here so it is already running by the time a menu is opened.
     */
    private final com.termina.shell.ShellProfiles profiles;

    /** Keeps the key bindings the same on every computer connected to the user's repository. */
    private final SettingsSync sync;

    public WindowManager(Settings settings) {
        this.settings = settings;
        this.profiles = new com.termina.shell.ShellProfiles(settings, tr("profile.systemShell"));
        // Nothing to do when it finishes: every menu that lists profiles is built when it is shown,
        // so the next one to open picks up whatever discovery found. The callback exists because a
        // menu already on screen when the answer lands would otherwise keep the shorter list.
        profiles.discoverInBackground(found -> {});
        settings.addOnChange(this::applySettingsEverywhere);
        this.sync = new SettingsSync(settings, this::anyWindowFocused);
    }

    private boolean anyWindowFocused() {
        for (TerminalWindow window : windows) {
            if (window.stage().isFocused()) return true;
        }
        return settingsWindow != null && settingsWindow.scene().getWindow().isFocused();
    }

    public com.termina.shell.ShellProfiles profiles() {
        return profiles;
    }

    /** Opens the first window on the primary stage, so the app owns no spare empty stage. */
    public TerminalWindow openFirstWindow(Stage primary) {
        return openFirstWindow(primary, null);
    }

    /**
     * @param cli launch options from the command line, applied to this window's first tab only —
     *     every later tab and window uses the configured shell and the home directory
     */
    public TerminalWindow openFirstWindow(Stage primary, com.termina.pty.@Nullable LaunchOptions cli) {
        return openFirstWindow(primary, cli, "");
    }

    /** @param title names the first tab, as Rename Tab would ({@code -T}); "" leaves it automatic */
    public TerminalWindow openFirstWindow(Stage primary, com.termina.pty.@Nullable LaunchOptions cli, String title) {
        firstTabTitle = title;
        return open(primary, cli);
    }

    /** The command line's {@code -T}, spent on the first tab that opens. */
    private String firstTabTitle = "";

    /** Returns the {@code -T} title once, then "" — it names the first tab only. */
    private String takeFirstTabTitle() {
        String title = firstTabTitle;
        firstTabTitle = "";
        return title;
    }

    /** A new window, cascaded from the most recently opened one. */
    public void openWindow() {
        openWindow(windows.isEmpty() ? null : windows.get(windows.size() - 1));
    }

    /**
     * A new window, cascaded from {@code from} — the window whose menu or chord asked for it.
     *
     * <p>From that one rather than from the last opened: with three windows across two screens,
     * "New Window" from the first should land beside the first, not beside whichever was made most
     * recently. {@code from} may be null, in which case the window opens where the stage would on
     * its own.
     */
    public TerminalWindow openWindow(@Nullable TerminalWindow from) {
        return open(new Stage(), null, from, null);
    }

    private TerminalWindow open(Stage stage, com.termina.pty.@Nullable LaunchOptions cli) {
        return open(stage, cli, null, null);
    }

    /**
     * @param fill puts the window's first tabs in, instead of the default profile's tab — what
     *     reopening a closed window does; null for an ordinary new window
     */
    private TerminalWindow open(
            Stage stage,
            com.termina.pty.@Nullable LaunchOptions cli,
            @Nullable TerminalWindow from,
            java.util.function.@Nullable Consumer<TerminalWindow> fill) {
        TerminalWindow window = new TerminalWindow(this, settings, stage);
        windows.add(window);

        stage.setOnHidden(e -> {
            windows.remove(window);
            // The last window closing ends the app. Without this the JVM lingers: the settings
            // window is a Stage of its own and would keep the toolkit alive on its own.
            if (windows.isEmpty()) Platform.exit();
        });

        // Offset each additional window so it does not land exactly on the one it came from, and
        // keep it on that window's screen — the offset alone walked the title bar off the bottom.
        if (from != null && windows.contains(from)) {
            Stage origin = from.stage();
            double width = stage.getScene() != null ? stage.getScene().getWidth() : origin.getWidth();
            double height = stage.getScene() != null ? stage.getScene().getHeight() : origin.getHeight();
            double[] at = WindowPlacement.offsetWithin(
                    origin.getX(), origin.getY(), CASCADE_OFFSET, width, height, SecondaryWindows.screenOf(origin));
            stage.setX(at[0]);
            stage.setY(at[1]);
        }

        window.show();
        // A command line with something to run wins outright; otherwise the default profile decides
        // what a window opens as, which is the whole point of having chosen one.
        if (fill != null) {
            fill.accept(window);
        } else if (cli != null && cli.hasCommand()) {
            window.openTab(cli.withShell(settings.shell()), takeFirstTabTitle());
        } else {
            profiles.whenDefaultReady(profile -> {
                // A window closed while discovery was running must not spawn a session afterward.
                if (!windows.contains(window)
                        || !stage.isShowing()
                        || !window.terminals().isEmpty()) return;
                com.termina.pty.LaunchOptions options = profile == null
                        ? com.termina.pty.LaunchOptions.ofShell(settings.shell())
                        : profile.toLaunchOptions("");
                if (cli != null && !cli.workingDirectory().isBlank()) {
                    options = options.withWorkingDirectory(cli.workingDirectory());
                }
                window.openTab(options, takeFirstTabTitle());
            });
        }
        return window;
    }

    private static final double CASCADE_OFFSET = 28;

    /**
     * Applies the settings to every tab in every window.
     *
     * <p>Scrollback and the shell are deliberately absent: one sizes a buffer that already exists,
     * the other is a process already running. Both take effect in the next session, and the
     * settings window says so on those rows.
     */
    private void applySettingsEverywhere() {
        ThemeEntry theme = Themes.byId(settings.themeId());
        // The user-agent stylesheet is application-wide, so this restyles every window at once —
        // including the settings window that is probably the one being used to change it.
        Application.setUserAgentStylesheet(theme.stylesheet());
        for (TerminalWindow window : List.copyOf(windows)) window.applySettings();
    }

    /** How to open a URL. Supplied by App, which owns the JavaFX HostServices. */
    public void setLinkOpener(java.util.function.Consumer<String> openLink) {
        this.openLink = openLink == null ? url -> {} : openLink;
    }

    /** Opens a URL in the desktop's browser — the About window's links, and clicked ones. */
    void openLink(String url) {
        openLink.accept(url);
    }

    /** One About window for the application, shown beside whichever window asked. */
    public void showAbout(Window from) {
        AboutWindow about = aboutWindow();
        about.setUpdate(knownUpdate);
        about.show(from);
    }

    /** For the development capture hook. */
    public javafx.scene.Scene showAboutForCapture(Window owner) {
        showAbout(owner);
        return aboutWindow().scene();
    }

    /** The About window, made on first use. */
    private AboutWindow aboutWindow() {
        AboutWindow about = aboutWindow;
        if (about == null) aboutWindow = about = new AboutWindow(settings, openLink);
        return about;
    }

    // ---------------------------------------------------------------- updates

    /**
     * The startup check, if it is enabled and a day has passed.
     *
     * <p>The timestamp is stamped <em>before</em> the request, not after: otherwise a check that
     * fails — offline, say — leaves it unset and every launch retries.
     */
    public void maybeCheckForUpdates() {
        if (!settings.updateCheck()) return;
        long now = System.currentTimeMillis();
        if (!com.termina.update.UpdateCheck.isDue(
                settings.lastUpdateCheck(), now, com.termina.update.UpdateCheck.DEFAULT_INTERVAL_MS)) {
            return;
        }
        settings.setLastUpdateCheck(now);
        updates.check(com.termina.AppInfo.VERSION, outcome -> onUpdateOutcome(outcome, false));
    }

    /** The user asked. Ignores both the interval and the enable setting, and always reports. */
    public void checkForUpdatesNow(java.util.function.Consumer<String> report) {
        report.accept(tr("status.checkingForUpdates"));
        settings.setLastUpdateCheck(System.currentTimeMillis());
        updates.check(com.termina.AppInfo.VERSION, outcome -> {
            onUpdateOutcome(outcome, true);
            var error = outcome.error();
            var latest = outcome.latest();
            if (error != null) {
                report.accept(describeFailure(error, outcome.detail()));
            } else if (outcome.available() && latest != null) {
                report.accept(tr("status.updateAvailable", latest.version()));
            } else {
                report.accept(tr("status.upToDate", com.termina.AppInfo.NAME));
            }
        });
    }

    /**
     * A failed check in words. The exception's class name went into the dialog in the first
     * version, and {@code UnknownHostException} tells the person reading it nothing they can act on.
     */
    static String describeFailure(com.termina.update.UpdateCheck.ErrorKind kind, @Nullable String detail) {
        return switch (kind) {
            case OFFLINE -> tr("status.updateCheckOffline");
            case TIMEOUT -> tr("status.updateCheckTimeout");
            case HTTP -> tr("status.updateCheckRefused", detail);
            case OTHER -> tr("status.updateCheckFailed", detail);
        };
    }

    private void onUpdateOutcome(com.termina.update.UpdateService.Outcome outcome, boolean manual) {
        com.termina.update.ReleaseInfo latest = outcome.latest();
        // Available always comes with the release; the pair is checked so the type says so too.
        if (!outcome.available() || latest == null) return;
        knownUpdate = latest;
        if (aboutWindow != null) aboutWindow.setUpdate(latest);
        // A version the user has already been shown stays out of the menu, but a manual check
        // surfaces it again — they just asked.
        if (!manual && latest.version().equals(settings.dismissedUpdate())) return;
        availableUpdate = latest;
        notifyUpdateListeners();
    }

    private void notifyUpdateListeners() {
        for (Runnable listener : List.copyOf(updateListeners)) listener.run();
    }

    public com.termina.update.@Nullable ReleaseInfo availableUpdate() {
        return availableUpdate;
    }

    /** Notified when the advertised update changes — found, or dismissed — so a window can relabel its Help menu. */
    void addUpdateListener(Runnable listener) {
        updateListeners.add(listener);
    }

    /**
     * The other half, which a window calls as it goes. Without it every closed window stayed
     * reachable from here — stage, scene, palette, closed-tab history — for the life of the app,
     * and an update notice relabelled the Help menu of windows nobody could see.
     */
    void removeUpdateListener(Runnable listener) {
        updateListeners.remove(listener);
    }

    /** How many windows are listening for updates — for the capture hook, to see a closed one let go. */
    public int updateListenerCount() {
        return updateListeners.size();
    }

    // ---------------------------------------------------------------- closed windows

    /** Windows closed on purpose, most recent first, so Reopen Closed Tab can bring one back. */
    private final java.util.Deque<ClosedTabs.Window> closedWindows = new java.util.ArrayDeque<>();

    /** A few: a window holds every one of its tabs' directories. */
    static final int MAX_CLOSED_WINDOWS = 5;

    /**
     * The clock closed tabs and closed windows are stamped with. One for the application, so a tab
     * closed in one window and a window closed later can be put in order — Reopen undoes the most
     * recent, wherever it was.
     */
    private long closeClock;

    long nextCloseStamp() {
        return ++closeClock;
    }

    /** Remembers a window's tabs as it closes; nothing when it had none worth reopening. */
    void rememberClosedWindow(ClosedTabs.Window window) {
        if (window.tabs().isEmpty()) return;
        closedWindows.push(window);
        while (closedWindows.size() > MAX_CLOSED_WINDOWS) closedWindows.removeLast();
    }

    /** When the most recently closed window closed, or -1 when none can be reopened. */
    long newestClosedWindowStamp() {
        ClosedTabs.Window newest = closedWindows.peek();
        return newest == null ? -1 : newest.stamp();
    }

    /**
     * Opens the most recently closed window again, beside {@code from}: each tab where it was, the
     * same one selected. Its tabs go into a window of their own rather than into {@code from} — they
     * were a window, and closing it was the thing being undone.
     */
    void reopenClosedWindow(@Nullable TerminalWindow from) {
        ClosedTabs.Window closed = closedWindows.poll();
        if (closed == null) return;
        open(new Stage(), null, from, window -> window.reopenTabs(closed));
    }

    /** Opens the release page and stops advertising the version, since the user has now seen it. */
    public void openReleasePage() {
        String url = availableUpdate == null || availableUpdate.url().isBlank()
                ? com.termina.AppInfo.RELEASES_PAGE
                : availableUpdate.url();
        dismissCurrentUpdate();
        openLink.accept(url);
    }

    /**
     * Takes the advertised update down: the Help item returns to "Check for Updates…" and the
     * version is remembered so the next startup check does not put it back. Until this existed the
     * item read "Version X is available…" until restart, and a manual check was unreachable.
     */
    public void dismissCurrentUpdate() {
        if (availableUpdate == null) return;
        settings.setDismissedUpdate(availableUpdate.version());
        availableUpdate = null;
        notifyUpdateListeners();
    }

    /**
     * One settings window for the whole application, shown beside whichever window asked.
     *
     * <p>Not owned by that window — an owned stage closes with its owner, and the owner cannot be
     * changed after the first show, so the window would have died with the first terminal window
     * to open it. See {@link SecondaryWindows}.
     */
    public void showSettings(Window from) {
        settingsWindow().show(from);
    }

    /** The settings window, made on first use. */
    private SettingsWindow settingsWindow() {
        SettingsWindow window = settingsWindow;
        if (window == null) {
            // Every window has the same commands, so the first one answers for all of them.
            settingsWindow = window = new SettingsWindow(
                    settings,
                    profiles,
                    windows.isEmpty() ? List.of() : windows.get(0).keyboardCommands(),
                    sync);
        }
        return window;
    }

    /**
     * Sync Now from a menu or the palette. {@code report} hears that it started and how it went, as
     * the update check's does, because the Sync page that would otherwise say so is not on screen.
     */
    public void syncSettingsNow(java.util.function.Consumer<String> report) {
        sync.syncNow(report, this::commandLabel);
    }

    /** A command as the menus name it, for a sync's conflict list; its id when no window knows it. */
    private String commandLabel(String id) {
        if (windows.isEmpty()) return id;
        for (Keybindings.Command command : windows.get(0).keyboardCommands()) {
            if (command.id().equals(id)) return command.label();
        }
        return id;
    }

    /** Opens the settings window and returns its scene, for the development capture hook. */
    public javafx.scene.Scene showSettingsForCapture(Window owner, String query) {
        showSettings(owner);
        if (query != null && !query.isBlank() && !"true".equals(query)) {
            settingsWindow().searchForCapture(query);
        }
        String keys = System.getProperty("termina.captureSettingsKeys");
        if (keys != null && !keys.isBlank()) {
            System.out.println("[capture] settings keys " + settingsWindow().fireKeysForCapture(keys));
        }
        return settingsWindow().scene();
    }

    public List<TerminalWindow> windows() {
        return List.copyOf(windows);
    }

    /** Every terminal in every window — used by the development capture hook. */
    public List<TerminalView> allTerminals() {
        List<TerminalView> all = new ArrayList<>();
        for (TerminalWindow window : windows) all.addAll(window.terminals());
        return all;
    }

    public void closeAll() {
        // Shutdown, not a user action: App.stop() reaches here after the quit is already decided.
        for (TerminalWindow window : List.copyOf(windows)) window.closeForShutdown();
        updates.shutdown();
    }
}
