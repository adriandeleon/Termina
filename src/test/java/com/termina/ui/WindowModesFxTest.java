package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.termina.config.Settings;
import com.termina.testing.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.termina.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window's modes in a real window (WIN-10, MENU-04, Pauta 6.0.1): Zen and Expert strip this
 * window's chrome and write no setting, and Simple mode swaps the bar's menus only when it changes.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class WindowModesFxTest {

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
    void zenAndExpertStripThisWindowsChromeAndLeavingBringsItBack() throws Exception {
        Settings settings = new Settings(dir.resolve("modes.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setHideTabBarWhenSingle(false); // the strip shows with one tab, so its going is seen
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        TerminalWindow other = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window) && started(other), "the shells to start");

        List<String> seen = onFx(() -> {
            List<String> out = new ArrayList<>();
            out.add(window.chromeForTest());
            run(window, "view.toggleZen");
            out.add(window.chromeForTest() + " | " + label(window, "view.toggleZen"));
            // This window's alone, and no setting was written for it.
            out.add(other.chromeForTest() + " | " + settings.showMenuBar() + settings.showTabBar()
                    + settings.showStatusBar());
            // From Zen straight to Expert: one at a time, and the status bar is back.
            run(window, "view.toggleExpert");
            out.add(window.chromeForTest() + " | " + label(window, "view.toggleZen") + " | "
                    + label(window, "view.toggleExpert"));
            // A mode only takes away: the status bar switched off in Settings stays off in Expert.
            settings.setShowStatusBar(false);
            out.add(window.chromeForTest());
            settings.setShowStatusBar(true);
            run(window, "view.toggleExpert");
            out.add(window.chromeForTest());
            // The keyboard is the terminal's all along.
            out.add(String.valueOf(window.stage().getScene().getFocusOwner() == window.activeTerminal()));
            return out;
        });
        assertEquals("none menuBar=true tabs=true status=true swaps=0", seen.get(0));
        assertEquals("zen menuBar=false tabs=false status=false swaps=0 | " + tr("menu.leaveZen"), seen.get(1));
        assertEquals("none menuBar=true tabs=true status=true swaps=0 | truetruetrue", seen.get(2));
        assertEquals(
                "expert menuBar=false tabs=false status=true swaps=0 | " + tr("command.view.toggleZen") + " | "
                        + tr("menu.leaveExpert"),
                seen.get(3));
        assertEquals("expert menuBar=false tabs=false status=false swaps=0", seen.get(4));
        assertEquals("none menuBar=true tabs=true status=true swaps=0", seen.get(5));
        assertEquals("true", seen.get(6));
        // Entering a mode says how to leave it, where it can still be read.
        assertTrue(
                onFx(() -> windows.messages().entries().stream()
                        .anyMatch(entry -> entry.text().equals(tr("mode.zen.entered")))),
                "the Message Log says the palette has the way out");
    }

    @Test
    void simpleModeShortensTheBarAndSwapsItOnlyWhenTheModeChanges() throws Exception {
        Settings settings = new Settings(dir.resolve("simple.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");

        List<String> seen = onFx(() -> {
            List<String> out = new ArrayList<>();
            out.add(menus(window) + " " + window.chromeForTest());
            run(window, "view.toggleSimpleMode");
            out.add(menus(window) + " " + window.chromeForTest() + " " + settings.simpleMode());
            // MENU-04: other settings change, and the bar's menus are left as they are.
            settings.setShowStatusBar(false);
            settings.setShowStatusBar(true);
            settings.setFontSize(settings.fontSize() + 1);
            out.add(window.chromeForTest());
            // The shorter bar still has the way back, named for where it leads, and every command
            // keeps its chord and its palette row.
            out.add(window.menuReport().contains(tr("menu.leaveSimpleMode")) + " "
                    + (window.shortcuts().size() > 20) + " "
                    + window.runnablePaletteRowsForTest().stream().anyMatch(row -> "pane.splitRight".equals(row.id())));
            run(window, "view.toggleSimpleMode");
            out.add(menus(window) + " " + window.chromeForTest());
            return out;
        });
        assertEquals("5 none menuBar=true tabs=false status=true swaps=0", seen.get(0));
        assertEquals("4 none+simple menuBar=true tabs=false status=true swaps=1 true", seen.get(1));
        assertEquals("none+simple menuBar=true tabs=false status=true swaps=1", seen.get(2), "not rebuilt");
        assertEquals("true true true", seen.get(3));
        assertEquals("5 none menuBar=true tabs=false status=true swaps=2", seen.get(4));

        // A window opened while the mode is on starts with the shorter bar, and counts no swap.
        onFx(() -> {
            settings.setSimpleMode(true);
            return null;
        });
        TerminalWindow later = onFx(() -> windows.openWindow(null));
        assertEquals(
                "4 none+simple",
                onFx(() -> menus(later) + " " + later.chromeForTest().split(" ")[0]));
        assertFalse(onFx(later::chromeForTest).contains("swaps=1"));
    }

    private static int menus(TerminalWindow window) {
        return window.menuBarForTest().getMenus().size();
    }

    private static String label(TerminalWindow window, String id) {
        for (MenuAction row : window.runnablePaletteRowsForTest()) {
            if (id.equals(row.id())) return row.label();
        }
        throw new AssertionError(id + " is not runnable");
    }

    private static void run(TerminalWindow window, String id) {
        for (MenuAction row : window.runnablePaletteRowsForTest()) {
            if (id.equals(row.id())) {
                row.action().run();
                return;
            }
        }
        throw new AssertionError(id + " is not runnable");
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
