package com.termina.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.termina.config.Settings;
import com.termina.pty.LaunchOptions;
import com.termina.update.ReleaseInfo;
import com.termina.update.UpdateCheck;
import com.termina.update.UpdateService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the window manager does across windows: an update found, opened and dismissed; a check that
 * failed; a second launch handed over; a closed window brought back. The update answers are given to
 * it directly — the network is {@code UpdateServiceTest}'s — and links go to a recorder, not a browser.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class WindowManagerFxTest {

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
    void updatesHandOffsAndAReopenedWindow() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        settings.setConfirmClose(false);
        List<String> opened = new CopyOnWriteArrayList<>();

        WindowManager windows = onFx(() -> new WindowManager(settings));
        onFx(() -> {
            windows.setLinkOpener(opened::add);
            return null;
        });
        TerminalWindow first = onFx(() -> windows.openWindow(null));
        TerminalWindow keeper = onFx(() -> windows.openWindow(first));
        waitFor(() -> started(first), "the shell");

        ReleaseInfo release = new ReleaseInfo("99.0.0", "https://example.com/releases/99.0.0", "Termina 99.0.0");
        onFx(() -> {
            windows.showAbout(first.stage());
            windows.onUpdateOutcome(new UpdateService.Outcome(true, release, null, null), false);
            windows.openReleasePage();
            windows.dismissCurrentUpdate();
            windows.onUpdateOutcome(
                    new UpdateService.Outcome(false, null, UpdateCheck.ErrorKind.OFFLINE, "no route"), true);
            windows.onUpdateOutcome(UpdateService.Outcome.none(), true);
            return null;
        });
        assertEquals(List.of(release.url()), opened, "the release page went to the link opener");
        assertEquals("99.0.0", settings.dismissedUpdate());

        // A second launch's request: a window in that directory.
        int before = onFx(() -> windows.windows().size());
        onFx(() -> windows.openHandedOff(new LaunchOptions("", dir.toString(), List.of()), "handed over"));
        assertEquals(before + 1, onFx(() -> windows.windows().size()));

        // A window closed, then brought back.
        onFx(() -> {
            first.stage().hide();
            return null;
        });
        int afterClose = onFx(() -> windows.windows().size());
        onFx(() -> {
            windows.reopenClosedWindow(keeper);
            return null;
        });
        assertTrue(onFx(() -> windows.windows().size()) > afterClose, "the closed window came back");
    }

    private static boolean started(TerminalWindow window) {
        try {
            return onFx(() -> {
                TerminalView view = window.activeTerminal();
                return view != null && view.isStarted();
            });
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
