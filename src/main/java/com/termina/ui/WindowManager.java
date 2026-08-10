package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.termina.config.Settings;

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
    private SettingsWindow settingsWindow;
    private AboutWindow aboutWindow;

    private final com.termina.update.UpdateService updates = new com.termina.update.UpdateService();
    /** The newer release, once found. Held app-wide so every window's Help menu agrees. */
    private com.termina.update.ReleaseInfo availableUpdate;

    private final List<Runnable> updateListeners = new ArrayList<>();
    private java.util.function.Consumer<String> openLink = url -> {};

    public WindowManager(Settings settings) {
        this.settings = settings;
        settings.setOnChange(this::applySettingsEverywhere);
    }

    /** Opens the first window on the primary stage, so the app owns no spare empty stage. */
    public TerminalWindow openFirstWindow(Stage primary) {
        return openFirstWindow(primary, null);
    }

    /**
     * @param cli launch options from the command line, applied to this window's first tab only —
     *     every later tab and window uses the configured shell and the home directory
     */
    public TerminalWindow openFirstWindow(Stage primary, com.termina.pty.LaunchOptions cli) {
        return open(primary, cli);
    }

    public void openWindow() {
        open(new Stage(), null);
    }

    private TerminalWindow open(Stage stage, com.termina.pty.LaunchOptions cli) {
        TerminalWindow window = new TerminalWindow(this, settings, stage);
        windows.add(window);

        stage.setOnHidden(e -> {
            windows.remove(window);
            // The last window closing ends the app. Without this the JVM lingers: the settings
            // window is a Stage of its own and would keep the toolkit alive on its own.
            if (windows.isEmpty()) Platform.exit();
        });

        // Offset each additional window so it does not land exactly on the one it came from.
        if (windows.size() > 1) {
            Stage previous = windows.get(windows.size() - 2).stage();
            stage.setX(previous.getX() + CASCADE_OFFSET);
            stage.setY(previous.getY() + CASCADE_OFFSET);
        }

        window.show();
        window.openTab(
                cli != null
                        ? cli.withShell(settings.shell())
                        : com.termina.pty.LaunchOptions.ofShell(settings.shell()));
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
        Theme theme = Theme.byId(settings.themeId(), Theme.EDITORA_DARK);
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

    public void showAbout(Window owner) {
        if (aboutWindow == null) aboutWindow = new AboutWindow(settings, openLink);
        aboutWindow.setUpdate(availableUpdate);
        aboutWindow.show(owner);
    }

    /** For the development capture hook. */
    public javafx.scene.Scene showAboutForCapture(Window owner) {
        showAbout(owner);
        return aboutWindow.scene();
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
            if (outcome.error() != null) {
                report.accept(tr("status.updateCheckFailed", outcome.error()));
            } else if (outcome.available()) {
                report.accept(tr("status.updateAvailable", outcome.latest().version()));
            } else {
                report.accept(tr("status.upToDate", com.termina.AppInfo.NAME));
            }
        });
    }

    private void onUpdateOutcome(com.termina.update.UpdateService.Outcome outcome, boolean manual) {
        if (!outcome.available()) return;
        // A version the user has already been shown stays out of the menu, but a manual check
        // surfaces it again — they just asked.
        if (!manual && outcome.latest().version().equals(settings.dismissedUpdate())) return;
        availableUpdate = outcome.latest();
        if (aboutWindow != null) aboutWindow.setUpdate(availableUpdate);
        for (Runnable listener : List.copyOf(updateListeners)) listener.run();
    }

    public com.termina.update.ReleaseInfo availableUpdate() {
        return availableUpdate;
    }

    /** Notified when an update is found, so a window can relabel its Help menu. */
    void addUpdateListener(Runnable listener) {
        updateListeners.add(listener);
    }

    /** Opens the release page and remembers the version, so it stops being advertised. */
    public void openReleasePage() {
        if (availableUpdate != null) settings.setDismissedUpdate(availableUpdate.version());
        String url = availableUpdate == null || availableUpdate.url().isBlank()
                ? com.termina.AppInfo.RELEASES_PAGE
                : availableUpdate.url();
        openLink.accept(url);
    }

    /** One settings window for the whole application, re-parented to whoever asked for it. */
    public void showSettings(Window owner) {
        if (settingsWindow == null) settingsWindow = new SettingsWindow(settings);
        settingsWindow.show(owner);
    }

    /** Opens the settings window and returns its scene, for the development capture hook. */
    public javafx.scene.Scene showSettingsForCapture(Window owner, String query) {
        showSettings(owner);
        if (query != null && !query.isBlank() && !"true".equals(query)) {
            settingsWindow.searchForCapture(query);
        }
        return settingsWindow.scene();
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
