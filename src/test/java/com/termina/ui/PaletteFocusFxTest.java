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
    void theMessageLogIsOverTheTerminalAndGivesTheKeyboardBack() throws Exception {
        // MLOG-08 (Pauta 6.0.1): a card in the terminal's own window. It keeps the keyboard while
        // it is up, the palette and it are never up together, and closing it gives the keyboard
        // back to the terminal.
        Settings settings = new Settings(dir.resolve("log.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");

        String opened = onFx(() -> {
            TerminalView view = java.util.Objects.requireNonNull(window.activeTerminal());
            view.requestFocus();
            windows.messages().add(MessageLog.Severity.ERROR, "something failed");
            int stages = javafx.stage.Window.getWindows().size();
            run(window, "view.messageLog");
            MessageLogCard log = window.messageLog();
            return log.isShowing()
                    + " " + (log.card().getScene() == window.stage().getScene())
                    + " " + (javafx.stage.Window.getWindows().size() - stages)
                    + " " + windows.messages().unreadErrors();
        });
        assertEquals("true true 0 0", opened, "showing, in the window's scene, no new window, errors read");

        // Frames later the terminal has not taken the keyboard back from it.
        Await.frames(3);
        String kept = onFx(() -> {
            Node owner = window.stage().getScene().getFocusOwner();
            boolean inCard = false;
            for (Node at = owner; at != null; at = at.getParent()) {
                if (at == window.messageLog().card()) inCard = true;
            }
            return String.valueOf(inCard);
        });
        assertEquals("true", kept, "the keyboard stays in the log while it is up");

        String closed = onFx(() -> {
            TerminalView view = java.util.Objects.requireNonNull(window.activeTerminal());
            run(window, "palette.show");
            boolean underPalette = window.messageLog().isShowing();
            run(window, "palette.show");
            run(window, "view.messageLog");
            boolean again = window.messageLog().isShowing();
            run(window, "view.messageLog"); // the command again closes it
            return underPalette + " " + again + " " + window.messageLog().isShowing() + " "
                    + (window.stage().getScene().getFocusOwner() == view);
        });
        assertEquals("false true false true", closed);
    }

    @Test
    void thePalettesMovementKeysAreTheOnesBoundAndTheShellKeepsThem() throws Exception {
        // PAL-06 (Pauta 6.0.1): a row and a page move on the chords of the four nav commands,
        // and follow them when they are rebound; with the terminal in front those keys are not
        // the window's to take.
        Settings settings = new Settings(dir.resolve("moves.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");

        String[] seen = onFx(() -> {
            javafx.scene.input.KeyEvent ctrlN = new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "",
                    "",
                    javafx.scene.input.KeyCode.N,
                    false,
                    true,
                    false,
                    false);
            boolean windowTakesIt = window.keyFilterTakesForTest(ctrlN);
            window.showPaletteForCapture("");
            String first = window.firePaletteKeysForCapture("up");
            String byDefault = window.firePaletteKeysForCapture("C-n,C-n,C-p,C-v,M-v");
            String legend = window.paletteLegendForTest();

            settings.replaceKeyBindings(java.util.Map.of("nav.lineDown", "Ctrl+J", "nav.lineUp", "none"));
            String rebound = window.firePaletteKeysForCapture("C-j,C-n,down,up");
            String legendAfter = window.paletteLegendForTest();
            boolean stillNotTaken = !window.keyFilterTakesForTest(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "",
                    "",
                    javafx.scene.input.KeyCode.J,
                    false,
                    true,
                    false,
                    false));
            run(window, "palette.show");
            settings.replaceKeyBindings(java.util.Map.of());
            return new String[] {
                String.valueOf(windowTakesIt),
                first,
                byDefault,
                legend,
                rebound,
                legendAfter,
                String.valueOf(stillNotTaken)
            };
        });
        assertEquals("false", seen[0], "Ctrl+N with the terminal in front is the shell's");
        // C-n twice goes two rows down, C-p one back; the page chords move further than a row.
        String[] steps = seen[2].split(" \\| ");
        assertEquals(5, steps.length, seen[2]);
        String start = seen[1].substring(seen[1].indexOf(" -> ") + 4);
        String afterOne = steps[0].substring(steps[0].indexOf(" -> ") + 4);
        String afterTwo = steps[1].substring(steps[1].indexOf(" -> ") + 4);
        String back = steps[2].substring(steps[2].indexOf(" -> ") + 4);
        assertTrue(!afterOne.equals(start) && !afterTwo.equals(afterOne), seen[2]);
        assertEquals(afterOne, back, "C-p goes back up a row");
        assertTrue(seen[3].contains(KeyChord.display("C-n")) && seen[3].contains(KeyChord.display("C-p")), seen[3]);

        // Rebound: Ctrl+J moves, Ctrl+N no longer does, the arrows still do.
        String[] after = seen[4].split(" \\| ");
        String j = after[0].substring(after[0].indexOf(" -> ") + 4);
        String n = after[1].substring(after[1].indexOf(" -> ") + 4);
        String down = after[2].substring(after[2].indexOf(" -> ") + 4);
        String up = after[3].substring(after[3].indexOf(" -> ") + 4);
        assertEquals(j, n, "Ctrl+N is no longer a movement key: " + seen[4]);
        assertTrue(!down.equals(n) && up.equals(n), seen[4]);
        assertTrue(seen[5].contains(KeyChord.display("C-j")) && !seen[5].contains(KeyChord.display("C-n")), seen[5]);
        assertEquals("true", seen[6], "nor is the new chord the window's with the terminal in front");
    }

    @Test
    void theHelpKeyOpensTheHighlightedCommandsPageForThisVersion() throws Exception {
        // PAL-08 (Pauta 6.0.1): Ctrl+H in the palette opens the page of the command under the
        // highlight, in the guide of the running version; a command put on Ctrl+H moves the help
        // key to F1.
        Settings settings = new Settings(dir.resolve("help.json"));
        settings.load();
        settings.setUpdateCheck(false);
        java.util.List<String> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
        WindowManager windows = onFx(() -> {
            WindowManager manager = new WindowManager(settings);
            manager.setLinkOpener(opened::add);
            return manager;
        });
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");

        String[] seen = onFx(() -> {
            window.showPaletteForCapture("keyboard shortcuts");
            String key = window.paletteHelpKeyForTest();
            String legend = window.paletteLegendForTest();
            window.firePaletteKeysForCapture("C-h");
            boolean stillUp = window.paletteLegendForTest().equals(legend);
            settings.replaceKeyBindings(java.util.Map.of("file.newWindow", "Ctrl+H"));
            String moved = window.paletteHelpKeyForTest();
            window.firePaletteKeysForCapture("f1");
            run(window, "palette.show");
            settings.replaceKeyBindings(java.util.Map.of());
            return new String[] {key, legend, String.valueOf(stillUp), moved};
        });
        assertEquals("C-h", seen[0]);
        assertTrue(seen[1].contains(KeyChord.display("C-h")), "the legend names the help key: " + seen[1]);
        assertEquals("true", seen[2]);
        assertEquals("f1", seen[3], "Ctrl+H is a command's now, so help is on F1");
        assertEquals(2, opened.size(), opened.toString());
        String page = com.termina.AppInfo.commandPage("help.keyboardShortcuts");
        assertEquals(java.util.List.of(page, page), opened, "Keyboard Shortcuts is the row under the highlight");
        assertTrue(page.contains("/docs/v-"), page);
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
            String before = window.paletteChordForTest("file.newTab") + " " + window.paletteOpenerForTest();
            settings.replaceKeyBindings(java.util.Map.of("file.newTab", "Shortcut+Shift+Y", "palette.show", "Alt+X"));
            String after = window.paletteChordForTest("file.newTab") + " " + window.paletteOpenerForTest();
            run(window, "palette.show");
            settings.replaceKeyBindings(java.util.Map.of());
            return new String[] {before, after};
        });
        String y = javafx.scene.input.KeyCombination.valueOf("Shortcut+Shift+Y").getDisplayText();
        // New Tab's own default, which is Cmd+T on macOS and Ctrl+Shift+T elsewhere.
        String t = MenuAction.appChord(javafx.scene.input.KeyCode.T).getDisplayText();
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
            boolean before = window.messageLog().isShowing();
            labels.getFirst().executeAccessibleAction(javafx.scene.AccessibleAction.FIRE);
            boolean opened = !before && window.messageLog().isShowing();
            window.messageLog().hide();
            return new boolean[] {roles, opened};
        });
        assertTrue(pressed[0], "the status bar's labels are buttons to a screen reader");
        assertTrue(pressed[1], "and a press opens the Message Log");
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
            run(window, "view.focusMenuBar");
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
        assertFalse(onFx(() ->
                window.runnablePaletteRowsForTest().stream().anyMatch(row -> "view.focusMenuBar".equals(row.id()))));
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
