package com.termina.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.termina.config.Settings;
import com.termina.term.TerminalSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window builds only what it shows (PERF-14), its palette lists every command (PAL-02), and a
 * closed window releases what it held (PERF-13, Pauta 2.0.2): its shell and the thread pool
 * its emulator runs on.
 *
 * <p>Real windows with real shells, because what leaks is exactly the part a stub would replace.
 * Two are opened and one closed: closing the last window ends the application, which would stop
 * the toolkit for every test after this one. Not on Windows, whose shell profiles have never run
 * (see AGENTS.md), so this would be their first run rather than a check of disposal.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class WindowLifecycleTest {

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
    void closingAWindowEndsItsShellAndItsThreads(@TempDir Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);

        // Pools alive before: other tests in this JVM may have shells of their own running.
        java.util.Set<String> before = sessionPools();
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow first = onFx(() -> windows.openWindow(null));
        TerminalWindow second = onFx(() -> windows.openWindow(first));
        // PERF-14: what a window does not show is not built with it.
        assertFalse(onFx(first::paletteBuilt), "the palette waits for its first open");
        assertFalse(onFx(windows::settingsWindowBuilt), "so does the settings window");
        onFx(() -> first.showPaletteForCapture(""));
        assertTrue(onFx(first::paletteBuilt), "and is built when it opens");

        // PAL-02: every registered command is a palette row, greyed if it cannot run now.
        assertEquals(java.util.List.of(), onFx(first::commandsNotInPalette));

        TerminalSession closing = sessionOf(first);
        sessionOf(second);
        // Each session runs its emulator on a pool of its own, termina-worker-N and termina-sched-N.
        waitFor(() -> mine(before).size() >= 2, "both sessions' threads to start");
        java.util.Set<String> withBoth = mine(before);

        onFx(() -> {
            first.stage().hide(); // the route every way out of a window takes: WINDOW_HIDDEN
            return null;
        });

        waitFor(() -> !closing.isRunning(), "the closed window's shell to end");
        String pool = String.valueOf(closing.threadPoolId());
        assertTrue(withBoth.contains(pool), "its pool was running: " + pool + " in " + withBoth);
        waitFor(() -> !sessionPools().contains(pool), "its session's threads to end");
        assertTrue(sessionOf(second).isRunning(), "the other window's shell is untouched");
        assertFalse(onFx(() -> windows.windows().contains(first)), "the manager let go of the window");

        // The other window is left open: closing it trips an assert inside JavaFX's Headless paint
        // collector (PaintCollector.removeDirtyScene) under -ea. Its shell ends with the forked test
        // JVM, whose exit closes the PTY and hangs the shell up.
    }

    private static TerminalSession sessionOf(TerminalWindow window) throws Exception {
        TerminalSession[] found = new TerminalSession[1];
        waitFor(
                () -> {
                    try {
                        found[0] = onFx(() -> {
                            TerminalView view = window.activeTerminal();
                            return view != null && view.isStarted() ? view.getSession() : null;
                        });
                    } catch (Exception e) {
                        return false;
                    }
                    return found[0] != null && found[0].isRunning();
                },
                "a shell to start");
        assertNotNull(found[0]);
        return found[0];
    }

    /** The session pools started since {@code before}: this test's own. */
    private static java.util.Set<String> mine(java.util.Set<String> before) {
        java.util.Set<String> pools = sessionPools();
        pools.removeAll(before);
        return pools;
    }

    /** The session thread pools alive now, by number: termina-worker-3 and termina-sched-3 are pool 3. */
    private static java.util.Set<String> sessionPools() {
        java.util.Set<String> pools = new java.util.TreeSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (!t.isAlive()) continue;
            if (name.startsWith("termina-worker-")) pools.add(name.substring("termina-worker-".length()));
            if (name.startsWith("termina-sched-")) pools.add(name.substring("termina-sched-".length()));
        }
        return pools;
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
        return result.get(20, TimeUnit.SECONDS);
    }
}
