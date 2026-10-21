package com.termina.ui;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * What the Settings, About and Debug Log windows have in common.
 *
 * <p>None of them is owned by a terminal window. JavaFX closes an owned stage with its owner, and
 * an owner can be set only once, before the first show — so a settings window owned by the first
 * terminal window to open it vanished when that window closed, while three others stayed up. They
 * are placed next to the window that asked instead, which is the part of ownership anyone wanted.
 */
final class SecondaryWindows {

    /** How far in from the asking window's corner a secondary window opens. */
    static final double OFFSET = 48;

    private SecondaryWindows() {}

    /** Positions {@code stage} just inside {@code from}'s top-left corner, on {@code from}'s screen. */
    static void placeNear(Stage stage, Window from) {
        if (from == null || Double.isNaN(from.getX()) || Double.isNaN(from.getY())) return;
        Rectangle2D screen = screenOf(from);
        double width = stage.getWidth() > 0
                ? stage.getWidth()
                : stage.getScene() != null ? stage.getScene().getWidth() : 0;
        double height = stage.getHeight() > 0
                ? stage.getHeight()
                : stage.getScene() != null ? stage.getScene().getHeight() : 0;
        double[] at = WindowPlacement.offsetWithin(from.getX(), from.getY(), OFFSET, width, height, screen);
        stage.setX(at[0]);
        stage.setY(at[1]);
    }

    /** The visual bounds of the screen that holds most of {@code window}, or the primary one. */
    static Rectangle2D screenOf(Window window) {
        var screens = Screen.getScreensForRectangle(
                window.getX(), window.getY(), Math.max(1, window.getWidth()), Math.max(1, window.getHeight()));
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        return screen.getVisualBounds();
    }

    /**
     * Escape closes the window. A filter rather than an accelerator so a control that happens to
     * have focus — a text area, a list — cannot consume it on the way.
     */
    static void closeOnEscape(Stage stage, Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.hide();
                e.consume();
            }
        });
    }
}
