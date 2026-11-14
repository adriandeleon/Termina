package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

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

/**
 * The picker as a card (QOPEN-02 to QOPEN-11, Pauta 6.0.1): what it shows, the keys it moves and
 * closes on, the preview and its undo, the one card the overlay host holds, and the four commands
 * that open one in a real window.
 */
@Tag("fx")
class QuickPickFxTest {

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

    /** The movement chords as the key maps ship them. */
    private static Map<String, PaletteKeys.Action> moves() {
        return PaletteKeys.moves(id -> KeyChord.of(Keymap.lists().get(id), Keybindings.MAC));
    }

    /** A stage with a field that has the keyboard, and an overlay host over it. */
    private record Bench(Stage stage, TextField field, StackPane layer, OverlayHost host, boolean[] fellBack) {}

    private static Bench bench() {
        TextField field = new TextField();
        StackPane layer = new StackPane(new VBox(field));
        Stage stage = new Stage();
        stage.setScene(new Scene(layer, 900, 600));
        stage.show();
        field.requestFocus();
        boolean[] fellBack = new boolean[1];
        return new Bench(stage, field, layer, new OverlayHost(layer, () -> fellBack[0] = true), fellBack);
    }

    private static QuickPick<String> picker(
            Bench bench, String title, String prompt, List<String> source, List<String> chosen) {
        return new QuickPick<>(
                bench.host(), title, prompt, () -> source, s -> s, s -> "", chosen::add, QuickPickFxTest::moves);
    }

    @Test
    void itOpensOnTheValueInForceInTheSuppliersOrderAndFiltersAStableList() throws Exception {
        List<String> seen = onFx(() -> {
            Bench bench = bench();
            List<String> source = new ArrayList<>(List.of("zsh", "bash", "fish", "nushell"));
            QuickPick<String> picker =
                    picker(bench, "New Tab from Profile", "Profile name…", source, new ArrayList<>());
            picker.setCurrent("fish"::equals);
            picker.show();
            List<String> out = new ArrayList<>();
            // QOPEN-02: a title naming the command and a prompt; the field has the keyboard.
            Node owner = bench.stage().getScene().getFocusOwner();
            out.add(picker.titleForTest() + " | "
                    + (owner instanceof TextInputControl field ? field.getPromptText() : "no field") + " | "
                    + picker.legendForTest().contains("↵"));
            // QOPEN-04: the supplier's order, the value in force selected.
            out.add(picker.rowsForTest() + " " + picker.selectedForTest());
            // QOPEN-03: the items were read when it was shown; a change to the source is not seen
            // until the picker reads again, and then the query is kept.
            source.add("ash");
            picker.setQueryForTest("sh");
            out.add(picker.rowsForTest() + " " + picker.selectedForTest());
            picker.reload();
            out.add(picker.rowsForTest() + " " + picker.queryForTest());
            // QOPEN-10: a list with nothing in it says so.
            picker.setQueryForTest("qqq");
            out.add(picker.rowsForTest() + " " + picker.placeholderForTest().isBlank());
            picker.hide();
            bench.stage().close();
            return out;
        });
        assertEquals("New Tab from Profile | Profile name… | true", seen.get(0));
        assertEquals("[zsh, bash, fish, nushell] fish", seen.get(1));
        assertEquals("[zsh, bash, fish, nushell] zsh", seen.get(2), "typed: ties stay in the supplier's order");
        assertEquals("[zsh, bash, fish, nushell, ash] sh", seen.get(3), "read again, the query kept");
        assertEquals("[] false", seen.get(4));
    }

