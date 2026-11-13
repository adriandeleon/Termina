package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.termina.config.Settings;
import com.termina.pty.ShellLauncher;
import com.termina.testing.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The paths the other UI tests leave out because they need care rather than because they matter
 * less: the menu items that reach a browser or the home folder (with both stubbed), a tab renamed
 * by double-clicking its header, a shell that exits inside a split, a view used before any window
 * wires it, and the debug log's own handler.
 */
@Tag("fx")
class RemainingPathsFxTest {

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
    void aViewBeforeAnyWindowWiresIt() throws Exception {
        // Its own defaults run: every callback a window would replace is a harmless no-op here.
        int fired = onFx(() -> {
            TerminalView view = new TerminalView(13);
            int count = 0;
            for (MenuItem item : view.contextMenuForTest().getItems()) {
                if (item.isDisable()) continue;
                item.fire();
                count++;
            }
            view.getDisplay().getWindowTitle();
            view.getDisplay().getProgram();
            view.prefWidth(-1);
            view.prefHeight(-1);
            return count;
        });
        assertTrue(fired > 3, "items fired: " + fired);
    }

    @Test
    void theDebugLogsOwnHandlerTakesWhatJavaLoggingSays() throws Exception {
        DebugLog.install();
        java.util.logging.Logger.getLogger("com.termina.test").warning("a warning through java.util.logging");
        for (java.util.logging.Handler handler :
                java.util.logging.Logger.getLogger("").getHandlers()) handler.flush();
        assertTrue(DebugLog.text().contains("a warning through java.util.logging"));
    }

    @Test
    void theLaunchersChecksOnRealPaths() {
        assertTrue(ShellLauncher.validateDirectory(dir.toString()) != null);
        assertTrue(ShellLauncher.validateDirectory(dir.resolve("missing").toString()) != null);
        assertTrue(ShellLauncher.validateShellPath(dir.resolve("missing-shell").toString()) != null);
        assertTrue(ShellLauncher.validateShellPath("/bin/sh") != null);
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
    void menuItemsThatGoOutsideAndAShellThatExitsInASplit() throws Exception {
        Path home = dir.resolve("home");
        java.nio.file.Files.createDirectories(home);
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        settings.setConfirmClose(false);
        List<String> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
        WindowManager windows = onFx(() -> new WindowManager(settings));
        onFx(() -> {
            windows.setLinkOpener(opened::add);
            return null;
        });
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));
        waitFor(() -> started(window), "the shell");

        String was = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            List<String> keys = List.of(
                    "command.help.documentation",
                    "command.whatsNew",
                    "command.exportConfiguration",
                    "command.renameTab");
            onFx(() -> {
                for (javafx.scene.control.Menu menu : window.menuBarForTest().getMenus()) {
                    for (MenuItem item : new ArrayList<>(menu.getItems())) {
                        for (String key : keys) {
                            if (com.termina.i18n.Messages.tr(key).equals(MenuRows.titleOf(item))) {
                                DialogAnswers.next(true, "renamed");
                                item.fire();
                            }
                        }
                    }
                }
                return null;
            });
        } finally {
            System.setProperty("user.home", was);
        }
        assertTrue(opened.size() >= 2, "Documentation and What's New went to the opener: " + opened);

        // The palette, run from the keyboard on a harmless row.
        onFx(() -> {
            window.showPaletteForCapture("zoom in");
            window.firePaletteKeysForCapture("enter");
            return null;
        });

        // A tab renamed by double-clicking its header.
        onFx(() -> {
            window.openTab();
            for (Tab tab : window.tabsForTest()) {
                Node graphic = tab.getGraphic();
                Node header = graphic;
                while (header != null && !header.getStyleClass().contains("tab")) header = header.getParent();
                if (header == null) continue;
                DialogAnswers.next(true, "double-clicked");
                Event.fireEvent(
                        header,
                        new MouseEvent(
                                MouseEvent.MOUSE_CLICKED,
                                5,
                                5,
                                5,
                                5,
                                MouseButton.PRIMARY,
                                2,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                true,
                                null));
                break;
            }
            return null;
        });

        // A split tab closed from the tab beside it, then brought back with both its panes.
        onFx(() -> {
            window.openTab();
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("splitRight".equals(row.id())) row.action().run();
            }
            return null;
        });
        waitFor(() -> TerminalView.shellsStarting() == 0 && started(window), "the split's shells");
        onFx(() -> {
            window.tabsForTest().get(0).getTabPane().getSelectionModel().select(0);
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("closeTabsToTheRight".equals(row.id())) row.action().run();
            }
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("reopenClosedTab".equals(row.id())) row.action().run();
            }
            return null;
        });
        waitFor(() -> TerminalView.shellsStarting() == 0 && started(window), "the reopened tab's shells");
        // Close Tab in a split tab closes the active pane, not the tab.
        onFx(() -> {
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("closeTab".equals(row.id())) row.action().run();
            }
            return null;
        });

        // A split, and the shell in one pane exits: the pane goes, the tab stays.
        onFx(() -> {
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("splitRight".equals(row.id())) row.action().run();
            }
            return null;
        });
        waitFor(() -> started(window), "the new pane's shell");
        TerminalView leaving = onFx(() -> {
            TerminalView view = window.activeTerminal();
            if (view != null) view.getSession().sendString("exit\r");
            return view;
        });
        waitFor(() -> active(window) != leaving, "the pane whose shell exited to go");
        assertTrue(onFx(() -> window.tabsForTest().size()) >= 1, "the tab outlived its pane");
    }

    private static TerminalView active(TerminalWindow window) {
        try {
            return onFx(window::activeTerminal);
        } catch (Exception e) {
            return null;
        }
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
