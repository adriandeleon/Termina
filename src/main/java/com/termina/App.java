package com.termina;

import javafx.application.Application;
import javafx.stage.Stage;

import com.termina.cli.CommandLine;
import com.termina.config.Settings;
import com.termina.pty.LaunchOptions;
import com.termina.ui.TerminalWindow;
import com.termina.ui.WindowManager;
import org.jspecify.annotations.Nullable;

/** Termina — a cross-platform terminal emulator on JavaFX. */
public final class App extends Application {

    private @Nullable WindowManager windows;

    /**
     * This process's hold on its config folder (CLI-07), taken in {@link #main} before the toolkit
     * starts and served once the first window is up; null with {@code --new-instance}, or when the
     * folder could not be claimed and this one started anyway.
     */
    private static com.termina.instance.@Nullable SingleInstance instance;

    /** How the launcher is invoked, for the usage text. */
    private static final String LAUNCH_NAME = "termina";

    public static void main(String[] args) {
        // First, before the arguments are even read (DIAG-04, Pauta 2.0.2): anything that goes wrong
        // from here on is captured, not printed to a console a packaged app does not have. It
        // keeps records in memory only; the file beside the settings is attached once the config
        // folder is known. Nothing in it loads AWT, which the line below must precede.
        com.termina.ui.DebugLog.install();
        // Parsed before anything else starts. --version is what somebody pastes into a bug report,
        // so it must not depend on a display, a config file, or the toolkit coming up at all.
        CommandLine cli = CommandLine.parse(args);
        if (cli.error() != null) {
            System.err.println(AppInfo.NAME + ": " + cli.error());
            System.err.println("Try '" + LAUNCH_NAME + " --help' for the options.");
            System.exit(2);
        }
        if (cli.help()) {
            System.out.println(CommandLine.usage(LAUNCH_NAME));
            // Not a bare return: launching an Application subclass in module mode starts the FX
            // toolkit, whose non-daemon thread would keep the process alive after main returns.
            System.exit(0);
        }
        if (cli.report()) {
            // No window: for when the window is what will not start. CLI-05.
            System.exit(
                    com.termina.report.ReportCommand.run(Settings.defaultFile(cli.configDir(), cli.dev()), System.out));
        }
        if (cli.version()) {
            // The first line stays "Termina <version>", which is what anything parsing it reads.
            AppInfo.details().forEach(System.out::println);
            System.exit(0);
        }

        // Must be the first statement, before any AWT class can load. We require java.desktop
        // (TtyConnector's default methods reference java.awt.Dimension) but never use AWT, and on
        // macOS an initialised AWT/Java2D pipeline contends with JavaFX's Glass/Prism for the
        // single AppKit run loop — an intermittent hang rather than a clean failure.
        System.setProperty("java.awt.headless", "true");
        com.termina.perf.Startup.mark("main");
        // A capture or an AOT training run is a process of its own whatever the folder: handed to a
        // Termina already running, it would photograph or train nothing.
        boolean ownProcess = cli.newInstance()
                || System.getProperty("termina.capture") != null
                || System.getProperty("termina.aotTrain") != null;
        if (!ownProcess) claimOrHandOff(cli, args);
        launch(args);
    }

    /**
     * One Termina per config folder (CLI-07): the first launch claims it; a later one hands its
     * arguments to the first, which opens a window for them, and exits here. When the holder does
     * not answer, this one starts on its own rather than not at all, and says why on stderr.
     */
    private static void claimOrHandOff(CommandLine cli, String[] args) {
        java.nio.file.Path configDir = Settings.defaultFile(cli.configDir(), cli.dev())
                .toAbsolutePath()
                .getParent();
        if (configDir == null) return;
        try {
            instance = com.termina.instance.SingleInstance.claim(configDir);
        } catch (java.io.IOException | RuntimeException e) {
            return; // a folder that cannot be locked is no reason not to start
        }
        if (instance != null) return;
        var request = new com.termina.instance.SingleInstance.Request(
                System.getProperty("user.dir", ""), java.util.List.of(args));
        if (com.termina.instance.SingleInstance.handOff(configDir, request, java.time.Duration.ofSeconds(5))) {
            System.exit(0);
        }
        System.err.println(
                AppInfo.NAME + ": another Termina holds " + configDir + " but did not answer; starting a second one.");
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

    @Override
    public void start(Stage stage) {
        com.termina.perf.Startup.mark("fx-start");
        CommandLine cli = CommandLine.parse(getParameters().getRaw().toArray(String[]::new));
        Settings settings = Settings.deferred(Settings.defaultFile(cli.configDir(), cli.dev()));
        settings.load();
        com.termina.perf.Startup.mark("settings");

        // Before the stylesheet below and before any window: CSS naming a font that is not yet
        // registered does not wait for it, it silently resolves to the system face. Only what the
        // first window draws — the interface font, the icons, the terminal's family; the other
        // bundled families follow the first frame (PERF-15).
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

        // Applied before any window is built, so the first frame is already themed.
        // The imported themes are read first: the stored theme may be one of them.
        com.termina.ui.Themes.useDirectory(settings.directory().resolve("themes"));
        com.termina.ui.Themes.followSystem(
                javafx.application.Platform.getPreferences().getColorScheme() == javafx.application.ColorScheme.DARK);
        Application.setUserAgentStylesheet(
                com.termina.ui.Themes.byId(settings.themeId()).stylesheet());

        com.termina.ui.StallMonitor.installIfRequested();
        WindowManager manager = new WindowManager(settings);
        windows = manager;
        windows.setLinkOpener(url -> getHostServices().showDocument(url));
        com.termina.perf.Startup.mark("pre-window");
        TerminalWindow first = windows.openFirstWindow(
                stage, new LaunchOptions(settings.shell(), cli.workingDirectory(), cli.command()), cli.title());
        com.termina.perf.Startup.mark("window-shown");
        com.termina.instance.SingleInstance held = instance;
        if (held != null) {
            try {
                held.serve(request -> javafx.application.Platform.runLater(() -> openHandedOff(request, settings)));
            } catch (java.io.IOException e) {
                java.util.logging.Logger.getLogger(App.class.getName())
                        .log(java.util.logging.Level.WARNING, "cannot listen for other launches", e);
            }
        }

        // What nobody waits for, after the first frame (PERF-15, DIAG-05): the font picker's other
        // families, the window icons a title bar never shows, and — a few seconds later still,
        // so it never competes with the shell's own startup — the daily update check.
        com.termina.ui.FirstFrame.after(() -> {
            if (com.termina.ui.Fonts.loadRest()) com.termina.ui.MonospaceFonts.forget();
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
