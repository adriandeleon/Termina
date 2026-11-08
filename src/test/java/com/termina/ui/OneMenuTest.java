package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One right-click menu at a time (MENU-06, Pauta 2.0.2). */
@Tag("fx")
class OneMenuTest {

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
    void openingASecondMenuClosesTheFirst() throws Exception {
        List<Boolean> showing = onFx(() -> {
            StackPane root = new StackPane();
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 300, 200));
            stage.show();
            ContextMenu tabMenu = OneMenu.install(new ContextMenu(new MenuItem("Rename Tab")));
            ContextMenu paneMenu = OneMenu.install(new ContextMenu(new MenuItem("Copy")));
            tabMenu.show(root, 10, 10);
            paneMenu.show(root, 50, 50);
            List<Boolean> state = List.of(tabMenu.isShowing(), paneMenu.isShowing());
            paneMenu.hide();
            stage.hide();
            return state;
        });
        assertEquals(List.of(false, true), showing, "one menu: the second, with the first closed");
    }

    @Test
    void everyContextMenuInTheAppGoesThroughOneMenu() throws IOException {
        // A ContextMenu made without it opens beside whatever is up; the failure is visual and quiet.
        List<String> bare = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                int made = count(text, "new ContextMenu(");
                int installed = count(text, "OneMenu.install(");
                if (file.getFileName().toString().equals("OneMenu.java")) continue;
                if (made > installed) bare.add(file + ": " + made + " made, " + installed + " installed");
            }
        }
        assertEquals(List.of(), bare);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
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
        return result.get(10, TimeUnit.SECONDS);
    }
}
