package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.termina.config.Settings;
import com.termina.testing.Await;
import org.junit.jupiter.api.AfterEach;
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
 * The UI zoom in the running app (WIN-12, MENU-16, Pauta 6.0.1): one setting restyles every open
 * window at once, the sizes the code fixed follow it, a menu draws Inter 14 px titles and 11 px
 * chords times the zoom whatever it was opened from, and three commands move it.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class UiZoomFxTest {

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

    /** The zoom is the whole app's: every other test expects to find it at 100%. */
    @AfterEach
    void backToTheDefault() throws Exception {
        onFx(() -> {
            UiZoom.apply(Settings.DEFAULT_UI_ZOOM);
            javafx.application.Application.setUserAgentStylesheet(
                    UiZoom.theme(Themes.byId("editora-dark").stylesheet()));
            return null;
        });
    }

    @Test
    void theSizesTheCodeFixedFollowTheZoomAndComeBack() throws Exception {
        List<String> seen = onFx(() -> {
            VBox root = new VBox(10);
            root.setPadding(new javafx.geometry.Insets(12, 8, 4, 2));
            Label fixed = new Label("fixed");
            fixed.setPrefWidth(110);
            fixed.setMaxWidth(Double.MAX_VALUE);
            Label own = UiZoom.own(new Label("own"));
            own.setPrefWidth(50);
            HBox row = new HBox(6, fixed, own);
            HBox.setMargin(fixed, new javafx.geometry.Insets(0, 0, 0, 8));
            Label bound = new Label("bound");
            UiZoom.bind(bound.minWidthProperty(), 20);
            root.getChildren().addAll(row, bound);
            UiZoom.adopt(root);
            // Added after the walk: adopted as it arrives.
            Label late = new Label("late");
            late.setMinHeight(30);
            root.getChildren().add(late);

            List<String> out = new ArrayList<>();
            for (int percent : new int[] {100, 150, 100}) {
                UiZoom.apply(percent);
                out.add(root.getSpacing() + " " + root.getPadding().getTop() + "/"
                        + root.getPadding().getLeft() + " " + fixed.getPrefWidth() + " "
                        + (fixed.getMaxWidth() == Double.MAX_VALUE) + " " + own.getPrefWidth() + " "
                        + row.getSpacing() + " " + HBox.getMargin(fixed).getLeft() + " " + bound.getMinWidth() + " "
                        + late.getMinHeight() + " " + UiZoom.px(10));
            }
            return out;
        });
        String at100 = "10.0 12.0/2.0 110.0 true 50.0 6.0 8.0 20.0 30.0 10.0";
        assertEquals(at100, seen.get(0));
        assertEquals("15.0 18.0/3.0 165.0 true 50.0 9.0 12.0 30.0 45.0 15.0", seen.get(1));
        assertEquals(at100, seen.get(2), "back at 100% everything is as the code wrote it");
    }

    @Test
    void oneSettingRestylesEveryOpenWindowAndItsMenus() throws Exception {
        Settings settings = new Settings(dir.resolve("zoom.json"));
        settings.load();
        settings.setUpdateCheck(false);
        // The app registers its bundled fonts before the first window; a test has to ask.
        Fonts.load("");
        // Only the windows this test opens are its to close: other tests' share the toolkit, and a
        // terminal's window hidden from here would end its own manager and the toolkit with it.
        java.util.Set<javafx.stage.Window> before =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        onFx(() -> before.addAll(javafx.stage.Window.getWindows()));
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");

        List<String> at100 = onFx(() -> {
            windows.showAbout(window.stage());
            return look(window, windows);
        });
        // The setting, written as the Settings row and the palette write it; the app follows.
        onFx(() -> {
            UiZoom.prepare(150);
            settings.setUiZoom(150);
            return null;
        });
        List<String> at150 = onFx(() -> look(window, windows));
        onFx(() -> {
            settings.setUiZoom(100);
            return null;
        });
        List<String> back = onFx(() -> {
            List<String> out = look(window, windows);
            for (javafx.stage.Window open : List.copyOf(javafx.stage.Window.getWindows())) {
                if (before.contains(open)) continue;
                if (open instanceof Stage && open != window.stage() && open.isShowing()) open.hide();
            }
            // The terminal's window stays: closing the last one ends the toolkit the tests share.
            return out;
        });

        // MENU-16: Inter 14 px titles and 11 px chords, in the bar's menu and in a right-click one.
        assertEquals("bundled bundled Inter 14.0 Inter 11.0 Inter 14.0 1.0 480.0", String.join(" ", at100));
        assertEquals("scaled scaled Inter 21.0 Inter 16.5 Inter 21.0 1.5 720.0", String.join(" ", at150));
        assertEquals(at100, back);
        assertEquals(100, settings.uiZoom());
    }

    @Test
    void threeCommandsMoveItAndSayWhenTheyCannot() throws Exception {
        Settings settings = new Settings(dir.resolve("commands.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");

        assertFalse(runnable(window, "view.uiZoomReset"), "nothing to reset at 100%");
        run(window, "view.uiZoomIn");
        waitFor(() -> settings.uiZoom() == 110, "the zoom to go up a step");
        waitFor(() -> UiZoom.percent() == 110, "the app to follow");
        run(window, "view.uiZoomIn");
        waitFor(() -> settings.uiZoom() == 120, "another step");
        run(window, "view.uiZoomOut");
        waitFor(() -> settings.uiZoom() == 110, "a step back");
        assertTrue(runnable(window, "view.uiZoomReset"));
        run(window, "view.uiZoomReset");
        waitFor(() -> settings.uiZoom() == 100, "back to the default");
        waitFor(() -> UiZoom.percent() == 100, "the app to follow");

        // The terminal's own zoom is another setting and other commands, with the chords.
        assertEquals(Settings.DEFAULT_FONT_SIZE, settings.fontSize());
        assertTrue(Keymap.defaultFor("view.zoomIn") != null, "Ctrl+= stays the terminal text's");
        for (String id : List.of("view.uiZoomIn", "view.uiZoomOut", "view.uiZoomReset")) {
            assertTrue(Keymap.defaultFor(id) == null, id + " has no chord by default");
        }

        // Kept across launches: the value is in the settings file.
        onFx(() -> {
            settings.setUiZoom(130);
            return null;
        });
        Settings reread = new Settings(dir.resolve("commands.json"));
        waitFor(
                () -> {
                    reread.load();
                    return reread.uiZoom() == 130;
                },
                "the zoom to be saved");
        onFx(() -> {
            settings.setUiZoom(100);
            return null;
        });
    }

    /** Where the app's sheets come from, a menu's fonts, the factor, and About's width. */
    private static List<String> look(TerminalWindow window, WindowManager windows) {
        List<String> out = new ArrayList<>();
        String app = window.stage().getScene().getStylesheets().stream()
                .filter(sheet -> sheet.equals(UiZoom.sheet("app.css")))
                .findFirst()
                .orElse("");
        out.add(app.isEmpty() ? "missing" : app.startsWith("data:") ? "scaled" : "bundled");
        String theme = javafx.application.Application.getUserAgentStylesheet();
        out.add(theme != null && theme.startsWith("data:") ? "scaled" : "bundled");

        Scene bar = window.showBarMenuForCapture(com.termina.i18n.Messages.tr("menu.file"));
        if (bar != null) {
            bar.getRoot().applyCss();
            bar.getRoot().layout();
            out.add(font(bar.getRoot().lookup(".menu-item-title")));
            out.add(font(bar.getRoot().lookup(".menu-item-chord")));
        } else {
            out.add("no bar menu");
        }
        // Closed as a person closes it, through the menu, so it opens again the next time.
        for (javafx.scene.control.Menu menu : window.menuBarForTest().getMenus()) menu.hide();
        TerminalView view = window.activeTerminal();
        Scene context = view == null ? null : view.showContextMenuForCapture(300, 300, false);
        if (context != null) {
            context.getRoot().applyCss();
            context.getRoot().layout();
            out.add(font(context.getRoot().lookup(".menu-item > .label")));
            view.contextMenuForTest().hide();
        } else {
            out.add("no right-click menu");
        }
        out.add(String.valueOf(UiZoom.factor().get()));
        out.add(String.valueOf(windows.showAboutForCapture(window.stage()).getWidth()));
        return out;
    }

    private static String font(Node node) {
        if (!(node instanceof Label label)) return "no label";
        return label.getFont().getFamily() + " " + label.getFont().getSize();
    }

    private static boolean runnable(TerminalWindow window, String id) throws Exception {
        return onFx(() -> window.runnablePaletteRowsForTest().stream().anyMatch(row -> id.equals(row.id())));
    }

    private static void run(TerminalWindow window, String id) throws Exception {
        onFx(() -> {
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if (id.equals(row.id())) {
                    row.action().run();
                    return null;
                }
            }
            throw new AssertionError(id + " is not runnable");
        });
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
