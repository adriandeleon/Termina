package com.termina.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.termina.config.Settings;
import com.termina.i18n.Messages;
import com.termina.shell.ShellProfiles;
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
 * The questions the app asks, answered both ways: renaming a tab, a multi-line paste, closing a tab
 * with a program running, a value for a setting, a report with the status bar off; and in Settings,
 * reset, reset key bindings, remove a theme, remove a profile. Cancel must change nothing; the
 * confirming answer must do what it said.
 */
@Tag("fx")
class DialogsFxTest {

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
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
    void theWindowsQuestions() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));
        waitFor(() -> started(window), "the shell");

        // Rename Tab: cancelled, then named.
        onFx(() -> {
            DialogAnswers.next(false, "ignored");
            row(window, "renameTab").action().run();
            DialogAnswers.next(true, "build");
            row(window, "renameTab").action().run();
            return null;
        });
        assertEquals("build", onFx(() -> {
            TerminalView view = window.activeTerminal();
            return view == null ? null : view.getDisplay().getUserTitle();
        }));

        // A multi-line paste: the prompt, cancelled and then accepted.
        onFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString("echo one\necho two\n");
            Clipboard.getSystemClipboard().setContent(content);
            DialogAnswers.next(false, null);
            row(window, "paste").action().run();
            DialogAnswers.next(true, null);
            row(window, "paste").action().run();
            return null;
        });

        // A setting's value, asked for: an answer, then a cancel.
        onFx(() -> {
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if (row.label().equals(Messages.tr("palette.setting.prompt", Messages.tr("settings.padding")))) {
                    DialogAnswers.next(true, "9");
                    row.action().run();
                    DialogAnswers.next(false, null);
                    row.action().run();
                }
            }
            return null;
        });
        assertEquals(9, settings.padding());

        // Closing a tab with a program running asks; Cancel keeps it.
        long busy = onFx(() -> {
            settings.setConfirmClose(true);
            TerminalView view = window.activeTerminal();
            if (view != null) view.getSession().sendString("sleep 30\r");
            return view == null ? -1L : view.getSession().pid();
        });
        waitFor(() -> !com.termina.process.RunningProgram.in(busy).isEmpty(), "the program to be running");
        int tabs = onFx(() -> window.tabsForTest().size());
        int answered = DialogAnswers.answered();
        onFx(() -> {
            DialogAnswers.next(false, null);
            row(window, "closeTab").action().run();
            return null;
        });
        // The prompt comes once the process table has been read, off the FX thread.
        waitFor(() -> DialogAnswers.answered() > answered, "the close prompt, answered");
        assertEquals(tabs, onFx(() -> window.tabsForTest().size()), "Cancel kept the tab");

        // With the status bar off, a report is a dialog.
        onFx(() -> {
            settings.setShowStatusBar(false);
            DialogAnswers.next(true, null);
            row(window, "syncNow").action().run();
            settings.setShowStatusBar(true);
            return null;
        });
        assertEveryDialogMeetsTheRules(6);
    }

    /**
     * What every dialog the tests answered looked like when it was answered: the interface font on
     * it (ICON-01), its buttons worded by the app where there are two or more (CONF-14), and Cancel
     * the one default button wherever a button is red (CONF-13), in the one button order (WIN-11,
     * all Pauta 6.0.1).
     */
    private static void assertEveryDialogMeetsTheRules(int atLeast) {
        List<DialogAnswers.Seen> seen = DialogAnswers.seen();
        assertTrue(seen.size() >= atLeast, "dialogs answered: " + seen);
        for (DialogAnswers.Seen dialog : seen) {
            assertTrue(dialog.uiFont(), "no interface font on " + dialog);
            // ACC-01 (Pauta 6.0.1): every button and field of a dialog has a name.
            assertEquals(List.of(), dialog.unnamed(), "unnamed controls on " + dialog);
            // WIN-11 (Pauta 6.0.1): one order on every OS, and Cancel drawn left of every action.
            assertEquals(Confirm.BUTTON_ORDER, dialog.buttonOrder(), "the OS's button order on " + dialog);
            assertTrue(dialog.cancelLeftmost(), "Cancel is not the leftmost button on " + dialog);
            if (dialog.buttons().size() >= 2) {
                assertEquals(List.of(), dialog.stock(), "stock buttons on " + dialog);
            }
            if (dialog.danger()) {
                assertEquals(1, dialog.defaults(), "one default button on " + dialog);
                assertTrue(dialog.cancelIsDefault(), "Cancel is not the default on " + dialog);
            }
        }
        assertTrue(seen.stream().anyMatch(DialogAnswers.Seen::danger), "a destructive question was asked");
    }

    /** WIN-11 (Pauta 6.0.1): in the order string, Cancel comes before every action's letter. */
    @Test
    void cancelComesBeforeEveryActionInTheButtonOrder() {
        int cancel = Confirm.BUTTON_ORDER.indexOf('C');
        assertTrue(cancel >= 0);
        for (char action : "OYNAU".toCharArray()) {
            assertTrue(Confirm.BUTTON_ORDER.indexOf(action) > cancel, action + " is left of Cancel");
        }
        // Every letter JavaFX's own orders have, so no button type is dropped from the bar.
        for (char letter : javafx.scene.control.ButtonBar.BUTTON_ORDER_WINDOWS.toCharArray()) {
            if (Character.isLetter(letter)) {
                assertTrue(Confirm.BUTTON_ORDER.indexOf(letter) >= 0, "no place for " + letter);
            }
        }
    }

    @Test
    void theSettingsWindowsQuestions() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setFontSize(17);
        settings.replaceKeyBindings(java.util.Map.of("newTab", "Shortcut+Shift+Y"));
        // Close Tab on New Tab's own chord: resetting New Tab then has to ask to take it back.
        KeyCodeHolder.taken(settings);
        ShellProfiles profiles = new ShellProfiles(settings, "System shell");
        // Where imported themes go; the window manager says so at startup, and there is none here.
        Themes.useDirectory(dir.resolve("themes"));
        // A kitty scheme: the importer reads other terminals' formats, not Termina's own.
        Path theme = dir.resolve("night.conf");
        StringBuilder colours = new StringBuilder("foreground #f0f0f0\nbackground #101010\n");
        for (int i = 0; i < 16; i++)
            colours.append("color").append(i).append(" #2").append(i % 10).append("2020\n");
        Files.writeString(theme, colours);

        SettingsWindow window = onFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 400, 300));
            owner.show();
            SettingsWindow settingsWindow = new SettingsWindow(
                    settings,
                    profiles,
                    List.of(
                            new Keybindings.Command("newTab", "File", "New Tab", Keymap.defaultFor("newTab")),
                            new Keybindings.Command("closeTab", "File", "Close Tab", Keymap.defaultFor("closeTab"))),
                    null);
            settingsWindow.show(owner);
            settingsWindow.importTheme(theme);
            return settingsWindow;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!settings.themeId().contains("night") && System.nanoTime() < deadline) Await.frame();
        assertTrue(settings.themeId().contains("night"), "the theme was imported and chosen: " + settings.themeId());

        // Each confirmation cancelled first (nothing changes), then confirmed.
        onFx(() -> {
            press(window, "settings.keys.reset", false);
            press(window, "settings.keys.reset", true);
            press(window, "settings.removeTheme.button", false);
            press(window, "settings.removeTheme.button", true);
            press(window, "settings.profileAdd", true);
            press(window, "settings.profileRemove", false);
            press(window, "settings.profileRemove", true);
            press(window, "settings.keys.resetAll", false);
            return null;
        });
        assertTrue(settings.values().containsKey("keys.closeTab"), "Cancel kept Close Tab's binding");
        onFx(() -> {
            press(window, "settings.keys.resetAll", true);
            settings.setFontSize(17);
            press(window, "settings.reset", false);
            return null;
        });
        assertFalse(settings.values().containsKey("keys.closeTab"), "Reset All cleared it");
        assertEquals(17, settings.fontSize(), "Cancel kept the font size");
        onFx(() -> {
            press(window, "settings.reset", true);
            return null;
        });
        assertTrue(settings.fontSize() != 17, "Reset to Defaults reset it");
        assertEveryDialogMeetsTheRules(8);
    }

    /** Presses the first visible, enabled button labelled {@code key}, answering what it asks. */
    private static void press(SettingsWindow window, String key, boolean answer) {
        String label = Messages.tr(key);
        List<Node> roots = new ArrayList<>(window.pagesForTest());
        roots.add(window.scene().getRoot());
        for (Node root : roots) {
            for (Button button : find(root, Button.class)) {
                if (label.equals(button.getText()) && !button.isDisabled()) {
                    DialogAnswers.next(answer, null);
                    button.fire();
                    return;
                }
            }
        }
    }

    private static MenuAction row(TerminalWindow window, String id) {
        for (MenuAction row : window.runnablePaletteRowsForTest()) {
            if (id.equals(row.id())) return row;
        }
        throw new AssertionError("no runnable command " + id);
    }

    private static <T extends Node> List<T> find(Node node, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(node)) out.add(type.cast(node));
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            out.addAll(find(scroll.getContent(), type));
        }
        if (node instanceof Parent parent) {
            for (Node child : new ArrayList<>(parent.getChildrenUnmodifiable())) out.addAll(find(child, type));
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

    /** A binding that puts Close Tab on New Tab's default chord. */
    private static final class KeyCodeHolder {
        static void taken(Settings settings) {
            javafx.scene.input.KeyCombination home = Keymap.defaultFor("newTab");
            if (home == null) return;
            java.util.Map<String, String> bindings = new java.util.HashMap<>(settings.keyBindings());
            bindings.put("closeTab", home.getName());
            settings.replaceKeyBindings(bindings);
        }
    }
}
