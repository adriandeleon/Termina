package com.termina;

import javafx.application.Application;
import javafx.stage.Stage;

import com.termina.cli.CommandLine;
import com.termina.config.Settings;
import com.termina.pty.LaunchOptions;
import com.termina.ui.TerminalWindow;
import com.termina.ui.WindowManager;
import org.jspecify.annotations.Nullable;

/**
 * Termina — a cross-platform terminal emulator on JavaFX. This is the application {@link Main}
 * launches, never the main class itself: a main class that extends {@code Application} has the
 * toolkit started for it before {@code main} runs, and the command line must be answered without
 * one.
 */
public final class App extends Application {

    private @Nullable WindowManager windows;

    /**
     * This process's hold on its config folder (CLI-07), taken by {@link Main} before the toolkit
     * starts and served once the first window is up; null with {@code --new-instance}, or when the
     * folder could not be claimed and this one started anyway.
     */
    private static com.termina.instance.@Nullable SingleInstance instance;

    /**
     * Hands over the hold {@link Main} took on the config folder. Static because {@code launch}
     * makes the application itself, with nothing passed to it.
     */
    static void hold(com.termina.instance.@Nullable SingleInstance claimed) {
        instance = claimed;
    }

    /**
     * A later launch's request: a new window, started as that launch would have started its first.
     * Its {@code -d} is relative to where it was run, and with no {@code -d} the window opens there.
     */
    private void openHandedOff(com.termina.instance.SingleInstance.Request request, Settings settings) {
        WindowManager manager = windows;
        if (manager == null) return;
        CommandLine handed = CommandLine.parse(request.args().toArray(String[]::new));
        if (handed.error() != null || !handed.shouldRun()) return;
        String directory = handed.workingDirectory().isBlank()
                ? request.cwd()
                : java.nio.file.Path.of(request.cwd())
                        .resolve(handed.workingDirectory())
                        .toString();
        manager.openHandedOff(new LaunchOptions(settings.shell(), directory, handed.command()), handed.title());
    }

    /**
     * What is read from disk before the first window, read here: {@code init} runs on the launcher
     * thread, before {@code start} and off the FX thread (PERF-01, PERF-16, Pauta 2.0.2).
     */
    @Override
    public void init() {
        prepared = prepare(getParameters().getRaw());
    }

    /** What {@link #init} read, for {@link #start}; null when a test starts the app without it. */
    private @Nullable Prepared prepared;

    /** The command line, the settings and what was loaded with them, ready for the first window. */
    record Prepared(CommandLine cli, Settings settings) {}

    /**
     * The settings, the fonts the first window draws, the log file, the catalogue and the imported
     * themes — every file the first window needs, read before the toolkit asks for any of them.
     */
    static Prepared prepare(java.util.List<String> args) {
        com.termina.perf.Startup.mark("init");
        CommandLine cli = CommandLine.parse(args.toArray(String[]::new));
        Settings settings = Settings.deferred(Settings.defaultFile(cli.configDir(), cli.dev()));
        settings.load();
        com.termina.perf.Startup.mark("settings");
        // Here, off the FX thread: whether the configured shell can run is asked of the disk, and
        // the first menu the window builds wants the answer (PERF-01, Pauta 6.0.1).
        com.termina.shell.ShellProfiles.warm(settings.shell(), java.util.List.of());

        // Before the stylesheet and before any window: CSS naming a font that is not yet registered
        // does not wait for it, it silently resolves to the system face. Only what the first window
        // draws — the interface font, the icons, the terminal's family; the other bundled families
        // follow the first frame (PERF-15).
        com.termina.ui.Fonts.load(settings.fontFamily());
        com.termina.perf.Startup.mark("fonts");
        com.termina.ui.DebugLog.attachFile(settings.directory());

        // Before the command names, menus and Settings rows are built — all of which read the
        // catalogue as they are constructed, once.
        String language = com.termina.i18n.Messages.resolve(
                settings.uiLanguage(),
                com.termina.i18n.Messages.available().keySet(),
                java.util.Locale.getDefault().getLanguage());
        com.termina.i18n.Messages.init(language);
        // So JavaFX localises its own built-in pieces — the OK/Cancel buttons on an Alert, and any
        // locale-sensitive control — to match. Only when the user chose a language: "Automatic"
        // follows the OS, country and all, rather than reducing de_AT to de.
        java.util.Locale.setDefault(com.termina.i18n.Messages.defaultLocale(
                settings.uiLanguage(), language, java.util.Locale.getDefault()));

        // The imported themes, read now: the stored theme may be one of them.
        com.termina.ui.Themes.useDirectory(settings.directory().resolve("themes"));
        return new Prepared(cli, settings);
    }

