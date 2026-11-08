package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;

import com.termina.config.Settings;
import com.termina.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every item of every menu — the menu bar's, each tab's and the terminal's right-click menu — fired as
 * a click fires it, in a real window on real shells. {@link WindowCommandsFxTest} runs the commands;
 * this runs the items, which carry their own handlers (a tab's menu acts on its tab, not the
 * selected one) and are what a mouse reaches. Left out by label, for the same reasons as there:
 * what is modal, external, or ends the window.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class MenusFxTest {

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

    /** Items left out, by their catalogue key. */
    private static final List<String> SKIPPED_KEYS = List.of(
            "menu.checkForUpdates",
            "menu.documentation",
            "menu.whatsNew",
            "menu.exportConfiguration",
            "menu.importConfiguration",
            "menu.renameTab",
            "menu.closeWindow",
            "menu.hideStatusBar",
            "menu.showStatusBar",
            "menu.openLink",
            "menu.copyLink");

    @Test
    void everyMenuItemRunsAgainstALiveWindow() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        settings.setConfirmClose(false);
        Set<String> skipped = new java.util.HashSet<>();
        for (String key : SKIPPED_KEYS) skipped.add(Messages.tr(key));

        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window)); // so the first window's last tab never ends the app
        waitFor(() -> started(window), "the shell");
        onFx(() -> {
            window.openTab();
            window.openTab();
            return null;
        });

        List<String> failures = new ArrayList<>();
        int fired = 0;
        // The terminal's right-click menu, then each tab's, then the menu bar's, whose close items go last.
        TerminalView view = onFx(window::activeTerminal);
        fired += fireAll(onFx(() -> new ArrayList<>(view.contextMenuForTest().getItems())), skipped, failures);
        for (Tab tab : onFx(() -> new ArrayList<>(window.tabsForTest()))) {
            if (tab.getContextMenu() == null) continue;
            List<MenuItem> items = new ArrayList<>(tab.getContextMenu().getItems());
            items.removeIf(item -> item.getText() != null && item.getText().startsWith(Messages.tr("menu.closeTab")));
            fired += fireAll(items, skipped, failures);
        }
        List<MenuItem> bar = new ArrayList<>();
        for (Menu menu : onFx(() -> new ArrayList<>(window.menuBarForTest().getMenus()))) {
            onFx(() -> {
                menu.fire(); // a menu's onShowing greys what cannot run
                return null;
            });
            bar.addAll(flatten(menu.getItems()));
        }
        bar.sort(java.util.Comparator.comparing(item -> item.getText() != null
                && item.getText().startsWith(Messages.tr("menu.closeTab").substring(0, 5))));
        fired += fireAll(bar, skipped, failures);

        assertEquals(List.of(), failures);
        assertTrue(fired > 40, "items fired: " + fired);
    }

    private static List<MenuItem> flatten(List<MenuItem> items) {
        List<MenuItem> out = new ArrayList<>();
        for (MenuItem item : items) {
            if (item instanceof Menu sub) out.addAll(flatten(sub.getItems()));
            else out.add(item);
        }
        return out;
    }

    private static int fireAll(List<MenuItem> items, Set<String> skipped, List<String> failures) throws Exception {
        int fired = 0;
        for (MenuItem item : items) {
            if (item instanceof SeparatorMenuItem || item instanceof CustomMenuItem || item.isDisable()) continue;
            if (item.getText() == null || skipped.contains(item.getText())) continue;
            String failure = onFx(() -> {
                try {
                    item.fire();
                    return null;
                } catch (RuntimeException e) {
                    return item.getText() + ": " + e;
                }
            });
            if (failure != null) failures.add(failure);
            onFx(() -> null);
            fired++;
        }
        return fired;
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

    @Test
    void aMenuNamesAndGreysItsItemsAsTheRegistrySaysWhenItOpens() throws Exception {
        // MENU-01 (Pauta 2.0.2): the bar holds no rule of its own; opening a menu asks the registry.
        Settings settings = new Settings(dir.resolve("registry.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        settings.setConfirmClose(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));
        waitFor(() -> started(window), "the shell");

        assertTrue(onFx(() -> itemOpened(window, "reopenClosedTab").isDisable()), "nothing closed yet: greyed");
        onFx(() -> {
            window.openTab();
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("closeTab".equals(row.id())) row.action().run();
            }
            return null;
        });
        assertTrue(onFx(() -> !itemOpened(window, "reopenClosedTab").isDisable()), "a tab closed: available");

        String before = onFx(() -> itemOpened(window, "pinTab").getText());
        onFx(() -> {
            itemOpened(window, "pinTab").fire();
            return null;
        });
        String after = onFx(() -> itemOpened(window, "pinTab").getText());
        assertTrue(!before.equals(after), "Pin Tab says which way it will go: " + before + " then " + after);
        // The palette names it the same, from the same place.
        assertEquals(after, onFx(() -> {
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("pinTab".equals(row.id())) return row.label();
            }
            return null;
        }));
    }

    /** The bar's item for command {@code id}, after its menu has opened. */
    private static MenuItem itemOpened(TerminalWindow window, String id) {
        for (Menu menu : window.menuBarForTest().getMenus()) {
            for (MenuItem item : menu.getItems()) {
                if (id.equals(item.getUserData())) {
                    javafx.event.Event.fireEvent(menu, new javafx.event.Event(Menu.ON_SHOWING));
                    return item;
                }
            }
        }
        throw new AssertionError("no item for " + id);
    }
}
