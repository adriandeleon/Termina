package com.termina.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.termina.config.Settings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shell starts on a worker and its session is built on the FX thread (PERF-01, Pauta 2.0.2): the
 * new tab is there at once and its shell a moment later, it inherits the directory the current tab's
 * shell is in — read from the operating system on that worker — and a tab closed before its shell
 * has started leaves nothing behind.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class ShellStartFxTest {

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
    void aNewTabStartsInTheDirectoryTheCurrentShellIsIn() throws Exception {
        Path place = Files.createDirectories(dir.resolve("work place"));
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setConfirmClose(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));
        waitFor(() -> started(window), "the first shell");
        onFx(() -> {
            TerminalView view = window.activeTerminal();
            if (view != null) view.getSession().sendString("cd '" + place + "'\r");
            return null;
        });
        // The directory the OS reports for the shell, once the cd has run.
        waitFor(
                () -> {
                    try {
                        TerminalView view = onFx(window::activeTerminal);
                        return view != null
                                && place.toString()
                                        .equals(TerminalWindow.directoryOf(
                                                onFx(() -> view.getSession().pid()), ""));
                    } catch (Exception e) {
                        return false;
                    }
                },
                "the shell to be in the new directory");

        TerminalView second = onFx(() -> {
            window.openTab((com.termina.shell.Profile) null);
            TerminalView view = window.activeTerminal();
            // There at once, before its shell: the start runs on a worker.
            assertTrue(view != null && window.tabsForTest().size() == 2);
            return view;
        });
        waitFor(() -> onFxQuiet(second::isStarted), "the new tab's shell");
        assertEquals(place.toString(), onFx(() -> second.launchOptions().workingDirectory()));
    }

    @Test
    void aTabClosedBeforeItsShellStartsLeavesNothingRunning() throws Exception {
        Settings settings = new Settings(dir.resolve("closed.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setConfirmClose(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));
        waitFor(() -> started(window), "the first shell");

        TerminalView doomed = onFx(() -> {
            window.openTab();
            TerminalView view = window.activeTerminal();
            window.closeCurrentTab(); // in the same pulse: the shell has not been handed back yet
            return view;
        });
        Thread.sleep(1500);
        assertFalse(onFx(doomed::isStarted), "no session is built for a closed tab");
        assertEquals(1, onFx(() -> window.tabsForTest().size()));
    }

    private static boolean started(TerminalWindow window) {
        return onFxQuiet(() -> {
            TerminalView view = window.activeTerminal();
            return view != null && view.isStarted();
        });
    }

    private static boolean onFxQuiet(java.util.concurrent.Callable<Boolean> check) {
        try {
            return onFx(check);
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(50);
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
