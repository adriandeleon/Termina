package com.termina;

import com.termina.ui.TerminalView;
import java.io.IOException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/** Termina — a cross-platform terminal emulator on JavaFX. */
public final class App extends Application {

    private static final double DEFAULT_FONT_SIZE = 13;

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");

    private TerminalView terminal;

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
        terminal = new TerminalView(DEFAULT_FONT_SIZE);

        BorderPane root = new BorderPane(terminal);
        Scene scene = new Scene(root, 900, 560);
        scene.setFill(Color.web("#14161c"));

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

        if (DevCapture.requested()) DevCapture.schedule(scene, terminal);
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
        // Same reasoning as paste, and more sharply: Ctrl+C is SIGINT and must reach the shell, so
        // on Linux/Windows copy has to be Ctrl+Shift+C.
        KeyCombination copy = MAC
                ? new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)
                : new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
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
                    fontSize = DEFAULT_FONT_SIZE;
                    terminal.setFontSize(fontSize);
                    e.consume();
                }
                default -> {}
            }
        });
    }

    private double fontSize = DEFAULT_FONT_SIZE;

    private void adjustFontSize(double delta) {
        fontSize = Math.max(7, Math.min(40, fontSize + delta));
        terminal.setFontSize(fontSize);
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
