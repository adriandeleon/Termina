package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Menu;
import javafx.scene.control.Tab;
import javafx.stage.Window;

import com.termina.config.Settings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every control a person can reach has a name (ACC-01, Pauta 6.0.1): the sweep opens each window
 * the app has, every page of Settings, the find bar, the palette and every menu, and fails on a
 * control, a list cell or a menu row a screen reader would announce as nothing. The dialogs are
 * swept as they are answered, by {@link DialogAnswers}, and held by {@code DialogsFxTest}.
 *
 * <p>{@link IconOnlyButtonsFxTest} holds the narrower thing, that an icon-only button's name and
 * its tooltip say the same.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class NamedControlsFxTest {

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
    void everyControlInEveryWindowAndMenuHasAName() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        // Only the windows this test opens: other tests' windows share the toolkit, and are theirs.
        java.util.Set<Window> before = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        onFx(() -> before.addAll(Window.getWindows()));
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell");
        onFx(() -> {
            window.openTab(); // two tabs: the strip shows, with a close button on each
            TerminalView view = window.activeTerminal();
            if (view != null) view.showSearch();
            windows.messages().add(MessageLog.Severity.ERROR, "something failed");
            DebugLog.append("a line for the debug log");
            windows.announceUpdateForCapture("99.0.0"); // the status bar's link
            return null;
        });
        Thread.sleep(300);

        List<String> unnamed = new ArrayList<>();
        List<String> unglyphed = new ArrayList<>();
        int checked = onFx(() -> {
            // Every window the app has, each in the state that shows the most.
            windows.showSettings(window.stage());
            windows.showAbout(window.stage());
            windows.showPauta(window.stage());
            windows.showReportProblem(window.stage());
            windows.debugLog(window.stage()).find();
            windows.showMessageLog(window.stage());
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("keyboardShortcuts".equals(row.id())) row.action().run();
            }
            // And the palette, over the terminal: its field and the rows it lists.
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("palette.show".equals(row.id())) row.action().run();
            }

            List<Node> roots = new ArrayList<>();
            for (Window open : List.copyOf(Window.getWindows())) {
                if (before.contains(open)) continue;
                if (open.isShowing() && open.getScene() != null)
                    roots.add(open.getScene().getRoot());
            }
            // Only the page on show is in the Settings scene: the rest are swept on their own.
            roots.addAll(windows.settingsWindowForTest().pagesForTest());

            int count = 0;
            java.util.Set<Node> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Node root : roots) {
                if (!seen.add(root)) continue;
                root.applyCss();
                if (root instanceof javafx.scene.Parent parent) parent.layout();
                unnamed.addAll(NamedControls.unnamed(root));
                // A control's own right-click menu: the two logs', the profile list's.
                unnamed.addAll(NamedControls.unnamedMenus(root));
                unglyphed.addAll(NamedControls.menusWithoutGlyph(root));
                count += NamedControls.checked(root);
            }

            // The menus that hang off no control: the bar's, a tab's and the terminal's.
            for (Menu menu : window.menuBarForTest().getMenus()) {
                unnamed.addAll(NamedControls.unnamedRows(menu.getItems()));
                // The macOS screen menu bar draws no icons: its rows are outside MENU-14.
                if (!TerminalWindow.SYSTEM_MENU_BAR) {
                    javafx.event.Event.fireEvent(menu, new javafx.event.Event(Menu.ON_SHOWING));
                    unglyphed.addAll(NamedControls.rowsWithoutGlyph(menu.getItems()));
                }
            }
            for (Tab tab : window.tabsForTest()) {
                unnamed.addAll(NamedControls.unnamedRows(tab.getContextMenu().getItems()));
                unglyphed.addAll(
                        NamedControls.rowsWithoutGlyph(tab.getContextMenu().getItems()));
            }
            unglyphed.addAll(NamedControls.rowsWithoutGlyph(window.profileMenuItemsForTest()));
            TerminalView view = window.activeTerminal();
            if (view != null)
                unnamed.addAll(
                        NamedControls.unnamedRows(view.contextMenuForTest().getItems()));
            if (view != null)
                unglyphed.addAll(
                        NamedControls.rowsWithoutGlyph(view.contextMenuForTest().getItems()));
            for (Window open : List.copyOf(Window.getWindows())) {
                if (before.contains(open)) continue;
                if (open.isShowing() && open instanceof javafx.stage.Stage && open != window.stage()) open.hide();
            }
            return count;
        });
        assertEquals(List.of(), unnamed.stream().distinct().toList());
        // MENU-14 (Pauta 6.0.1): every row of every menu the app draws carries a glyph.
        assertEquals(List.of(), unglyphed.stream().distinct().toList());
        assertTrue(checked >= 80, "controls and cells looked at: " + checked);
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