    @Test
    void itMovesOnThePalettesKeysChoosesAfterHidingAndCancelsOnEscape() throws Exception {
        List<String> seen = onFx(() -> {
            Bench bench = bench();
            List<String> chosen = new ArrayList<>();
            boolean[] upWhenChosen = new boolean[1];
            QuickPick<String> picker = new QuickPick<>(
                    bench.host(),
                    "Pick",
                    "…",
                    () -> List.of("one", "two", "three", "four"),
                    s -> s,
                    s -> "",
                    item -> {
                        chosen.add(item);
                        upWhenChosen[0] = bench.host().isShowing();
                    },
                    QuickPickFxTest::moves);
            List<String> out = new ArrayList<>();
            picker.show();
            // QOPEN-06: the chords of the four nav commands, and the arrows whatever is bound.
            picker.fireKeyForTest("C-n");
            picker.fireKeyForTest("C-n");
            out.add(picker.selectedForTest());
            picker.fireKeyForTest("C-p");
            out.add(picker.selectedForTest());
            picker.fireKeyForTest("down");
            picker.fireKeyForTest("down");
            out.add(picker.selectedForTest());
            picker.fireKeyForTest("up");
            out.add(picker.selectedForTest());
            // Enter chooses: the card is down, and the keyboard back, before the choice runs.
            picker.fireKeyForTest("enter");
            out.add(chosen + " " + upWhenChosen[0] + " " + picker.isShowing() + " "
                    + (bench.stage().getScene().getFocusOwner() == bench.field()));
            // QOPEN-07: Escape and Ctrl+G cancel, and the keyboard goes back where it was.
            picker.show();
            picker.fireKeyForTest("escape");
            out.add(picker.isShowing() + " " + (bench.stage().getScene().getFocusOwner() == bench.field()));
            picker.show();
            picker.fireKeyForTest("C-g");
            out.add(picker.isShowing() + " " + chosen.size());
            bench.stage().close();
            return out;
        });
        assertEquals("three", seen.get(0));
        assertEquals("two", seen.get(1));
        assertEquals("four", seen.get(2));
        assertEquals("three", seen.get(3));
        assertEquals("[three] false false true", seen.get(4));
        assertEquals("false true", seen.get(5));
        assertEquals("false 1", seen.get(6));
    }

    @Test
    void aPreviewFollowsTheSelectionAndIsUndoneOnADismissalButNotAfterAChoice() throws Exception {
        // QOPEN-08, and the undo QOPEN-07 asks a test for.
        List<String> seen = onFx(() -> {
            Bench bench = bench();
            List<String> previewed = new ArrayList<>();
            int[] undone = new int[1];
            List<String> chosen = new ArrayList<>();
            QuickPick<String> picker = picker(bench, "Pick", "…", List.of("one", "two", "three"), chosen);
            picker.setCurrent("two"::equals);
            picker.setPreview(previewed::add, () -> undone[0]++);
            List<String> out = new ArrayList<>();
            picker.show();
            out.add("opened on " + picker.selectedForTest() + ": " + previewed);
            picker.fireKeyForTest("down");
            picker.fireKeyForTest("up");
            picker.fireKeyForTest("up");
            out.add(previewed.toString());
            picker.fireKeyForTest("escape");
            out.add("undone " + undone[0] + " chosen " + chosen);
            picker.show();
            picker.fireKeyForTest("down");
            picker.fireKeyForTest("enter");
            out.add("undone " + undone[0] + " chosen " + chosen);
            // Closing the card from outside it, as a press on what is behind it does, is a dismissal too.
            picker.show();
            picker.fireKeyForTest("up");
            bench.host().hide();
            out.add("undone " + undone[0]);
            bench.stage().close();
            return out;
        });
        assertEquals("opened on two: []", seen.get(0), "nothing is previewed for the row it opens on");
        assertEquals("[three, two, one]", seen.get(1));
        assertEquals("undone 1 chosen []", seen.get(2));
        assertEquals("undone 1 chosen [three]", seen.get(3), "a choice is not undone");
        assertEquals("undone 2", seen.get(4));
    }

