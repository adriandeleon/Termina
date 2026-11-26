package com.termina.ui;

import javafx.event.EventHandler;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import org.jspecify.annotations.Nullable;

/**
 * What the Settings, About and Debug Log windows have in common.
 *
 * <p>None of them is owned by a terminal window. JavaFX closes an owned stage with its owner, and
 * an owner can be set only once, before the first show — so a settings window owned by the first
 * terminal window to open it vanished when that window closed, while three others stayed up. They
 * are centred on the window that asked instead, which is the part of ownership anyone wanted.
 */
final class SecondaryWindows {

    private SecondaryWindows() {}

    /**
     * Centres {@code stage} on {@code from}, kept on {@code from}'s screen.
     *
     * <p>Before its first show a stage has no size, and most of these windows' scenes take theirs
     * from their content, so the size is the root's preferred one, laid out for the purpose. That
     * estimate leaves out the title bar and borders; once the window is up it is centred again with
     * the size it really has, which moves it by those few pixels at most.
     */
    static void placeOver(Stage stage, @Nullable Window from) {
        if (from == null || Double.isNaN(from.getX()) || Double.isNaN(from.getY())) return;
        double[] size = expectedSize(stage);
        centre(stage, from, size[0], size[1]);
        if (stage.getWidth() > 0 && stage.getHeight() > 0) return;
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, new EventHandler<>() {
            @Override
            public void handle(WindowEvent e) {
                stage.removeEventHandler(WindowEvent.WINDOW_SHOWN, this);
                if (!Double.isNaN(from.getX())) centre(stage, from, stage.getWidth(), stage.getHeight());
            }
        });
    }

    private static void centre(Stage stage, Window from, double width, double height) {
        double[] at = WindowPlacement.centredWithin(
                from.getX(), from.getY(), from.getWidth(), from.getHeight(), width, height, bounds(screenOf(from)));
        stage.setX(at[0]);
        stage.setY(at[1]);
    }

    /** The stage's size if it has been shown, else its scene's, else its content's preferred size. */
    private static double[] expectedSize(Stage stage) {
        if (stage.getWidth() > 0 && stage.getHeight() > 0) return new double[] {stage.getWidth(), stage.getHeight()};
        Scene scene = stage.getScene();
        if (scene == null) return new double[] {0, 0};
        if (scene.getWidth() > 0 && scene.getHeight() > 0) return new double[] {scene.getWidth(), scene.getHeight()};
        Parent root = scene.getRoot();
        root.applyCss();
        root.layout();
        double width = root.prefWidth(-1);
        return new double[] {width, root.prefHeight(width)};
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
        closeOnEscape(stage, scene, () -> false);
    }

    /**
     * {@link #closeOnEscape(Stage, Scene)}, but for a window with a find bar or a filter box: while
     * {@code claimed} says so, Escape is that field's, which closes its bar or empties itself and
     * leaves the window open (FIND-07, Pauta 6.0.1).
     */
    static void closeOnEscape(Stage stage, Scene scene, java.util.function.BooleanSupplier claimed) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !claimed.getAsBoolean()) {
                stage.hide();
                e.consume();
            }
        });
    }

    /** A JavaFX rectangle as {@link WindowPlacement}'s bounds; null for null. */
    static WindowPlacement.@Nullable Bounds bounds(javafx.geometry.@Nullable Rectangle2D r) {
        return r == null ? null : new WindowPlacement.Bounds(r.getMinX(), r.getMinY(), r.getMaxX(), r.getMaxY());
    }
}