    @Override
    public void start(Stage stage) {
        start(stage, getParameters().getRaw(), url -> getHostServices().showDocument(url));
    }

    /**
     * The start, given what {@code launch} would: the arguments and how to open a link. Apart so a
     * test can start the real application on a stage of its own; only {@code launch} makes the
     * parameters and the host services, and runs {@link #init}.
     */
    void start(Stage stage, java.util.List<String> args, java.util.function.Consumer<String> openLink) {
        com.termina.perf.Startup.mark("fx-start");
        Prepared ready = prepared != null ? prepared : prepare(args);
        CommandLine cli = ready.cli();
        Settings settings = ready.settings();

        // Applied before any window is built, so the first frame is already themed.
        com.termina.ui.Themes.followSystem(
                javafx.application.Platform.getPreferences().getColorScheme() == javafx.application.ColorScheme.DARK);
        Application.setUserAgentStylesheet(
                com.termina.ui.Themes.byId(settings.themeId()).stylesheet());

        com.termina.ui.StallMonitor.installIfRequested();
        WindowManager manager = new WindowManager(settings);
        windows = manager;
        windows.setLinkOpener(openLink);
        com.termina.perf.Startup.mark("pre-window");
        TerminalWindow first = windows.openFirstWindow(
                stage, new LaunchOptions(settings.shell(), cli.workingDirectory(), cli.command()), cli.title());
        com.termina.perf.Startup.mark("window-shown");
        com.termina.instance.SingleInstance held = instance;
        if (held != null) {
            // Off the FX thread (PERF-01, Pauta 6.0.1): listening deletes a stale socket file and
            // binds a new one, which is the disk's time, and no frame should wait on it.
            Thread listen = new Thread(
                    () -> {
                        try {
                            held.serve(request ->
                                    javafx.application.Platform.runLater(() -> openHandedOff(request, settings)));
                        } catch (java.io.IOException e) {
                            java.util.logging.Logger.getLogger(App.class.getName())
                                    .log(java.util.logging.Level.WARNING, "cannot listen for other launches", e);
                        }
                    },
                    "termina-instance-bind");
            listen.setDaemon(true);
            listen.start();
        }

        // What nobody waits for, after the first frame (PERF-15, DIAG-05): the font picker's other
        // families, the window icons a title bar never shows, and — a few seconds later still,
        // so it never competes with the shell's own startup — the daily update check.
        com.termina.ui.FirstFrame.after(() -> {
            com.termina.ui.Fonts.loadRestInBackground(com.termina.ui.MonospaceFonts::forget);
            com.termina.ui.Icons.loadLarge();
            javafx.animation.PauseTransition settle =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            settle.setOnFinished(e -> manager.maybeCheckForUpdates());
            settle.play();
        });
        com.termina.ui.FirstFrame.start();

        if (DevCapture.requested()) DevCapture.schedule(windows, first, settings);
        maybeExitAfterTraining();
    }

    /**
     * Ends the run once a window has been up long enough to have loaded what it needs.
     *
     * <p>Used only while recording the AOT cache. The training run has to render — the bulk of what
     * is worth archiving is JavaFX scene, control and CSS classes, which are not touched until a
     * window actually paints, so a headless training run gains close to nothing.
     */
    private void maybeExitAfterTraining() {
        if (System.getProperty("termina.aotTrain") == null) return;
        javafx.animation.PauseTransition settle =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(2500));
        settle.setOnFinished(e -> System.exit(0));
        settle.play();
    }

    @Override
    public void stop() {
        if (windows != null) windows.closeAll();
        com.termina.instance.SingleInstance held = instance;
        if (held != null) held.close();
    }
}