    @Test
    void theHostHoldsOneCardAndKeepsWhereTheKeyboardWasBeforeTheFirst() throws Exception {
        // QOPEN-11: showing a card removes the one that was up, and the keyboard goes back, when
        // the last closes, to the node that had it before the first opened.
        List<String> seen = onFx(() -> {
            Bench bench = bench();
            List<String> hidden = new ArrayList<>();
            Label first = new Label("first");
            TextField second = new TextField();
            OverlayHost.Scrim scrim = new OverlayHost.Scrim("palette-backdrop", "Close");
            List<String> out = new ArrayList<>();
            bench.host().show(first, scrim, () -> hidden.add("first"));
            out.add(bench.layer().getChildren().size() + " " + bench.host().isShowing(first));
            bench.host().show(second, scrim, () -> hidden.add("second"));
            second.requestFocus();
            out.add(bench.layer().getChildren().size() + " " + bench.host().isShowing(first) + " "
                    + bench.host().isShowing(second) + " " + hidden);
            bench.host().hide(first); // not the card showing: nothing happens
            out.add(String.valueOf(bench.host().isShowing()));
            bench.host().hide();
            out.add(bench.layer().getChildren().size() + " " + hidden + " "
                    + (bench.stage().getScene().getFocusOwner() == bench.field()) + " " + bench.fellBack()[0]);
            // With the node that had the keyboard out of the scene, the terminal's fallback has it.
            bench.host().show(second, scrim, () -> {});
            second.requestFocus();
            bench.layer().getChildren().set(0, new VBox());
            bench.host().hide();
            out.add(String.valueOf(bench.fellBack()[0]));
            bench.stage().close();
            return out;
        });
        assertEquals("3 true", seen.get(0), "what was there, the backdrop and the card");
        assertEquals("3 false true [first]", seen.get(1), "one card: the second took the first's place");
        assertEquals("true", seen.get(2));
        assertEquals("1 [first, second] true false", seen.get(3));
        assertEquals("true", seen.get(4));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
    void theFourCommandsOpenAPickerInTheWindowAndTheThemeIsPreviewedAndPutBack() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setThemeId("editora-light");
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell to start");

        List<String> seen = onFx(() -> {
            List<String> out = new ArrayList<>();
            TerminalView view = java.util.Objects.requireNonNull(window.activeTerminal());
            view.requestFocus();

            // Select Color Theme: opens on the theme in force; moving shows the next one in the
            // window, and Escape puts back the one there was.
            run(window, "view.selectTheme");
            QuickPick<?> themes = java.util.Objects.requireNonNull(window.pickerForTest("view.selectTheme"));
            out.add(themes.titleForTest() + " | " + themes.selectedForTest() + " | "
                    + (window.stage().getScene().getFocusOwner() instanceof TextInputControl) + " | "
                    + themes.rowsForTest().getFirst());
            themes.fireKeyForTest("down");
            out.add(themes.selectedForTest() + " -> " + settings.themeId());
            themes.fireKeyForTest("escape");
            out.add(settings.themeId() + " " + themes.isShowing() + " "
                    + (window.stage().getScene().getFocusOwner() == view));
            // And Enter keeps the one chosen.
            run(window, "view.selectTheme");
            themes.setQueryForTest("high contrast");
            themes.fireKeyForTest("enter");
            out.add(settings.themeId());

            // Select Cursor Shape: a setting's values.
            run(window, "view.selectCursorShape");
            QuickPick<?> shapes = java.util.Objects.requireNonNull(window.pickerForTest("view.selectCursorShape"));
            out.add(shapes.rowsForTest().size() + " " + shapes.selectedForTest());
            shapes.fireKeyForTest("down");
            shapes.fireKeyForTest("enter");
            out.add(String.valueOf(settings.cursorShape()));

            // Select Font: the families installed, the one drawn with selected.
            run(window, "view.selectFont");
            QuickPick<?> fonts = java.util.Objects.requireNonNull(window.pickerForTest("view.selectFont"));
            out.add(String.valueOf(!fonts.rowsForTest().isEmpty()
                    && fonts.selectedForTest().equals(MonospaceFonts.resolve(settings.fontFamily()))));
            // One card at a time: the palette takes the picker's place, and closing it gives the
            // keyboard back to the terminal, which had it before the picker.
            run(window, "palette.show");
            out.add(fonts.isShowing() + " " + (window.stage().getScene().getFocusOwner() instanceof TextInputControl));
            run(window, "palette.show");
            out.add(String.valueOf(window.stage().getScene().getFocusOwner() == view));

            // New Tab from Profile: Enter on the default profile opens a tab.
            int before = window.sessionSnapshot().tabs().size();
            run(window, "file.newTabFromProfile");
            QuickPick<?> profiles = java.util.Objects.requireNonNull(window.pickerForTest("file.newTabFromProfile"));
            out.add(String.valueOf(!profiles.selectedForTest().isEmpty()));
            profiles.fireKeyForTest("enter");
            out.add(String.valueOf(window.sessionSnapshot().tabs().size() - before));
            return out;
        });
        assertEquals("Select Color Theme | Editora Light | true | " + Themes.systemDisplayName(), seen.get(0));
        assertFalse(seen.get(1).endsWith("editora-light"), seen.get(1));
        assertEquals("editora-light false true", seen.get(2), "Escape puts the theme back");
        assertEquals("high-contrast", seen.get(3));
        assertEquals("3 " + com.termina.i18n.Messages.tr("cursor.block"), seen.get(4));
        assertEquals("UNDERLINE", seen.get(5));
        assertEquals("true", seen.get(6));
        assertEquals("false true", seen.get(7));
        assertEquals("true", seen.get(8));
        assertEquals("true", seen.get(9));
        assertEquals("1", seen.get(10));
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
