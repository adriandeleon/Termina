package com.termina.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.termina.testing.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KBD-04 (Pauta 6.0.1): a greyed menu row stays in the menu and says why under the pointer. */
@Tag("fx")
class MenuReasonsFxTest {

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
    void aGreyedRowShowsItsReasonUnderThePointerAndAnotherRowDoesNot() throws Exception {
        // Built on the FX thread: a control made on this one while the toolkit is still starting
        // deadlocks the two threads on each other's class initialisation.
        MenuItem open = onFx(() -> new MenuItem("Open"));
        MenuItem reopen = onFx(() -> new MenuItem("Reopen Closed Tab"));
        MenuItem bare = onFx(() -> new MenuItem("Greyed by something else"));
        ContextMenu menu = onFx(() -> {
            bare.setDisable(true);
            return new ContextMenu(open, reopen, bare);
        });
        Stage stage = onFx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(new StackPane(), 300, 200));
            s.show();
            MenuReasons.set(reopen, "No tab has been closed");
            menu.show(s, 100, 100);
            MenuReasons.attach(menu.getItems());
            MenuReasons.attach(menu.getItems()); // every showing calls it: watched once
            return s;
        });
        try {
            assertTrue(reopen.isDisable());
            assertEquals("No tab has been closed", MenuReasons.of(reopen));

            Node row = onFx(reopen::getStyleableNode);
            assertNotNull(row, "the row's node");
            Tooltip tooltip = MenuReasons.tooltipOf(row.getParent());
            assertNotNull(tooltip);
            onFx(() -> {
                tooltip.setShowDelay(javafx.util.Duration.millis(1));
                return null;
            });

            // A real pointer, through the scene's own picking: the disabled row takes no event,
            // and the rows' parent must.
            moveOver(row);
            waitFor(() -> tooltip.isShowing());
            assertEquals("No tab has been closed", tooltip.getText());

            moveOver(onFx(open::getStyleableNode));
            waitFor(() -> !tooltip.isShowing());
            moveOver(onFx(bare::getStyleableNode));
            // The tooltip's one-millisecond delay runs on frames; five of them is long past it.
            Await.frames(5);
            assertFalse(onFx(tooltip::isShowing), "a row with no reason says nothing");

            // Available again: no longer greyed, and nothing left to say.
            onFx(() -> {
                MenuReasons.set(reopen, null);
                return null;
            });
            assertFalse(reopen.isDisable());
            assertNull(MenuReasons.of(reopen));
        } finally {
            onFx(() -> {
                menu.hide();
                stage.hide();
                return null;
            });
        }
    }

    private static void moveOver(Node node) throws Exception {
        onFx(() -> {
            Bounds on = node.localToScreen(node.getBoundsInLocal());
            new javafx.scene.robot.Robot().mouseMove(on.getCenterX(), on.getCenterY());
            return null;
        });
        // A second move a pixel on: the tooltip's own timer starts on the move after it is installed.
        onFx(() -> {
            Bounds on = node.localToScreen(node.getBoundsInLocal());
            new javafx.scene.robot.Robot().mouseMove(on.getCenterX() + 1, on.getCenterY());
            return null;
        });
    }

    private static void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!onFx(condition)) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Await.frame();
        }
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
