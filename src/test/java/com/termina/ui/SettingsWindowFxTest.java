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
                new Keybindings.Command("palette.show", "View", "Command Palette", Keymap.defaultFor("palette.show")));

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

    /**
     * SET-04 (Pauta 6.0.1): a text field commits on Enter, and a value that fails its check is
     * marked and not written: the sync repository and branch, which only Connect used to store,
     * and the shell path, which used to be stored whatever it named.
     */
    @Test
    void aTextFieldCommitsOnEnterAndOnlyAValueThatPasses() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        ShellProfiles profiles = new ShellProfiles(settings, "System shell");
        List<Keybindings.Command> commands =
                List.of(new Keybindings.Command("newTab", "File", "New Tab", Keymap.defaultFor("newTab")));
        Path shell = java.nio.file.Files.writeString(dir.resolve("a-shell"), "#!/bin/sh\n");
        assertTrue(shell.toFile().setExecutable(true));

        List<String> stored = onFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 400, 300));
            owner.show();
            SettingsWindow window = new SettingsWindow(settings, profiles, commands, null);
            window.show(owner);
            TextField repository = field(window, com.termina.i18n.Messages.tr("settings.sync.repository.prompt"));
            TextField branch = field(window, Settings.DEFAULT_SYNC_BRANCH);
            TextField shellField = field(window, com.termina.i18n.Messages.tr("settings.shell.prompt"));
            List<String> out = new ArrayList<>();

            enter(repository, "git@github.com:me/settings.git");
            out.add(settings.syncRepository());
            enter(repository, "https://me:secret@github.com/me/settings.git");
            out.add(settings.syncRepository());
            enter(repository, "--upload-pack=evil");
            out.add(settings.syncRepository());

            enter(repository, "git@github.com:me/settings.git");
            enter(branch, "trunk");
            out.add(settings.syncBranch());
            enter(branch, "-trunk");
            out.add(settings.syncBranch());

            // With the branch still marked, nothing is written; put right, an empty address is.
            enter(repository, "");
            out.add(settings.syncRepository());
            enter(branch, "trunk");
            out.add(settings.syncRepository());

            enter(shellField, shell.toString());
            out.add(settings.shell());
            enter(shellField, dir.resolve("no-such-shell").toString());
            out.add(settings.shell());
            enter(shellField, "");
            out.add(settings.shell());

            // A profile's command and directory, the same: marked, and the profile keeps its own.
            for (Region page : window.pagesForTest()) {
                for (javafx.scene.control.Button button : find(page, javafx.scene.control.Button.class)) {
                    if (com.termina.i18n.Messages.tr("settings.profileAdd").equals(button.getText())) button.fire();
                }
            }
            TextField command = field(window, "wsl.exe -d Ubuntu");
            TextField directory = field(window, com.termina.i18n.Messages.tr("settings.profileDirectory.prompt"));
            enter(command, shell + " --login");
            out.add(profiles.userProfiles().get(0).commandLine());
            enter(command, dir.resolve("no-such-program") + " --login");
            out.add(profiles.userProfiles().get(0).commandLine());
            enter(command, shell + " --login");
            enter(directory, dir.toString());
            out.add(profiles.userProfiles().get(0).workingDirectory());
            enter(directory, dir.resolve("no-such-folder").toString());
            out.add(profiles.userProfiles().get(0).workingDirectory());
            window.scene().getWindow().hide();
            owner.hide();
            return out;
        });
        assertEquals(
                List.of(
                        "git@github.com:me/settings.git",
                        "git@github.com:me/settings.git",
                        "git@github.com:me/settings.git",
                        "trunk",
                        "trunk",
                        "git@github.com:me/settings.git",
                        "",
                        shell.toString(),
                        shell.toString(),
                        "",
                        shell + " --login",
                        shell + " --login",
                        dir.toString(),
                        dir.toString()),
                stored);
        assertFalse(settings.syncEnabled(), "storing the fields connects nothing");
    }

    /** SET-03 (Pauta 6.0.1): what a row's controls show is what the search reads as visible text. */
    @Test
    void theSearchReadsWhatARowsControlsShow() throws Exception {
        String text = onFx(() -> {
            javafx.scene.control.Label title = new javafx.scene.control.Label("Theme");
            title.getStyleClass().add("settings-row-title");
            javafx.scene.control.Label hint = new javafx.scene.control.Label("The colours of the window");
            hint.getStyleClass().add("settings-row-description");
            ComboBox<String> choice = new ComboBox<>();
            choice.getItems().addAll("Editora Dark", "Dracula");
            TextField path = new TextField("typed by the user");
            path.setPromptText("/path/to/shell");
            javafx.scene.control.Button action = new javafx.scene.control.Button("Import…");
            CheckBox box = new CheckBox("Follow the system");
            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(
                    new javafx.scene.layout.VBox(title, hint), new javafx.scene.layout.HBox(choice, path, action, box));
            return SettingsWindow.visibleText(row);
        });
        assertEquals("Editora Dark Dracula /path/to/shell Import… Follow the system", text);
    }

    private static TextField field(SettingsWindow window, String prompt) {
        for (Region page : window.pagesForTest()) {
            for (TextField field : find(page, TextField.class)) {
                if (prompt.equals(field.getPromptText())) return field;
            }
        }
        throw new AssertionError("no field with the prompt " + prompt);
    }

    private static void enter(TextField field, String text) {
        field.setText(text);
        field.fireEvent(new ActionEvent());
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
            // Nothing left writing into the folder JUnit deletes next: auto-sync off, so no timer starts
            // a run, then a job queued behind whatever the worker was still finishing.
            onFx(() -> {
                settings.setSyncAuto(false);
                return null;
            });
            java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
            sync.afterQueuedWorkForTest(drained::countDown);
            assertTrue(drained.await(30, TimeUnit.SECONDS), "the sync worker finished");
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
