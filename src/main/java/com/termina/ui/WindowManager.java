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
    private final ConfigTransfer configTransfer;
    private final List<TerminalWindow> windows = new ArrayList<>();
    private @Nullable SettingsWindow settingsWindow;
    private @Nullable AboutWindow aboutWindow;

    private com.termina.update.UpdateService updates = new com.termina.update.UpdateService();

    /** Asks {@code service} for updates instead of GitHub: for a test with its own server. */
    void useUpdateServiceForTest(com.termina.update.UpdateService service) {
        updates = service;
    }

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
    /** The last update check that answered this session, failures included; null until one has. */
    private com.termina.update.UpdateService.@Nullable Outcome lastAnswer;

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

    /** This session's messages, for every window's status bar and the Message Log. */
    private final MessageLog messages = new MessageLog();

    private @Nullable MessageLogWindow messageLogWindow;
    private @Nullable DebugLogWindow debugLogWindow;

    public WindowManager(Settings settings) {
        this.settings = settings;
        this.configTransfer = new ConfigTransfer(settings);
        this.profiles = new com.termina.shell.ShellProfiles(settings, tr("profile.systemShell"));
        this.profiles.neverAskTheDiskOn(Platform::isFxApplicationThread);
        // Nothing to do when it finishes: every menu that lists profiles is built when it is shown,
        // so the next one to open picks up whatever discovery found. The callback exists because a
        // menu already on screen when the answer lands would otherwise keep the shorter list.
        profiles.discoverInBackground(found -> {});
        settings.addOnChange(this::applySettingsEverywhere);
        // An uncaught exception is recorded in the Debug Log first; this says so where the user is
        // looking, never as a dialog with a stack trace in it (DIAG-04).
        // In batches a frame apart, not a runLater for each (PERF-07): a thread that throws in a
        // loop throws at its own pace.
        FxBatch<Throwable> uncaught = new FxBatch<>(errors -> {
            for (Throwable error : errors) {
                messages.add(
                        MessageLog.Severity.ERROR,
                        tr("status.uncaughtError", error.getClass().getSimpleName()));
            }
        });
        DebugLog.onUncaught(uncaught::add);
        // The desktop's light or dark, for the theme that follows it. Re-applied whatever the
        // stored theme, because a profile can follow the system while the default does not; the
        // desktop changes its scheme a few times a day at most.
        javafx.application.Platform.getPreferences().colorSchemeProperty().addListener((o, was, now) -> {
            Themes.followSystem(now == javafx.application.ColorScheme.DARK);
            applySettingsEverywhere();
        });
        this.sync = new SettingsSync(settings, this::anyWindowFocused);
        // A window joins this list as it is shown, before its first frame: the one place that
        // catches every window — settings, About, a confirmation dialog — however it was made.
        // The sizes the code fixes, in every window, follow the UI zoom (WIN-12, Pauta 6.0.1).
        UiZoom.adoptWindows();
        Window.getWindows().addListener((javafx.collections.ListChangeListener<Window>) change -> {
            // Looked up only once a window was actually added: the list also fires for removals,
            // and every tooltip and menu is a window.
            javafx.application.@Nullable ColorScheme scheme = null;
            while (change.next()) {
                for (Window added : change.getAddedSubList()) {
                    if (scheme == null) scheme = Themes.byId(settings.themeId()).colorScheme();
                    applyColorScheme(added, scheme);
                    installUiFont(added);
                }
            }
        });
    }

    /**
     * Tells a window's scene whether the theme is light or dark. JavaFX draws the buttons of an
     * extended title bar and picks the native frame's variant from the scene's colour scheme, and
     * that defaults to the desktop's — so a dark theme on a light desktop had dark grey buttons on a
     * dark header, and on Windows and macOS a light dialog would get a dark frame. A terminal window
     * sets its own, from the pane in use (its profile may name another theme); a popup has no frame.
     */
    private void applyColorScheme(Window window, javafx.application.ColorScheme scheme) {
        if (window instanceof javafx.stage.PopupWindow) return;
        javafx.scene.Scene scene = window.getScene();
        if (scene == null) return;
        for (TerminalWindow terminal : windows) {
            if (terminal.stage() == window) return;
        }
        scene.getPreferences().setColorScheme(scheme);
    }

    /**
     * The interface font on a window's scene, whoever built the window (ICON-01, Pauta 6.0.1): an
     * alert or a prompt is a scene of its own and would otherwise draw in the platform's face. A
     * popup takes its owner's scene stylesheets when JavaFX shows it, and needs none.
     */
    private static void installUiFont(Window window) {
        if (window instanceof javafx.stage.PopupWindow) return;
        if (window.getScene() != null) Fonts.installUiFont(window.getScene());
        // Once per window: it joins the list each time it is shown.
        if (window.getProperties().putIfAbsent(UI_FONT_HOOKED, Boolean.TRUE) != null) return;
        window.sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) Fonts.installUiFont(scene);
        });
    }

    private static final Object UI_FONT_HOOKED = new Object();

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
    /**
     * A window for another launch's command line (CLI-07): its options apply to this window's
     * first tab, as they would have to a process of its own, and it comes to the front.
     */
    public TerminalWindow openHandedOff(com.termina.pty.LaunchOptions cli, String title) {
        firstTabTitle = title;
        TerminalWindow window = open(new Stage(), cli, windows.isEmpty() ? null : windows.getLast(), null);
        window.stage().toFront();
        window.stage().requestFocus();
        return window;
    }

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
            if (windows.isEmpty()) endApp.run();
        });

        // Offset each additional window so it does not land exactly on the one it came from, and
        // keep it on that window's screen — the offset alone walked the title bar off the bottom.
        if (from != null && windows.contains(from)) {
            Stage origin = from.stage();
            double width = stage.getScene() != null ? stage.getScene().getWidth() : origin.getWidth();
            double height = stage.getScene() != null ? stage.getScene().getHeight() : origin.getHeight();
            double[] at = WindowPlacement.offsetWithin(
                    origin.getX(),
                    origin.getY(),
                    CASCADE_OFFSET,
                    width,
                    height,
                    SecondaryWindows.bounds(SecondaryWindows.screenOf(origin)));
            stage.setX(at[0]);
            stage.setY(at[1]);
        }

        window.show();
        // After the show, and a pulse later, by when Glass has built the application menu; until it
        // takes, every window that opens tries again (ABOUT-01).
        Platform.runLater(() -> MacAppMenu.addAbout(
                com.termina.i18n.Messages.tr("command.help.about", com.termina.AppInfo.NAME),
                () -> showAbout(frontWindow())));
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
        // The UI zoom first (WIN-12, Pauta 6.0.1): every open window's own sheets, then the theme
        // at that zoom.
        UiZoom.apply(settings.uiZoom());
        Application.setUserAgentStylesheet(UiZoom.theme(theme.stylesheet()));
        for (Window open : List.copyOf(Window.getWindows())) applyColorScheme(open, theme.colorScheme());
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

    // ---------------------------------------------------------------- quit

    private final List<TerminalWindow> closeRequests = new ArrayList<>();
    private boolean quitting;

    /** What the last window going does: ends the app. A test's manager counts it, and stays up. */
    private Runnable endApp = Platform::exit;

    void endAppWithForTest(Runnable end) {
        endApp = end;
    }

    /**
     * A window was asked to close: by its close button, or by the system quitting the app, which
     * asks every window in the same turn. Gathered until the turn is over and then told apart
     * ({@link WindowRules#closeRequestsAreAQuit}), so the system's Quit runs the one quit method
     * and asks once, not once for each window (WIN-13, Pauta 6.0.1).
     */
    void closeRequested(TerminalWindow window) {
        closeRequests.add(window);
        if (closeRequests.size() > 1) return;
        Platform.runLater(() -> {
            List<TerminalWindow> asked = List.copyOf(closeRequests);
            closeRequests.clear();
            if (WindowRules.closeRequestsAreAQuit(asked.size(), windows.size())) {
                quit(asked.get(0));
            } else {
                for (TerminalWindow one : asked) one.close();
            }
        });
    }

    /**
     * Quits: the one method every way of quitting runs, the Quit row, the {@code app.quit} command
     * and the system's own Quit (WIN-13). It asks once if any shell in any window still has a
     * program running, in the window it was asked from, and on a yes closes the dialogs and
     * secondary windows, then every terminal window the way shutdown does, which saves what
     * closing a window saves; the last one going ends the app.
     *
     * @param from the window to ask in, or null for the one in front
     */
    void quit(@Nullable TerminalWindow from) {
        if (quitting) return;
        TerminalWindow asker = from;
        if (asker == null || !windows.contains(asker)) {
            asker = windows.isEmpty() ? null : windows.get(windows.size() - 1);
            for (TerminalWindow window : windows) {
                if (window.stage().isFocused()) asker = window;
            }
        }
        if (asker == null) {
            quitNow();
            return;
        }
        asker.confirmQuitThen(allTerminals(), this::quitNow);
    }

    private void quitNow() {
        if (quitting) return;
        quitting = true;
        // Dialogs and the windows beside the terminals first: nothing of theirs is left open over
        // an app that is going.
        // Never a terminal's window, not even one this manager did not open: hidden from here it
        // would go without its question, and as its own manager's last window it would end the
        // app from under this one. (Tests run several managers in one JVM; that is how it showed.)
        for (Window open : List.copyOf(Window.getWindows())) {
            if (TerminalWindow.isTerminalWindow(open)) continue;
            if (open instanceof Stage stage && stage.isShowing()) stage.hide();
        }
        // The last of these to go ends the app (open's setOnHidden), and App.stop writes the settings.
        for (TerminalWindow window : List.copyOf(windows)) window.closeForShutdown();
        // A manager with no window left to close it, a test's: nothing more is coming.
        quitting = !windows.isEmpty();
    }

    /** One About window for the application, shown beside whichever window asked. */
    /** The terminal window the user is in, or the last one opened when none has focus. */
    private Window frontWindow() {
        for (TerminalWindow window : windows) {
            if (window.stage().isFocused()) return window.stage();
        }
        return windows.isEmpty() ? new Stage() : windows.get(windows.size() - 1).stage();
    }

    public void showAbout(Window from) {
        AboutWindow about = aboutWindow();
        about.setUpdate(knownUpdate, lastAnswer);
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
        if (about == null) {
            aboutWindow = about = new AboutWindow(settings, openLink, () -> showPauta(frontWindow()));
        }
        return about;
    }

    private @Nullable PautaWindow pautaWindow;

    /** The Pauta window (PWIN-01): what {@code help.pauta} and About's conformance link open. */
    void showPauta(Window from) {
        PautaWindow window = pautaWindow;
        if (window == null) pautaWindow = window = new PautaWindow(openLink);
        window.show(from);
    }

    /** For the development capture hook. */
    public javafx.scene.Scene showPautaForCapture(Window owner) {
        showPauta(owner);
        PautaWindow window = pautaWindow;
        return window == null ? owner.getScene() : window.scene();
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
    public void checkForUpdatesNow(Reporter report) {
        report.info(tr("status.checkingForUpdates"));
        settings.setLastUpdateCheck(System.currentTimeMillis());
        updates.check(com.termina.AppInfo.VERSION, outcome -> {
            onUpdateOutcome(outcome, true);
            var error = outcome.error();
            var latest = outcome.latest();
            if (error != null) {
                report.error(describeFailure(error, outcome.detail()));
            } else if (outcome.available() && latest != null) {
                report.info(tr("status.updateAvailable", latest.version()));
            } else {
                report.info(tr("status.upToDate", com.termina.AppInfo.NAME));
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

    void onUpdateOutcome(com.termina.update.UpdateService.Outcome outcome, boolean manual) {
        lastAnswer = outcome;
        if (aboutWindow != null) aboutWindow.setUpdate(knownUpdate, outcome);
        com.termina.update.ReleaseInfo latest = outcome.latest();
        // Available always comes with the release; the pair is checked so the type says so too.
        if (!outcome.available() || latest == null) return;
        knownUpdate = latest;
        if (aboutWindow != null) aboutWindow.setUpdate(latest, outcome);
        // A version the user has already been shown stays out of the menu, but a manual check
        // surfaces it again — they just asked.
        if (!manual && latest.version().equals(settings.dismissedUpdate())) return;
        availableUpdate = latest;
        notifyUpdateListeners();
        // The status-bar notice (DIAG-05), beside the Help menu's: the log is where the bar's
        // echo area reads from.
        if (!manual) messages.add(MessageLog.Severity.INFO, tr("status.updateAvailable", latest.version()));
    }

    /**
     * A newer version, as the update check would report it at startup, for the capture hook
     * ({@code -Dtermina.captureUpdateAvailable=99.0.0}): the Help menu's row and the status bar's
     * link to the release (DIAG-09) are otherwise only seen on the day a release comes out.
     */
    public void announceUpdateForCapture(String version) {
        onUpdateOutcome(
                new com.termina.update.UpdateService.Outcome(
                        true,
                        new com.termina.update.ReleaseInfo(
                                version,
                                com.termina.AppInfo.RELEASES_PAGE + "/tag/v" + version,
                                com.termina.AppInfo.NAME + " " + version),
                        null,
                        null),
                false);
    }

    MessageLog messages() {
        return messages;
    }

    /** The Message Log, beside whichever window asked. */
    void showMessageLog(Window from) {
        messageLog().show(from);
    }

    /**
     * The chord command {@code id} is on now, for the windows beside the terminals: the bindings
     * are the application's, so any terminal window's table answers.
     */
    javafx.scene.input.@Nullable KeyCombination chordOf(String id) {
        return windows.isEmpty() ? null : windows.get(0).chordOf(id);
    }

    /** The one Message Log window, built on first use: what its commands act on. */
    MessageLogWindow messageLog() {
        MessageLogWindow window = messageLogWindow;
        if (window == null) {
            messageLogWindow = window = new MessageLogWindow(messages);
            window.setChords(this::chordOf);
        }
        return window;
    }

    /** The Debug Log: a command now, not only a button on the Advanced page (DIAG-03). */
    void showDebugLog(Window from) {
        debugLog(from).show();
    }

    /**
     * The Debug Log with a query in its find bar, for the capture hook
     * ({@code -Dtermina.captureDebugLogFind=text}); says where the search stands and returns the
     * window's scene to photograph.
     */
    public javafx.scene.Scene debugLogFindForCapture(Window from, String query) {
        DebugLogWindow window = debugLog(from);
        window.show();
        window.searchFor(query);
        System.out.println("[capture] debugLogFind " + window.findReport() + " menu=" + window.menuRowsForTest());
        return window.scene();
    }

    /**
     * The Message Log with its filter typed into, for the capture hook
     * ({@code -Dtermina.captureMessageLogFilter=text}).
     */
    public javafx.scene.Scene messageLogFilterForCapture(Window from, String typed) {
        MessageLogWindow window = messageLog();
        window.show(from);
        window.filterBy(typed);
        System.out.println("[capture] messageLogFilter " + window.report() + " menu=" + window.menuRowsForTest());
        return window.scene();
    }

    /** The one Debug Log window, built on first use: what its commands act on. */
    DebugLogWindow debugLog(Window from) {
        DebugLogWindow window = debugLogWindow;
        if (window == null) {
            debugLogWindow = window = new DebugLogWindow(from, () -> showReportProblem(from));
            window.setChords(this::chordOf);
        }
        return window;
    }

    private @Nullable ReportProblemWindow reportWindow;

    /**
     * Help → Report a Problem (DIAG-06): this session's report, redacted, in a window to read and
     * edit before the user sends it themselves.
     */
    void showReportProblem(Window from) {
        ReportProblemWindow window = reportWindow;
        if (window == null) reportWindow = window = new ReportProblemWindow(openLink);
        window.show(from, currentReport());
    }

    /**
     * Report a Problem's two buttons as commands ({@code report.copy}, {@code report.open}): on the
     * window as it stands when it is open, with whatever the user has edited, and on a fresh
     * report shown first when it is not.
     */
    void reportProblemAction(Window from, boolean open) {
        ReportProblemWindow window = reportWindow;
        if (window == null || !window.isShowing()) {
            showReportProblem(from);
            window = reportWindow;
        }
        if (window == null) return;
        if (open) window.openIssue();
        else window.copy();
    }

    /** One of the Settings window's actions, as its command runs it (KBD-01). */
    void runSettingsAction(Window from, SettingsActions.Action action) {
        settingsWindow().run(from, action);
    }

    /**
     * Why a Settings action cannot run now, or null when it can. Answered from the settings and the
     * sync's state, without building the Settings window: the palette asks on every listing.
     */
    @Nullable
    String settingsActionUnavailable(SettingsActions.Action action) {
        String key = SettingsActions.unavailableKey(action, settingsActionState());
        return key == null ? null : tr(key);
    }

    private SettingsActions.State settingsActionState() {
        SettingsWindow window = settingsWindow;
        SettingsSync.State state = sync.state();
        com.termina.sync.SyncReport report = state.report();
        return new SettingsActions.State(
                Themes.byId(settings.themeId()).isBuiltIn(),
                window != null && window.hasRemovableProfileSelected(),
                settings.syncEnabled(),
                state.phase() == SettingsSync.Phase.SYNCING,
                report != null && report.status() == com.termina.sync.SyncReport.Status.NEEDS_CONFIRMATION,
                com.termina.sync.SyncEngine.hasCredentials(settings.syncRepository()),
                sync.gitHubAvailable());
    }

    /** Opens Report a Problem and returns its scene, for the development capture hook. */
    public javafx.scene.Scene showReportProblemForCapture(Window from) {
        showReportProblem(from);
        ReportProblemWindow window = reportWindow;
        if (window == null) throw new IllegalStateException("not shown");
        return window.scene();
    }

    /** What a report would hold now. */
    com.termina.report.ProblemReport currentReport() {
        return com.termina.report.ProblemReport.build(
                com.termina.AppInfo.details(),
                settings.values(),
                DebugLog.text(),
                com.termina.report.Redactor.forThisMachine());
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

    /** Export Configuration (SET-12): a zip of the config folder in the home folder. */
    void exportConfiguration(Reporter report) {
        configTransfer.export(report);
    }

    /** Import Configuration (SET-12): asks for a zip and replaces the parts the user ticks. */
    void importConfiguration(Window from, Reporter report) {
        configTransfer.importFrom(from, report);
    }

    /**
     * Import Configuration without the file chooser or the dialog, ticking {@code parts}: for the
     * development capture, which can drive neither.
     */
    public void importConfigurationForCapture(
            java.nio.file.Path zip, java.util.Set<com.termina.config.ConfigArchive.Part> parts, Reporter report) {
        configTransfer.importZip(zip, report, scan -> {
            System.out.println("[capture] import offers " + scan.parts());
            return parts;
        });
    }

    /** The settings window, on its Profiles page. */
    public void showProfileSettings(Window from) {
        settingsWindow().showProfiles(from);
    }

    /** Whether the settings window has been made yet: for the first-frame test (PERF-14). */
    boolean settingsWindowBuilt() {
        return settingsWindow != null;
    }

    /** The application's settings sync, for the palette's sync commands. */
    SettingsSync settingsSync() {
        return sync;
    }

    /** The settings window, made on first use. */
    /** The settings window, made if need be: for the test that presses its buttons. */
    SettingsWindow settingsWindowForTest() {
        return settingsWindow();
    }

    private SettingsWindow settingsWindow() {
        SettingsWindow window = settingsWindow;
        if (window == null) {
            // Every window has the same commands, so the first one answers for all of them.
            settingsWindow = window = new SettingsWindow(
                    settings,
                    profiles,
                    windows.isEmpty() ? List.of() : windows.get(0).keyboardCommands(),
                    sync);
            window.openDebugLogWith(this::showDebugLog);
        }
        return window;
    }

    /**
     * Sync Now from a menu or the palette. {@code report} hears that it started and how it went, as
     * the update check's does, because the Sync page that would otherwise say so is not on screen.
     */
    public void syncSettingsNow(Reporter report) {
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
        // After the windows, whose closing wrote their geometry: saves are deferred, and this is the
        // last moment on the FX thread where a pending one can still be written.
        settings.flush();
    }
}
