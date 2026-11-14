package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every command the palette lists, run in a real window on real shells: tabs opened, split, moved,
 * pinned and closed, the find bar, zoom, the menu bar, every theme and setting row. What is checked
 * is that none of them throws and the window is still in one piece afterwards — the commands are
 * each tested for what they do elsewhere; this is the one place they all run against the same live
 * window, one after another, as a user's session does.
 *
 * <p>Left out, by id: what opens a modal dialog or a file chooser (which a headless run cannot
 * answer), what reaches the network or a browser, and closing the window, which is
 * {@link WindowLifecycleTest}'s. The status bar is turned on first, because with it off a command
 * that reports puts up a modal dialog instead. Not on Windows, whose shell path has never run.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class WindowCommandsFxTest {

    /** Commands left out, by id, and why. */
    private static final Set<String> SKIPPED = Set.of(
            "help.checkForUpdates", // the network
            "help.documentation", // a browser
            "help.whatsNew", // a browser
            "settings.exportConfiguration", // writes to the real home folder
            "settings.importConfiguration", // a file chooser
            "debugLog.export", // a file chooser
            "settings.importTheme", // a file chooser
            "settings.reset", // a question
            "settings.resetShortcuts", // a question
            "settings.removeProfile", // a question, once Add Profile has selected one
            "sync.createRepository", // a prompt, where gh is signed in
            "report.open", // a browser
            "help.website", // a browser
            "help.sourceCode", // a browser
            "help.pautaRules", // a browser
            "help.pautaDocs", // a browser
            "view.uiZoomIn", // the zoom is the whole app's, and every other test expects 100%: UiZoomFxTest's
            "view.uiZoomOut",
            "view.uiZoomReset",
            "tab.rename", // a modal dialog
            "file.closeWindow", // WindowLifecycleTest's
            "app.quit", // ends the app, and the toolkit the other tests share
            "view.toggleTabBar", // TabBarFxTest's: hidden, a later test's tab headers are not there to find
            "view.toggleStatusBar"); // turning the bar off makes every later report a modal dialog

    /** Run last, in this order: they take tabs away from the commands before them. */
    private static final List<String> LAST =
            List.of("file.closeTabsToTheLeft", "file.closeTabsToTheRight", "file.closeOtherTabs", "file.closeTab");

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
    void everyPaletteCommandRunsAgainstALiveWindow() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        settings.setConfirmClose(false);
        // Paste runs: with several lines on the clipboard (a CI runner's, say) it would ask first, and
        // the prompt is modal. One line on the clipboard, and the prompt off besides.
        settings.setConfirmPaste(false);
        onFx(() -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString("echo pasted");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            return null;
        });

        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        // A second window, so closing every tab of the first never ends the application.
        onFx(() -> windows.openWindow(window));
        waitFor(() -> started(window), "the first shell");
        onFx(() -> {
            window.openTab();
            window.openTab();
            return null;
        });

        String settingsGroup = Messages.tr("palette.group.settings");
        List<MenuAction> rows = onFx(window::runnablePaletteRowsForTest);
        List<MenuAction> order = new ArrayList<>();
        List<MenuAction> last = new ArrayList<>();
        for (MenuAction row : rows) {
            String id = row.id();
            if (id != null && SKIPPED.contains(id)) continue;
            // A setting's value prompt is a modal dialog; its switches and choices run.
            if (row.keywords() != null
                    && row.keywords().contains(settingsGroup)
                    && row.label().endsWith("…")) continue;
            if (id != null && LAST.contains(id)) last.add(row);
            else order.add(row);
        }
        last.sort(java.util.Comparator.comparingInt(row -> LAST.indexOf(row.id())));
        order.addAll(last);
        assertTrue(order.size() > 60, "the palette lists the commands: " + order.size());

        List<String> failures = new ArrayList<>();
        for (MenuAction row : order) {
            String failure = onFx(() -> {
                try {
                    row.action().run();
                    return null;
                } catch (RuntimeException e) {
                    return row.label() + ": " + e;
                }
            });
            if (failure != null) failures.add(failure);
            // A pulse between commands, as between key presses: some lay out before the next runs.
            onFx(() -> null);
        }
        assertEquals(List.of(), failures);
        assertTrue(onFx(() -> windows.windows().size() >= 1), "the application is still open");
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
