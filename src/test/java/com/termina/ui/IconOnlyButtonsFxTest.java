package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Control;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.stage.Window;

import com.termina.config.Settings;
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
 * Every icon-only button the app shows has a name (ICON-21, TBAR-01, KBD-17, Pauta 2.0.2): its
 * accessible text is set, and where it has a tooltip, the tooltip says the same. A button built
 * without {@link IconNames} gets a tooltip and forgets the name, or the other way round, and nothing
 * on screen shows it — a screen reader announces "button".
 *
 * <p>Checked on what is drawn rather than on the source: the real window with its tab strip, find
 * bar and both zoom rows, the Settings window and About. "Icon-only" is a button whose text has no
 * letter or digit — none, or a symbol such as the zoom row's "−" and "⤢", which a reader would read
 * as itself.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class IconOnlyButtonsFxTest {

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
    void everyIconOnlyButtonHasAName() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));
        waitFor(() -> started(window), "the shell");
        onFx(() -> {
            window.openTab(); // two tabs: the strip shows, with a close button on each
            TerminalView view = window.activeTerminal();
            if (view != null) view.showSearch();
            return null;
        });
        waitFor(() -> started(window), "the second tab's shell");
        Await.frames(2); // and the strip and the find bar laid out

        List<String> unnamed = new ArrayList<>();
        int checked = onFx(() -> {
            List<Node> roots = new ArrayList<>();
            roots.add(window.stage().getScene().getRoot());
            for (Menu menu : window.menuBarForTest().getMenus()) roots.addAll(customContent(menu.getItems()));
            TerminalView view = window.activeTerminal();
            if (view != null)
                roots.addAll(customContent(view.contextMenuForTest().getItems()));
            windows.showSettings(window.stage());
            roots.add(windows.showAboutForCapture(window.stage()).getRoot());
            for (Window other : Window.getWindows()) {
                if (other.isShowing() && other.getScene() != null)
                    roots.add(other.getScene().getRoot());
            }
            int count = 0;
            // The windows overlap the roots above: each node once.
            java.util.Set<Node> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Node root : roots) {
                root.applyCss();
                for (Node node : all(root)) {
                    if (seen.add(node)) count += check(node, unnamed);
                }
            }
            return count;
        });
        assertEquals(List.of(), unnamed);
        // New tab, the profile chevron, two close buttons, the find bar's arrows and the zoom rows.
        assertTrue(checked >= 12, "icon-only controls checked: " + checked);
    }

    /** 1 when {@code node} is an icon-only control (and adds to {@code unnamed} if it has no name). */
    private static int check(Node node, List<String> unnamed) {
        boolean closeButton = node.getStyleClass().contains("tab-close-button");
        boolean iconButton = node instanceof ButtonBase button && iconOnly(button.getText());
        if (!closeButton && !iconButton) return 0;
        String what = node.getClass().getSimpleName() + node.getStyleClass();
        String name = node.getAccessibleText();
        if (name == null || name.isBlank()) {
            unnamed.add(what + ": no accessible text");
        } else if (node instanceof Control control
                && control.getTooltip() != null
                && !name.equals(control.getTooltip().getText())) {
            unnamed.add(what + ": tooltip \"" + control.getTooltip().getText() + "\", name \"" + name + "\"");
        }
        return 1;
    }

    static boolean iconOnly(String text) {
        return text == null || text.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    private static List<Node> customContent(List<MenuItem> items) {
        List<Node> out = new ArrayList<>();
        for (MenuItem item : items) {
            if (item instanceof CustomMenuItem custom && custom.getContent() != null) out.add(custom.getContent());
            if (item instanceof Menu sub) out.addAll(customContent(sub.getItems()));
        }
        return out;
    }

    private static List<Node> all(Node node) {
        List<Node> out = new ArrayList<>();
        out.add(node);
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            out.addAll(all(scroll.getContent()));
        }
        if (node instanceof Parent parent) {
            for (Node child : new ArrayList<>(parent.getChildrenUnmodifiable())) out.addAll(all(child));
        }
        return out;
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
