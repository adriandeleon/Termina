package com.termina.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.stage.Stage;

import com.termina.config.Settings;
import com.termina.pty.LaunchOptions;
import com.termina.testing.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * WIN-08 (Pauta 6.0.1), end to end: tabs opened in a real window are written down when the
 * application goes, and the next first window opens with them, each shell in its directory, the
 * names kept and the same tab in front.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class SessionRestoreFxTest {

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
    void theTabsOpenAtTheEndAreTheTabsOfTheNextLaunch() throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        Path work = Files.createDirectories(dir.resolve("work")).toRealPath();
        Settings settings = new Settings(config.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setConfirmClose(false);

        // The first run: the tab every window starts with, and a named one in a directory.
        WindowManager before = onFx(() -> {
            WindowManager windows = new WindowManager(settings);
            windows.endAppWithForTest(() -> {});
            windows.keepSession();
            return windows;
        });
        TerminalWindow first = onFx(() -> before.openWindow(null));
        waitFor(() -> tabCount(first) == 1 && TerminalView.shellsStarting() == 0, "the first tab");
        onFx(() -> {
            first.openTab(LaunchOptions.ofShell(settings.shell()).withWorkingDirectory(work.toString()), "build");
            return null;
        });
        waitFor(() -> tabCount(first) == 2 && TerminalView.shellsStarting() == 0, "the second tab");
        onFx(() -> {
            before.closeAll();
            return null;
        });
        assertFalse(settings.sessionTabs().isEmpty(), "quitting writes the session");
        ClosedTabs.Window written = SessionStore.parse(settings.sessionTabs(), Files::isDirectory);
        assertEquals(2, written.tabs().size());
        assertEquals(1, written.selected(), "the tab in front");

        // The next launch: what the application does before its first window, then that window.
        SessionStore.warm(settings.sessionTabs());
        WindowManager after = onFx(() -> {
            WindowManager windows = new WindowManager(settings);
            windows.endAppWithForTest(() -> {});
            windows.keepSession();
            return windows;
        });
        TerminalWindow restored = onFx(() -> after.openFirstWindow(new Stage(), null, ""));
        waitFor(() -> tabCount(restored) == 2 && TerminalView.shellsStarting() == 0, "the restored tabs");
        List<String> seen = onFx(() -> {
            List<TerminalView> panes = restored.terminals();
            TerminalView second = panes.get(1);
            return List.of(
                    String.valueOf(panes.size()),
                    second.getDisplay().getUserTitle(),
                    Path.of(second.launchOptions().workingDirectory())
                            .toRealPath()
                            .toString(),
                    String.valueOf(restored.tabsForTest().indexOf(restored.selectedTabForTest())));
        });
        assertEquals(List.of("2", "build", work.toString(), "1"), seen);

        // With the setting off, the end of the run removes what was kept.
        onFx(() -> {
            settings.setRestoreSession(false);
            after.closeAll();
            return null;
        });
        assertEquals("", settings.sessionTabs(), "turned off, no session is left to come back");
    }

    @Test
    void closingTheLastWindowKeepsItsTabsAndACommandLineGetsATabOfItsOwn() throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        Path asked = Files.createDirectories(dir.resolve("asked")).toRealPath();
        Settings settings = new Settings(config.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setConfirmClose(false);

        WindowManager before = onFx(() -> {
            WindowManager windows = new WindowManager(settings);
            windows.endAppWithForTest(() -> {});
            windows.keepSession();
            return windows;
        });
        TerminalWindow other = onFx(() -> before.openWindow(null));
        TerminalWindow last = onFx(() -> before.openWindow(other));
        waitFor(
                () -> tabCount(other) == 1 && tabCount(last) == 1 && TerminalView.shellsStarting() == 0,
                "both windows' tabs");
        // A window closed with another still open is not the session.
        onFx(() -> {
            other.stage().hide();
            return null;
        });
        assertEquals("", settings.sessionTabs());
        onFx(() -> {
            last.stage().hide(); // the last one, as its close button closes it
            return null;
        });
        assertEquals(
                1,
                SessionStore.parse(settings.sessionTabs(), Files::isDirectory)
                        .tabs()
                        .size());

        // The next launch names a directory: the session's tab, and after it the one asked for.
        SessionStore.warm(settings.sessionTabs());
        WindowManager after = onFx(() -> {
            WindowManager windows = new WindowManager(settings);
            windows.endAppWithForTest(() -> {});
            return windows;
        });
        TerminalWindow restored = onFx(() -> after.openFirstWindow(
                new Stage(), LaunchOptions.ofShell(settings.shell()).withWorkingDirectory(asked.toString()), ""));
        waitFor(() -> tabCount(restored) == 2 && TerminalView.shellsStarting() == 0, "the session's tab and the asked");
        String place =
                onFx(() -> Path.of(restored.terminals().get(1).launchOptions().workingDirectory())
                        .toRealPath()
                        .toString());
        assertEquals(asked.toString(), place);
        onFx(() -> {
            after.closeAll();
            return null;
        });
    }

    private static int tabCount(TerminalWindow window) {
        try {
            return onFx(() -> window.tabsForTest().size());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
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
        return result.get(20, TimeUnit.SECONDS);
    }
}
