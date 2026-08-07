package com.termina;

import com.termina.config.Settings;
import com.termina.ui.Theme;
import com.termina.ui.TerminalWindow;
import com.termina.ui.WindowManager;
import javafx.application.Application;
import javafx.stage.Stage;

/** Termina — a cross-platform terminal emulator on JavaFX. */
public final class App extends Application {

    private WindowManager windows;

    public static void main(String[] args) {
        // Must be the first statement, before any AWT class can load. We require java.desktop
        // (TtyConnector's default methods reference java.awt.Dimension) but never use AWT, and on
        // macOS an initialised AWT/Java2D pipeline contends with JavaFX's Glass/Prism for the
        // single AppKit run loop — an intermittent hang rather than a clean failure.
        System.setProperty("java.awt.headless", "true");
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // Before the stylesheet below and before any window: CSS naming a font that is not yet
        // registered does not wait for it, it silently resolves to the system face.
        com.termina.ui.Fonts.load();

        Settings settings = new Settings(Settings.defaultFile());
        settings.load();

        // Applied before any window is built, so the first frame is already themed.
        Application.setUserAgentStylesheet(
                Theme.byId(settings.themeId(), Theme.EDITORA_DARK).stylesheet());

        com.termina.ui.StallMonitor.installIfRequested();
        windows = new WindowManager(settings);
        windows.setLinkOpener(url -> getHostServices().showDocument(url));
        TerminalWindow first = windows.openFirstWindow(stage);
        windows.maybeCheckForUpdates();

        if (DevCapture.requested()) DevCapture.schedule(windows, first, settings);
    }

    @Override
    public void stop() {
        if (windows != null) windows.closeAll();
    }
}
