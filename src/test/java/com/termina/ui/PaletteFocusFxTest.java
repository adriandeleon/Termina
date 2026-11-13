package com.termina.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.TextInputControl;

import com.termina.config.Settings;
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
 * Where the keyboard goes when the palette closes (PAL-07, Pauta 6.0.1): back where it was, the
 * find bar's field included, and to the terminal when that is where it was or nothing else can
 * take it. And the command that puts it in the menu bar (MENU-17).
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class PaletteFocusFxTest {

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
    void closingThePaletteGivesTheKeyboardBackToWhereItWas() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");

        // From the terminal: back to the terminal.
        String fromTerminal = onFx(() -> {
            TerminalView view = java.util.Objects.requireNonNull(window.activeTerminal());
            view.requestFocus();
            run(window, "palette.show");
            Node during = window.stage().getScene().getFocusOwner();
            run(window, "palette.show"); // the command again closes it
            Node after = window.stage().getScene().getFocusOwner();
            return (during instanceof TextInputControl) + " " + (after == view);
        });
        assertEquals("true true", fromTerminal);

        // From the find bar's field: back to that field, not to the terminal behind it.
        String fromFind = onFx(() -> {
            TerminalView view = java.util.Objects.requireNonNull(window.activeTerminal());
            view.showSearch();
            window.stage().getScene().getRoot().applyCss();
            window.stage().getScene().getRoot().layout();
            Node field = window.stage().getScene().getFocusOwner();
            run(window, "palette.show");
            Node during = window.stage().getScene().getFocusOwner();
            run(window, "palette.show");
            Node after = window.stage().getScene().getFocusOwner();
            return (field instanceof TextInputControl) + " " + (during != field) + " " + (after == field);
        });
        assertEquals("true true true", fromFind);

        // The rule on its own: a node that is gone, hidden or disabled does not get it back.
        assertFalse(CommandPalette.returnsTo(null, null));
        boolean[] rule = onFx(() -> {
            javafx.scene.Scene scene = window.stage().getScene();
            javafx.scene.control.TextField loose = new javafx.scene.control.TextField();
            Node field = scene.getFocusOwner();
            boolean there = CommandPalette.returnsTo(field, scene);
            boolean elsewhere = CommandPalette.returnsTo(loose, scene);
            field.setDisable(true);
            boolean disabled = CommandPalette.returnsTo(field, scene);
            field.setDisable(false);
            return new boolean[] {there, elsewhere, disabled};
        });
        assertTrue(rule[0], "a field still in the scene takes it back");
        assertFalse(rule[1], "a node of another scene does not");
        assertFalse(rule[2], "nor a disabled one");
    }

    @Test
    void anOpenPaletteFollowsAChangeOfBindings() throws Exception {
        // PAL-09 (Pauta 6.0.1): the opener's chip and the rows are rebuilt as the bindings change,
        // so a palette that is up never shows a chord that has moved.
        Settings settings = new Settings(dir.resolve("rebind.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");

        String[] seen = onFx(() -> {
            run(window, "palette.show");
            String before = window.paletteChordForTest("newTab") + " " + window.paletteOpenerForTest();
            settings.replaceKeyBindings(java.util.Map.of("newTab", "Shortcut+Shift+Y", "palette.show", "Alt+X"));
            String after = window.paletteChordForTest("newTab") + " " + window.paletteOpenerForTest();
            run(window, "palette.show");
            settings.replaceKeyBindings(java.util.Map.of());
            return new String[] {before, after};
        });
        String y = javafx.scene.input.KeyCombination.valueOf("Shortcut+Shift+Y").getDisplayText();
        String t = javafx.scene.input.KeyCombination.valueOf("Shortcut+Shift+T").getDisplayText();
        String p = javafx.scene.input.KeyCombination.valueOf("Shortcut+Shift+P").getDisplayText();
        String x = javafx.scene.input.KeyCombination.valueOf("Alt+X").getDisplayText();
        assertEquals(t + " " + p, seen[0]);
        assertEquals(y + " " + x, seen[1], "the open palette shows the chords in force");
    }

    @Test
    void theTerminalAndTheStatusBarSayWhatTheyAreToAScreenReader() throws Exception {
        // ACC-03, ACC-02 (Pauta 6.0.1): the terminal stands for its canvas, as a text area whose
        // text is the screen; the status bar's message is a button whose press opens the log.
        Settings settings = new Settings(dir.resolve("reader.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");
        onFx(() -> {
            java.util.Objects.requireNonNull(window.activeTerminal())
                    .getSession()
                    .sendString("echo reader-can-hear\r");
            return null;
        });
        waitFor(
                () -> {
                    try {
                        return onFx(() -> String.valueOf(java.util.Objects.requireNonNull(window.activeTerminal())
                                        .queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT))
                                .lines()
                                .anyMatch("reader-can-hear"::equals));
                    } catch (Exception e) {
                        return false;
                    }
                },
                "the screen's text to reach the accessible text");
        String said = onFx(() -> {
            TerminalView view = java.util.Objects.requireNonNull(window.activeTerminal());
            String text = String.valueOf(view.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT));
            return view.getAccessibleRole() + " " + view.getAccessibleText() + " " + text.endsWith(" ") + " "
                    + text.endsWith("\n");
        });
        assertEquals("TEXT_AREA " + com.termina.i18n.Messages.tr("terminal.accessibleName") + " false false", said);

        boolean[] pressed = onFx(() -> {
            java.util.List<PressLabel> labels = new java.util.ArrayList<>();
            collect(window.stage().getScene().getRoot(), labels);
            boolean roles = !labels.isEmpty();
            for (PressLabel label : labels) roles &= label.getAccessibleRole() == javafx.scene.AccessibleRole.BUTTON;
            int logs = openLogs();
            labels.getFirst().executeAccessibleAction(javafx.scene.AccessibleAction.FIRE);
            boolean opened = openLogs() > logs;
            for (javafx.stage.Window open : java.util.List.copyOf(javafx.stage.Window.getWindows())) {
                if (open instanceof javafx.stage.Stage stage
                        && com.termina.i18n.Messages.tr("messagelog.title").equals(stage.getTitle())) {
                    stage.hide();
                }
            }
            return new boolean[] {roles, opened};
        });
        assertTrue(pressed[0], "the status bar's labels are buttons to a screen reader");
        assertTrue(pressed[1], "and a press opens the Message Log");
    }

    private static int openLogs() {
        int count = 0;
        for (javafx.stage.Window open : javafx.stage.Window.getWindows()) {
            if (open instanceof javafx.stage.Stage stage
                    && stage.isShowing()
                    && com.termina.i18n.Messages.tr("messagelog.title").equals(stage.getTitle())) count++;
        }
        return count;
    }

    private static void collect(Node node, java.util.List<PressLabel> out) {
        if (node instanceof PressLabel label) out.add(label);
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, out);
        }
    }

    @Test
    void theKeyboardReachesTheMenuBarThroughItsCommand() throws Exception {
        Settings settings = new Settings(dir.resolve("bar.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");

        String shown = onFx(() -> {
            run(window, "focusMenuBar");
            javafx.scene.control.Menu first = window.menuBarForTest().getMenus().getFirst();
            boolean open = first.isShowing();
            boolean focused = window.menuBarForTest().isFocused();
            first.hide();
            return open + " " + focused;
        });
        assertEquals("true true", shown);

        // Hidden, there is no bar to walk: the command is greyed, with its reason.
        onFx(() -> {
            settings.setShowMenuBar(false);
            return null;
        });
        assertFalse(onFx(
                () -> window.runnablePaletteRowsForTest().stream().anyMatch(row -> "focusMenuBar".equals(row.id()))));
        onFx(() -> {
            settings.setShowMenuBar(true);
            return null;
        });
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
