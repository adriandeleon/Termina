package com.termina;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.stage.Stage;

import com.termina.testing.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application's own start, on a stage the test gives it: settings read from the folder the
 * command line names, the catalogue and theme chosen, the first window opened on the shell with the
 * working directory and title asked for. Links go nowhere; the update check is off in that folder.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class AppStartFxTest {

    @TempDir
    Path dir;

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
    void theApplicationStartsOnTheStageItIsGiven() throws Exception {
        Path config = dir.resolve("config");
        Files.createDirectories(config);
        Files.writeString(
                config.resolve("settings.json"),
                "{\n  \"schemaVersion\" : 1,\n  \"settings\" : {\n    \"updates.check\" : false\n  }\n}\n");
        // The last run's session, as it is written into the state file: one tab, named (WIN-08,
        // Pauta 6.0.1).
        Files.writeString(
                config.resolve(com.termina.config.Settings.STATE_FILE),
                "{ \"schemaVersion\": 2, \"settings\": { \"session.tabs\": \"{\\\"version\\\":1,\\\"selected\\\":0,"
                        + "\\\"tabs\\\":[{\\\"pinned\\\":false,\\\"active\\\":0,\\\"panes\\\":"
                        + "{\\\"shell\\\":\\\"\\\",\\\"directory\\\":\\\"\\\",\\\"profile\\\":\\\"\\\","
                        + "\\\"title\\\":\\\"kept\\\"}}]}\" } }");
        // This process claims the folder, as the first launch does; start() then listens there.
        String[] args = {"--config-dir", config.toString()};
        App.hold(Main.claimOrHandOff(com.termina.cli.CommandLine.parse(args), args));
        Stage stage = onFx(Stage::new);
        App app = new App();
        onFx(() -> {
            app.start(
                    stage,
                    List.of("--config-dir", config.toString(), "-d", dir.toString(), "-T", "started"),
                    url -> {});
            return null;
        });
        assertTrue(onFx(stage::isShowing), "the first window is the stage it was given");

        // The session's tab came back, and the tab the command line asked for is after it.
        long opened = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (onFx(() -> app.terminalTitlesForTest().size()) < 2 && System.nanoTime() < opened) {
            Await.frame();
        }
        assertEquals(List.of("kept", "started"), onFx(app::terminalTitlesForTest));

        // A second launch hands its request over and the running one opens a window for it.
        int before = onFx(() -> javafx.stage.Window.getWindows().size());
        assertTrue(com.termina.instance.SingleInstance.handOff(
                config,
                new com.termina.instance.SingleInstance.Request(dir.toString(), List.of("-T", "handed over")),
                java.time.Duration.ofSeconds(5)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (onFx(() -> javafx.stage.Window.getWindows().size()) <= before && System.nanoTime() < deadline) {
            Await.frame();
        }
        assertTrue(onFx(() -> javafx.stage.Window.getWindows().size()) > before, "a window for the second launch");
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
