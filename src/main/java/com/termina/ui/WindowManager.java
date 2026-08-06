package com.termina.ui;

import com.termina.config.Settings;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;

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

    public WindowManager(Settings settings) {
        this.settings = settings;
        settings.setOnChange(this::applySettingsEverywhere);
    }

    /** Opens the first window on the primary stage, so the app owns no spare empty stage. */
    public TerminalWindow openFirstWindow(Stage primary) {
        return open(primary);
    }

    public void openWindow() {
        open(new Stage());
    }

    private TerminalWindow open(Stage stage) {
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
        window.openTab();
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

    /** One settings window for the whole application, re-parented to whoever asked for it. */
    public void showSettings(Window owner) {
        if (settingsWindow == null) settingsWindow = new SettingsWindow(settings);
        settingsWindow.show(owner);
    }

    /** Opens the settings window and returns its scene, for the development capture hook. */
    public javafx.scene.Scene showSettingsForCapture(Window owner) {
        showSettings(owner);
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
        for (TerminalWindow window : List.copyOf(windows)) window.close();
    }
}
