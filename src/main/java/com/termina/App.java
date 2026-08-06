package com.termina;

import com.termina.config.Settings;
import com.termina.ui.SettingsWindow;
import com.termina.ui.Theme;
import com.termina.ui.TerminalView;
import java.io.IOException;
import java.util.Locale;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/** Termina — a cross-platform terminal emulator on JavaFX. */
public final class App extends Application {

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    private Settings settings;
    private TerminalView terminal;
    private SettingsWindow settingsWindow;
    private Stage stage;

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
        this.stage = stage;

        settings = new Settings(Settings.defaultFile());
        settings.load();
        // Every settings change re-applies immediately — that is why the settings window has no OK
        // button. Choosing a font or a theme is a judgement about how something looks, and deferring
        // the result until a dialog is dismissed makes you guess.
        settings.setOnChange(this::applySettings);

        Theme theme = Theme.byId(settings.themeId(), Theme.EDITORA_DARK);
        Application.setUserAgentStylesheet(theme.stylesheet());

        terminal = new TerminalView(settings.fontSize());
        terminal.setFontFamily(settings.fontFamily());
        terminal.setPalette(theme.palette());
        terminal.setPreferredCursor(settings.cursorShape());
        terminal.setAltIsMeta(settings.altIsMeta());
        terminal.setBellEnabled(settings.bell());
        // Fixed at session start, so they must be set before start() rather than in applySettings.
        terminal.setSessionOptions(settings.scrollbackLines(), settings.shell());

        BorderPane root = new BorderPane(terminal);
        Scene scene = new Scene(root, 900, 560);
        scene.setFill(theme.palette().background());

        installShortcuts(scene);

        stage.titleProperty().bind(terminal.getDisplay().windowTitleProperty());
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> terminal.close());
        stage.show();

        terminal.requestFocus();

        try {
            terminal.start();
        } catch (IOException e) {
            showStartupFailure(e);
            return;
        }
        // The shell exiting closes the window, matching every other terminal.
        terminal.setOnSessionEnded(stage::close);

        if (DevCapture.requested()) {
            // -Dtermina.captureSettings photographs the settings window instead of the terminal.
            if (System.getProperty("termina.captureSettings") != null) {
                showSettings();
                DevCapture.schedule(settingsWindow.scene(), terminal);
            } else {
                DevCapture.schedule(scene, terminal);
            }
        }
    }

    /**
     * Re-applies everything that can change while a session is running.
     *
     * <p>Scrollback depth and the shell are deliberately absent: one sizes a buffer that already
     * exists and the other is a process already running, so both take effect in the next session.
     * The settings window says so on those two rows rather than appearing to apply and not.
     */
    private void applySettings() {
        Theme theme = Theme.byId(settings.themeId(), Theme.EDITORA_DARK);
        Application.setUserAgentStylesheet(theme.stylesheet());
        terminal.setPalette(theme.palette());
        if (stage != null && stage.getScene() != null) {
            stage.getScene().setFill(theme.palette().background());
        }
        terminal.setFontFamily(settings.fontFamily());
        terminal.setFontSize(settings.fontSize());
        terminal.setPreferredCursor(settings.cursorShape());
        terminal.setAltIsMeta(settings.altIsMeta());
        terminal.setBellEnabled(settings.bell());
    }

    /**
     * Application shortcuts. These are filters so they win over the terminal's own key handling —
     * without that, Cmd/Ctrl+V would be encoded and sent to the shell as a keystroke.
     */
    private void installShortcuts(Scene scene) {
        // SHORTCUT_DOWN is Cmd on macOS and Ctrl elsewhere. On macOS that keeps Ctrl+V free for
        // the shell (readline's "paste from kill ring"); on Linux/Windows Ctrl+Shift+V is the
        // convention precisely because plain Ctrl+V is Ctrl-V (literal-next) in readline.
        KeyCombination paste = MAC
                ? new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN)
                : new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        // Same reasoning, and more sharply: Ctrl+C is SIGINT and must reach the shell, so on
        // Linux/Windows copy has to be Ctrl+Shift+C.
        KeyCombination copy = MAC
                ? new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)
                : new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        KeyCombination preferences = new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN);

        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (preferences.match(e)) {
                showSettings();
                e.consume();
                return;
            }
            // Consumed only when something was actually copied, so a copy chord with an empty
            // selection stays available to whatever else might want it rather than silently
            // becoming a no-op key.
            if (copy.match(e) && terminal.copySelection()) {
                e.consume();
                return;
            }
            if (paste.match(e)) {
                terminal.paste();
                e.consume();
                return;
            }
            if (!e.isShortcutDown()) return;
            // Zoom. PLUS/EQUALS/ADD all reach here depending on layout and keypad.
            switch (e.getCode()) {
                case PLUS, EQUALS, ADD -> {
                    adjustFontSize(1);
                    e.consume();
                }
                case MINUS, SUBTRACT -> {
                    adjustFontSize(-1);
                    e.consume();
                }
                case DIGIT0, NUMPAD0 -> {
                    settings.setFontSize(Settings.DEFAULT_FONT_SIZE);
                    e.consume();
                }
                default -> {}
            }
        });
    }

    private void showSettings() {
        if (settingsWindow == null) settingsWindow = new SettingsWindow(settings);
        settingsWindow.show(stage);
    }

    /**
     * Zoom writes through the settings rather than straight to the view, so a size chosen with the
     * keyboard persists and shows up in the settings window like any other.
     */
    private void adjustFontSize(double delta) {
        settings.setFontSize(settings.fontSize() + delta);
    }

    private void showStartupFailure(IOException e) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Termina");
        alert.setHeaderText("Could not start a shell");
        alert.setContentText(String.valueOf(e.getMessage()));
        alert.showAndWait();
        Platform.exit();
    }

    @Override
    public void stop() {
        if (terminal != null) terminal.close();
    }
}
