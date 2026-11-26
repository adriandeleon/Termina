package com.termina.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import com.termina.testing.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One click anywhere on a menu row runs it. JavaFX runs a {@code CustomMenuItem} only from a click
 * on its content, so a click on the row's padding highlighted the row and did nothing, and it took
 * a second click, on the title, to run the command.
 */
@Tag("fx")
class MenuRowsClickFxTest {

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void aClickOnARowsPaddingRunsItOnce() throws Exception {
        List<String> ran = new ArrayList<>();
        Robot[] robot = new Robot[1];
        Stage owner = onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(), 400, 300));
            stage.setX(100);
            stage.setY(100);
            stage.show();
            return stage;
        });
        // One robot throughout: each keeps its own idea of where the pointer is and which window
        // is under it. It presses into the window that has the pointer, which a press gives it:
        // the owner's, before a menu opens over it.
        onFx(() -> {
            robot[0] = new Robot();
            robot[0].mouseMove(300, 250);
            robot[0].mouseClick(MouseButton.PRIMARY);
            return null;
        });
        frames(5);
        ContextMenu menu = onFx(() -> {
            MenuItem about = MenuRows.row("About Termina", null, false);
            about.setOnAction(e -> ran.add("about"));
            MenuItem other = MenuRows.row("A much longer title than the other", null, false);
            other.setOnAction(e -> ran.add("other"));
            return new ContextMenu(about, other);
        });
        // The row's left padding, and its right end beyond the shorter title's content.
        for (boolean leftEdge : new boolean[] {true, false}) {
            int before = ran.size();
            show(menu, owner);
            double[] at = waitForRow(menu);
            double x = leftEdge ? at[0] + 2 : at[2] - 3;
            onFx(() -> {
                robot[0].mouseMove(x, at[1]);
                return null;
            });
            frames(5);
            onFx(() -> {
                robot[0].mouseClick(MouseButton.PRIMARY);
                return null;
            });
            frames(5);
            String where = leftEdge ? "left padding" : "right end";
            assertEquals(List.of("about"), ran.subList(before, ran.size()), "a click on the " + where);
            assertFalse(onFx(menu::isShowing), "the menu closes, as after a click on the title");
        }
        // A click on the title still runs it exactly once: the forwarded click is not a second one.
        int before = ran.size();
        show(menu, owner);
        double[] title = waitForTitle(menu);
        onFx(() -> {
            robot[0].mouseMove(title[0], title[1]);
            return null;
        });
        frames(5);
        onFx(() -> {
            robot[0].mouseClick(MouseButton.PRIMARY);
            return null;
        });
        frames(5);
        assertEquals(List.of("about"), ran.subList(before, ran.size()));
        onFx(() -> {
            owner.hide();
            return null;
        });
    }

    @Test
    void aNodeIsWithinItselfAndItsAncestors() {
        StackPane outer = new StackPane();
        StackPane inner = new StackPane();
        outer.getChildren().add(inner);
        assertTrue(MenuRows.isWithin(inner, inner));
        assertTrue(MenuRows.isWithin(inner, outer));
        assertFalse(MenuRows.isWithin(outer, inner));
        assertFalse(MenuRows.isWithin(null, inner));
    }

    private static void show(ContextMenu menu, Stage owner) throws Exception {
        onFx(() -> {
            menu.show(owner, 150, 150);
            return null;
        });
    }

    /** The first row's container on screen: {left, centre y, right}. */
    private static double[] waitForRow(ContextMenu menu) throws Exception {
        frames(5);
        return onFx(() -> {
            Node row = menu.getItems().getFirst().getStyleableNode();
            Bounds b = row.localToScreen(row.getBoundsInLocal());
            return new double[] {b.getMinX(), b.getCenterY(), b.getMaxX()};
        });
    }

    /** The centre of the first row's title on screen. */
    private static double[] waitForTitle(ContextMenu menu) throws Exception {
        frames(5);
        return onFx(() -> {
            Node title = menu.getItems().getFirst().getStyleableNode().lookup(".menu-item-title");
            Bounds b = title.localToScreen(title.getBoundsInLocal());
            return new double[] {b.getCenterX(), b.getCenterY()};
        });
    }

    private static void frames(int count) throws InterruptedException {
        for (int i = 0; i < count; i++) Await.frame();
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}
