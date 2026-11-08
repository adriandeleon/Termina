package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.termina.config.Settings;
import com.termina.shell.ShellProfiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real Settings window, every page built and every control worked as a hand would: a switch
 * flipped and flipped back, every option of a choice tried and the first restored, a stepper up
 * and down, a slider to both ends, a field typed into and committed. What is checked is that every
 * control writes through live (SET-01) and that the window takes its own writes back without a
 * stale value — the failure a settings window has when a reload races a write.
 *
 * <p>Buttons are left alone: several open a modal dialog or a file chooser, which a headless run
 * cannot answer.
 */
@Tag("fx")
class SettingsWindowFxTest {

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
    void everyControlOnEveryPageWritesThroughAndTheWindowKeepsUp() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        int[] changes = {0};
        settings.addOnChange(() -> changes[0]++);
        ShellProfiles profiles = new ShellProfiles(settings, "System shell");
        List<Keybindings.Command> commands = List.of(
                new Keybindings.Command("newTab", "File", "New Tab", Keymap.defaultFor("newTab")),
                new Keybindings.Command("closeTab", "File", "Close Tab", Keymap.defaultFor("closeTab")),
                new Keybindings.Command(
                        "commandPalette", "View", "Command Palette", Keymap.defaultFor("commandPalette")));

