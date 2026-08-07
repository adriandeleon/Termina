package com.termina;

import javafx.application.Application;
import javafx.stage.Stage;

import com.termina.cli.CommandLine;
import com.termina.config.Settings;
import com.termina.pty.LaunchOptions;
import com.termina.ui.TerminalWindow;
import com.termina.ui.Theme;
import com.termina.ui.WindowManager;

/** Termina — a cross-platform terminal emulator on JavaFX. */
public final class App extends Application {

    private WindowManager windows;

    /** How the launcher is invoked, for the usage text. */
    private static final String LAUNCH_NAME = "termina";

    public static void main(String[] args) {
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
        if (cli.version()) {
            System.out.println(AppInfo.NAME + " " + AppInfo.VERSION);
            System.exit(0);
        }

        // Must be the first statement, before any AWT class can load. We require java.desktop
        // (TtyConnector's default methods reference java.awt.Dimension) but never use AWT, and on
        // macOS an initialised AWT/Java2D pipeline contends with JavaFX's Glass/Prism for the
        // single AppKit run loop — an intermittent hang rather than a clean failure.
        System.setProperty("java.awt.headless", "true");
        // Before launch, so anything that goes wrong during startup is captured too — that is the
        // window in which a packaged application is most likely to fail and least able to say so.
        com.termina.ui.DebugLog.install();
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // Before the stylesheet below and before any window: CSS naming a font that is not yet
        // registered does not wait for it, it silently resolves to the system face.
        com.termina.ui.Fonts.load();

        CommandLine cli = CommandLine.parse(getParameters().getRaw().toArray(String[]::new));
        Settings settings = new Settings(Settings.defaultFile(cli.configDir()));
        settings.load();
        com.termina.ui.DebugLog.attachFile(settings.file().getParent());

        // Before the command names, menus and Settings rows are built — all of which read the
        // catalogue as they are constructed, once.
        String language = com.termina.i18n.Messages.resolve(
                settings.uiLanguage(),
                com.termina.i18n.Messages.available().keySet(),
                java.util.Locale.getDefault().getLanguage());
        com.termina.i18n.Messages.init(language);
        // So JavaFX localises its own built-in pieces — the OK/Cancel buttons on an Alert, and any
        // locale-sensitive control — to match rather than to the OS.
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag(language));

        // Applied before any window is built, so the first frame is already themed.
        Application.setUserAgentStylesheet(
                Theme.byId(settings.themeId(), Theme.EDITORA_DARK).stylesheet());

        com.termina.ui.StallMonitor.installIfRequested();
        windows = new WindowManager(settings);
        windows.setLinkOpener(url -> getHostServices().showDocument(url));
        TerminalWindow first = windows.openFirstWindow(
                stage, new LaunchOptions(settings.shell(), cli.workingDirectory(), cli.command()));
        windows.maybeCheckForUpdates();

        if (DevCapture.requested()) DevCapture.schedule(windows, first, settings);
    }

    @Override
    public void stop() {
        if (windows != null) windows.closeAll();
    }
}
