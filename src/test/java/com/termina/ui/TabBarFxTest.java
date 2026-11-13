package com.termina.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.termina.config.Settings;
import com.termina.i18n.Messages;
import com.termina.testing.Await;
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
 * A command hides the tab bar with any number of tabs open (TABS-12, Pauta 6.0.1), and is named
 * for the way it will go.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class TabBarFxTest {

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
    void theCommandHidesTheStripWithThreeTabsOpenAndShowsItAgain() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> {
            window.openTab();
            window.openTab();
            return null;
        });
        waitFor(() -> tabs(window) == 3, "three tabs");

        assertFalse(onFx(() -> hidden(window)), "three tabs: the strip shows");
        assertEquals(Messages.tr("menu.hideTabBar"), label(window));
        run(window);
        assertTrue(onFx(() -> hidden(window)), "hidden, with three tabs open");
        assertFalse(settings.showTabBar());
        assertEquals(3, tabs(window), "the tabs are still there");
        assertEquals(Messages.tr("command.view.toggleTabBar"), label(window));
        run(window);
        assertFalse(onFx(() -> hidden(window)), "and back");
        assertTrue(settings.showTabBar());
    }

    private static boolean hidden(TerminalWindow window) {
        return window.tabsForTest().getFirst().getTabPane().getStyleClass().contains("hide-tab-bar");
    }

    private static int tabs(TerminalWindow window) {
        try {
            return onFx(() -> window.tabsForTest().size());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String label(TerminalWindow window) throws Exception {
        return onFx(() -> window.runnablePaletteRowsForTest().stream()
                .filter(row -> "view.toggleTabBar".equals(row.id()))
                .findFirst()
                .orElseThrow()
                .label());
    }

    private static void run(TerminalWindow window) throws Exception {
        onFx(() -> {
            window.runnablePaletteRowsForTest().stream()
                    .filter(row -> "view.toggleTabBar".equals(row.id()))
                    .findFirst()
                    .orElseThrow()
                    .action()
                    .run();
            return null;
        });
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
        return result.get(30, TimeUnit.SECONDS);
    }
}
