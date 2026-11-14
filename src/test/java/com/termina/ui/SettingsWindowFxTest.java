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
import com.termina.testing.Await;
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
                new Keybindings.Command("file.newTab", "File", "New Tab", Keymap.defaultFor("file.newTab")),
                new Keybindings.Command("file.closeTab", "File", "Close Tab", Keymap.defaultFor("file.closeTab")),
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
            window.fireKeysForCapture("file.newTab:C-S-y;file.closeTab:BS");
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
                List.of(new Keybindings.Command("file.newTab", "File", "New Tab", Keymap.defaultFor("file.newTab")));
        Path shell = java.nio.file.Files.writeString(dir.resolve("a-shell"), "#!/bin/sh\n");
        assertTrue(shell.toFile().setExecutable(true));

        TextField[] fields = new TextField[1];
        SettingsWindow[] windowHolder = new SettingsWindow[1];
        Stage[] ownerHolder = new Stage[1];
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

            fields[0] = shellField;
            windowHolder[0] = window;
            ownerHolder[0] = owner;
            return out;
        });

        // The paths are checked off the FX thread (PERF-01, Pauta 6.0.1), so each answer, and the
        // write that follows a good one, comes a moment after the key.
        TextField shellField = fields[0];
        onFx(() -> enter(shellField, shell.toString()));
        waitFor(() -> shell.toString().equals(settings.shell()), "the shell path to be written");
        stored.add(settings.shell());
        onFx(() -> enter(shellField, dir.resolve("no-such-shell").toString()));
        awaitMarked(shellField);
        stored.add(settings.shell());
        onFx(() -> enter(shellField, ""));
        waitFor(() -> settings.shell().isEmpty(), "the shell path to be cleared");
        stored.add(settings.shell());

        // A profile's command and directory, the same: marked, and the profile keeps its own.
        TextField[] profileFields = onFx(() -> {
            SettingsWindow window = windowHolder[0];
            for (Region page : window.pagesForTest()) {
                for (javafx.scene.control.Button button : find(page, javafx.scene.control.Button.class)) {
                    if (com.termina.i18n.Messages.tr("settings.profileAdd").equals(button.getText())) button.fire();
                }
            }
            return new TextField[] {
                field(window, "wsl.exe -d Ubuntu"),
                field(window, com.termina.i18n.Messages.tr("settings.profileDirectory.prompt"))
            };
        });
        TextField command = profileFields[0];
        TextField directory = profileFields[1];
        onFx(() -> enter(command, shell + " --login"));
        waitFor(() -> (shell + " --login").equals(userProfile(profiles).commandLine()), "the command to be written");
        stored.add(userProfile(profiles).commandLine());
        onFx(() -> enter(command, dir.resolve("no-such-program") + " --login"));
        awaitMarked(command);
        stored.add(userProfile(profiles).commandLine());
        onFx(() -> enter(command, shell + " --login"));
        onFx(() -> enter(directory, dir.toString()));
        waitFor(() -> dir.toString().equals(userProfile(profiles).workingDirectory()), "the folder to be written");
        stored.add(userProfile(profiles).workingDirectory());
        onFx(() -> enter(directory, dir.resolve("no-such-folder").toString()));
        awaitMarked(directory);
        stored.add(userProfile(profiles).workingDirectory());
        onFx(() -> {
            windowHolder[0].scene().getWindow().hide();
            ownerHolder[0].hide();
            return null;
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

    /**
     * DOCS-11 (Pauta 6.0.1): every page ends in a link, Help for and the page's name, that opens
     * that page's part of the documentation through the application's own way of opening a link.
     */
    @Test
    void everyPageHasALinkToItsDocumentation() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        ShellProfiles profiles = new ShellProfiles(settings, "Default Shell");
        List<String> opened = new ArrayList<>();
        List<String> links = onFx(() -> {
            SettingsWindow window = new SettingsWindow(settings, profiles, List.of(), null);
            window.openLinksWith(opened::add);
            List<String> texts = new ArrayList<>();
            for (Region page : window.pagesForTest()) {
                List<javafx.scene.control.Hyperlink> help = find(page, javafx.scene.control.Hyperlink.class).stream()
                        .filter(link -> link.getStyleClass().contains("settings-help-link"))
                        .toList();
                assertEquals(1, help.size(), "one help link a page");
                texts.add(help.get(0).getText());
                help.get(0).fire();
            }
            return texts;
        });
        List<String> wanted = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        for (SettingsWindow.Category page : SettingsWindow.Category.values()) {
            wanted.add(com.termina.i18n.Messages.tr("settings.helpFor", page.title()));
            addresses.add(SettingsHelp.url(page));
        }
        assertEquals(wanted.stream().sorted().toList(), links.stream().sorted().toList());
        assertEquals(
                addresses.stream().sorted().toList(), opened.stream().sorted().toList());
        assertTrue(links.contains("Help for Appearance"), links.toString());
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

    /**
     * Waits for the line under a path field that says what is wrong with it. The check behind the
     * mark starts after the one the commit made, so by then the commit has had its answer, and two
     * frames more let the write it would have made happen.
     */
    private static void awaitMarked(TextField field) throws InterruptedException {
        waitFor(
                () -> {
                    try {
                        return onFx(() -> field.getParent() instanceof javafx.scene.layout.VBox box
                                && box.getChildren().size() > 1
                                && box.getChildren().get(1).isVisible());
                    } catch (Exception e) {
                        return false;
                    }
                },
                "the mark under the field");
        Await.frames(2);
    }

    private static Void enter(TextField field, String text) {
        field.setText(text);
        field.fireEvent(new ActionEvent());
        return null;
    }

    private static com.termina.shell.Profile userProfile(ShellProfiles profiles) {
        try {
            return onFx(() -> profiles.userProfiles().get(0));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Await.frame();
        }
    }

    /**
     * QUAL-13 (Pauta 6.0.1): a settings round trip. Every switch, number and choice in the window
     * is set to something else, the settings are written and read back from the file as a restart
     * reads them, and a second window over those shows each control where the first left it.
     */
    @Test
    void everySwitchNumberAndChoiceSurvivesARestart() throws Exception {
        Path file = dir.resolve("roundtrip.json");
        Settings settings = new Settings(file);
        settings.load();
        List<Keybindings.Command> commands =
                List.of(new Keybindings.Command("file.newTab", "File", "New Tab", Keymap.defaultFor("file.newTab")));

        List<String> before = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> set = onFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 400, 300));
            owner.show();
            SettingsWindow window =
                    new SettingsWindow(settings, new ShellProfiles(settings, "System shell"), commands, null);
            window.show(owner);
            List<Node> controls = new ArrayList<>();
            for (Region page : window.pagesForTest()) valueControls(page, controls);
            for (Node control : controls) before.add(valueOf(control));
            names.addAll(keys(controls));
            for (Node control : controls) change(control);
            List<String> after = new ArrayList<>();
            for (Node control : controls) after.add(valueOf(control));
            window.scene().getWindow().hide();
            owner.hide();
            return after;
        });
        // The UI zoom is written a moment later, once its stylesheets are ready on a worker.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (settings.uiZoom() == Settings.DEFAULT_UI_ZOOM && System.nanoTime() < deadline) Await.frame();
        settings.flush();

        int changed = 0;
        List<String> unchanged = new ArrayList<>();
        for (int i = 0; i < set.size(); i++) {
            if (!set.get(i).equals(before.get(i))) changed++;
            else unchanged.add(names.get(i) + "=" + set.get(i));
        }
        assertTrue(set.size() >= 20, "switches, numbers and choices found: " + set.size());
        assertTrue(
                changed >= set.size() - 6,
                "almost every control took another value: " + changed + " of " + set.size() + "; not " + unchanged);

        Settings reread = new Settings(file);
        reread.load();
        List<String> shown = onFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 400, 300));
            owner.show();
            SettingsWindow window =
                    new SettingsWindow(reread, new ShellProfiles(reread, "System shell"), commands, null);
            window.show(owner);
            // Every one, greyed or not: turning a switch off greys the number beside it, and the
            // number is still what it was set to.
            List<Node> controls = new ArrayList<>();
            for (Region page : window.pagesForTest()) valueControls(page, controls, true);
            List<String> keys = keys(controls);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < controls.size(); i++) out.add(keys.get(i) + "=" + valueOf(controls.get(i)));
            window.scene().getWindow().hide();
            owner.hide();
            return out;
        });
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < set.size(); i++) {
            String want = names.get(i) + "=" + set.get(i);
            if (!shown.contains(want)) wrong.add(want);
        }
        assertEquals(List.of(), wrong, "each control reads back what it was set to; the window shows " + shown);
        // The zoom is the whole app's: every other test expects to find it at 100%.
        onFx(() -> {
            UiZoom.apply(Settings.DEFAULT_UI_ZOOM);
            return null;
        });
    }

    /** Every enabled switch, number and choice under {@code node}, in the order the page lays them out. */
    private static void valueControls(Node node, List<Node> out) {
        valueControls(node, out, false);
    }

    /** As above; with {@code disabledToo}, the ones a state of the page has greyed as well. */
    private static void valueControls(Node node, List<Node> out, boolean disabledToo) {
        boolean takesAValue = (node instanceof CheckBox
                        || node instanceof SettingSwitch
                        || node instanceof Slider
                        || (node instanceof ComboBox<?> combo
                                && !combo.getItems().isEmpty())
                        || (node instanceof Spinner<?> spinner && spinner.getValueFactory() != null))
                && (disabledToo || !node.isDisabled());
        if (takesAValue) {
            // Once each: the page on show is listed with the pages, and is the same nodes.
            boolean listed = false;
            for (Node have : out) listed |= have == node;
            if (!listed) out.add(node);
            return; // a spinner's own text field is the spinner
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            valueControls(scroll.getContent(), out, disabledToo);
        }
        if (node instanceof Parent parent) {
            for (Node child : new ArrayList<>(parent.getChildrenUnmodifiable())) {
                valueControls(child, out, disabledToo);
            }
        }
    }

    /** A name a second window gives the same control: its kind, its label, and which of that name it is. */
    private static List<String> keys(List<Node> controls) {
        List<String> out = new ArrayList<>();
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (Node control : controls) {
            String name = control.getClass().getSimpleName() + ":" + control.getAccessibleText();
            out.add(name + "#" + seen.merge(name, 1, Integer::sum));
        }
        return out;
    }

    private static String valueOf(Node control) {
        if (control instanceof CheckBox box) return String.valueOf(box.isSelected());
        if (control instanceof SettingSwitch sw) return String.valueOf(sw.isSelected());
        if (control instanceof Slider slider) return String.valueOf(Math.round(slider.getValue()));
        if (control instanceof ComboBox<?> combo) return String.valueOf(combo.getValue());
        if (control instanceof Spinner<?> spinner) return String.valueOf(spinner.getValue());
        return "";
    }

    /** Sets a control to something other than what it shows: the other state, the next step, another item. */
    private static void change(Node control) {
        if (control instanceof CheckBox box) {
            box.fire();
        } else if (control instanceof SettingSwitch sw) {
            sw.toggle();
        } else if (control instanceof Slider slider) {
            double middle = (slider.getMin() + slider.getMax()) / 2;
            slider.setValue(Math.round(slider.getValue()) == Math.round(middle) ? slider.getMin() : middle);
        } else if (control instanceof ComboBox<?> combo) {
            pickAnother(combo);
        } else if (control instanceof Spinner<?> spinner) {
            Object was = spinner.getValue();
            spinner.increment();
            if (java.util.Objects.equals(was, spinner.getValue())) spinner.decrement();
        }
    }

    private static <T> void pickAnother(ComboBox<T> combo) {
        for (T item : combo.getItems()) {
            if (!java.util.Objects.equals(item, combo.getValue())) {
                combo.setValue(item);
                return;
            }
        }
    }

    /** Works every control under {@code node}; counts switches, choices, steppers, sliders, fields. */
    private static void exercise(Node node, int[] counts) {
        if (node instanceof CheckBox box && !box.isDisabled()) {
            box.fire();
            box.fire();
            counts[0]++;
        } else if (node instanceof SettingSwitch sw && !sw.isDisabled()) {
            // KBD-18 (Pauta 6.0.1): an on/off row is a switch the keyboard works. Space flips it,
            // and so does a screen reader's press; there and back.
            boolean was = sw.isSelected();
            javafx.event.Event.fireEvent(
                    sw,
                    new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                            " ",
                            " ",
                            javafx.scene.input.KeyCode.SPACE,
                            false,
                            false,
                            false,
                            false));
            if (sw.isSelected() == was) throw new AssertionError("Space did not flip " + sw.getAccessibleText());
            sw.executeAccessibleAction(javafx.scene.AccessibleAction.FIRE);
            if (sw.isSelected() != was) throw new AssertionError("a press did not flip it back");
            if (sw.getAccessibleRole() != javafx.scene.AccessibleRole.TOGGLE_BUTTON || !sw.isFocusTraversable()) {
                throw new AssertionError(sw.getAccessibleText() + " is not a focusable toggle");
            }
            if (!Boolean.valueOf(was).equals(sw.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.SELECTED))) {
                throw new AssertionError(sw.getAccessibleText() + " does not say whether it is on");
            }
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
        settings.replaceKeyBindings(java.util.Map.of("file.newTab", "Shortcut+Shift+Y"));
        ShellProfiles profiles = new ShellProfiles(settings, "System shell");
        List<Keybindings.Command> commands =
                List.of(new Keybindings.Command("file.newTab", "File", "New Tab", Keymap.defaultFor("file.newTab")));

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