        int[] worked = onFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 400, 300));
            owner.show();
            SettingsWindow window = new SettingsWindow(settings, profiles, commands, null);
            window.show(owner);
            int[] counts = new int[5];
            for (Region page : window.pagesForTest()) exercise(page, counts);
            for (String query : List.of("font", "scroll", "sync", "keyboard", "no such setting", "")) {
                window.searchForCapture(query);
            }
            window.fireKeysForCapture("newTab:C-S-y;closeTab:BS");
            window.showProfiles(owner);
            return counts;
        });

        assertTrue(worked[0] >= 10, "switches worked: " + worked[0]);
        assertTrue(worked[1] >= 3, "choices worked: " + worked[1]);
        assertTrue(worked[2] >= 3, "steppers worked: " + worked[2]);
        assertTrue(changes[0] > worked[0], "every control wrote through: " + changes[0] + " changes");
        settings.flush();
        // Every switch went there and back, so the stored switches are what they were.
        Settings reread = new Settings(dir.resolve("settings.json"));
        reread.load();
        assertEquals(settings.showScrollBar(), reread.showScrollBar());
        assertEquals(settings.confirmClose(), reread.confirmClose());
    }

    /** Works every control under {@code node}; counts switches, choices, steppers, sliders, fields. */
    private static void exercise(Node node, int[] counts) {
        if (node instanceof CheckBox box && !box.isDisabled()) {
            box.fire();
            box.fire();
            counts[0]++;
        } else if (node instanceof ComboBox<?> combo
                && !combo.isDisabled()
                && !combo.getItems().isEmpty()) {
            exerciseCombo(combo);
            counts[1]++;
        } else if (node instanceof Spinner<?> spinner && !spinner.isDisabled() && spinner.getValueFactory() != null) {
            spinner.increment();
            spinner.decrement();
            counts[2]++;
        } else if (node instanceof Slider slider && !slider.isDisabled()) {
            double was = slider.getValue();
            slider.setValue(slider.getMin());
            slider.setValue(slider.getMax());
            slider.setValue(was);
            counts[3]++;
        } else if (node instanceof TextField field && !field.isDisabled() && field.isEditable()) {
            String was = field.getText();
            field.setText(was == null ? "x" : was + " ");
            field.fireEvent(new ActionEvent());
            field.setText(was);
            field.fireEvent(new ActionEvent());
            counts[4]++;
        }
        // A scroll pane's content is its skin's child, not its own, until it has been shown.
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            exercise(scroll.getContent(), counts);
        }
        if (node instanceof Parent parent) {
            for (Node child : new ArrayList<>(parent.getChildrenUnmodifiable())) exercise(child, counts);
        }
    }

    private static <T> void exerciseCombo(ComboBox<T> combo) {
        T was = combo.getValue();
        for (T item : new ArrayList<>(combo.getItems())) combo.setValue(item);
        combo.setValue(was != null ? was : combo.getItems().getFirst());
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
        return result.get(60, TimeUnit.SECONDS);
    }

    /** The buttons that do their work at once — no confirmation, no file chooser, no dialog. */
    private static final java.util.Set<String> SAFE_BUTTONS = java.util.Set.of(
            "settings.keys.reset",
            "settings.profileAdd",
            "settings.debugLog.button",
            "settings.exportConfig.button",
            "settings.sync.now",
            "settings.sync.anyway");

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(
            value = org.junit.jupiter.api.condition.OS.WINDOWS,
            disabledReason = "connects to a git repository, as SyncEngineTest's whole syncs")
    void theButtonsThatActAtOnceAndAConnectionToARealRepository() throws Exception {
        Path bare = dir.resolve("remote.git");
        java.nio.file.Files.createDirectories(bare);
        assertEquals(
                0,
                new ProcessBuilder("git", "init", "--quiet", "--bare", bare.toString())
                        .start()
                        .waitFor());
        Path home = dir.resolve("home");
        java.nio.file.Files.createDirectories(home);
        Settings settings = new Settings(dir.resolve("config/settings.json"));
        settings.load();
        settings.replaceKeyBindings(java.util.Map.of("newTab", "Shortcut+Shift+Y"));
        ShellProfiles profiles = new ShellProfiles(settings, "System shell");
        List<Keybindings.Command> commands =
                List.of(new Keybindings.Command("newTab", "File", "New Tab", Keymap.defaultFor("newTab")));

        String was = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); // Export writes there
        try {
            SettingsSync sync = onFx(() -> new SettingsSync(settings, () -> true));
            SettingsWindow[] built = new SettingsWindow[1];
            int pressed = onFx(() -> {
                Stage owner = new Stage();
                owner.setScene(new Scene(new StackPane(), 400, 300));
                owner.show();
                SettingsWindow window = new SettingsWindow(settings, profiles, commands, sync);
                built[0] = window;
                window.show(owner);
                // A theme file read through the import row, as the file chooser would hand it over.
                Themes.useDirectory(dir.resolve("themes")); // the window manager's job, and there is none
                // A kitty scheme: the importer reads other terminals' formats, not Termina's own.
                Path theme = dir.resolve("night.conf");
                StringBuilder colours = new StringBuilder("foreground #f0f0f0\nbackground #101010\n");
                for (int i = 0; i < 16; i++)
                    colours.append("color")
                            .append(i)
                            .append(" #2")
                            .append(i % 10)
                            .append("2020\n");
                java.nio.file.Files.writeString(theme, colours);
                window.importTheme(theme);
                // Connect: the repository typed into its field, then the button.
                for (Region page : window.pagesForTest()) {
                    for (TextField field : find(page, TextField.class)) {
                        if (com.termina.i18n.Messages.tr("settings.sync.repository.prompt")
                                .equals(field.getPromptText())) {
                            field.setText(bare.toString());
                        }
                    }
                }
                int count = 0;
                for (Region page : window.pagesForTest()) {
                    for (javafx.scene.control.Button button : find(page, javafx.scene.control.Button.class)) {
                        String text = button.getText();
                        boolean safe = SAFE_BUTTONS.stream()
                                .anyMatch(
                                        key -> com.termina.i18n.Messages.tr(key).equals(text));
                        boolean connect = com.termina.i18n.Messages.tr("settings.sync.connect")
                                .equals(text);
                        if ((safe || connect) && !button.isDisabled() && shown(button)) {
                            button.fire();
                            count++;
                        }
                    }
                    // Add Profile opened an editor: its fields commit like the rest.
                    exercise(page, new int[5]);
                }
                return count;
            });
            assertTrue(pressed >= 4, "buttons pressed: " + pressed);
            assertTrue(settings.syncEnabled(), "Connect connected");
            // The same button disconnects; the GitHub button's name prompt, cancelled before gh runs.
            onFx(() -> {
                // From the pages, not the scene: only the page on show is in it.
                for (Region page : built[0].pagesForTest()) {
                    for (javafx.scene.control.Button button : find(page, javafx.scene.control.Button.class)) {
                        String text = button.getText();
                        if (com.termina.i18n.Messages.tr("settings.sync.disconnect")
                                .equals(text)) button.fire();
                        if (com.termina.i18n.Messages.tr("settings.sync.createGithub")
                                .equals(text)) {
                            DialogAnswers.next(false, null);
                            button.fire();
                        }
                    }
                }
                return null;
            });
            assertFalse(settings.syncEnabled(), "Disconnect disconnected");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (onFx(() -> sync.state().phase()) == SettingsSync.Phase.SYNCING && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
        } finally {
            System.setProperty("user.home", was);
        }
    }

    /** Every node of {@code type} under {@code node}, into scroll panes too. */
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

    /** Whether a hand could reach it: it and every parent visible. */
    private static boolean shown(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }
}
