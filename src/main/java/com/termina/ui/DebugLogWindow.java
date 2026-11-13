package com.termina.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/**
 * Shows what {@link DebugLog} has captured: a read-only console with Refresh, Copy, Export, Clear
 * and Report a Problem (DIAG-03), a find bar docked over the text (FIND-01, Pauta 6.0.1) and a
 * right-click menu on it (MENU-05).
 *
 * <p>Everything a button or a menu row does here is a method the window's commands also call
 * ({@code debugLog.find}, {@code debugLog.refresh}, {@code debugLog.copy}, {@code debugLog.clear},
 * {@code debugLog.export}), so the palette and a chord reach all of it (KBD-01).
 */
final class DebugLogWindow {

    private final Stage stage = new Stage();
    private final TextArea text = new TextArea();
    private final Label path = new Label();

    private final javafx.scene.control.TextField findField = new javafx.scene.control.TextField();
    private final Label findCount = new Label();
    private final HBox findBar = new HBox(4);
    /** A pause after the last key before a long log is searched, so typing stays ahead of it. */
    private final javafx.animation.PauseTransition findTyping =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(120));

    /** Where the menu's rows read their commands' chords; none until the manager says. */
    private java.util.function.Function<String, javafx.scene.input.@Nullable KeyCombination> chords = id -> null;

    void setChords(java.util.function.Function<String, javafx.scene.input.@Nullable KeyCombination> chords) {
        this.chords = chords;
    }

    private int[] found = new int[0];
    private int current = -1;
    /** The caret as it was when the bar opened: where every search of this opening starts. */
    private int findAnchor;

    DebugLogWindow(Window owner) {
        this(owner, null);
    }

    /** @param onReport opens Report a Problem, or null where nothing can (DIAG-03) */
    DebugLogWindow(Window owner, @Nullable Runnable onReport) {
        text.setEditable(false);
        text.setWrapText(false);
        text.getStyleClass().add("debug-log-text");
        text.setAccessibleText(tr("debuglog.title"));
        VBox.setVgrow(text, Priority.ALWAYS);

        path.getStyleClass().add("settings-path");
        path.setWrapText(true);

        buildFindBar();

        Button find = new Button(tr("debuglog.find"));
        find.setOnAction(e -> find());
        Button refresh = new Button(tr("debuglog.refresh"));
        refresh.setOnAction(e -> refresh());
        Button copy = new Button(tr("debuglog.copy"));
        copy.setOnAction(e -> copy());
        Button export = new Button(tr("debuglog.export"));
        export.setOnAction(e -> export());
        Button clear = new Button(tr("debuglog.clear"));
        clear.setOnAction(e -> clear());
        Button close = new Button(tr("debuglog.close"));
        close.setOnAction(e -> stage.close());
        close.setDefaultButton(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, find, refresh, copy, export, clear, spacer, close);
        if (onReport != null) {
            Button report = new Button(tr("command.reportProblem"));
            report.setOnAction(e -> onReport.run());
            buttons.getChildren().add(buttons.getChildren().indexOf(spacer), report);
        }
        buttons.setAlignment(Pos.CENTER_LEFT);

        // The text's own menu (MENU-05): the rows are the buttons' actions, each a command. It
        // replaces the stock one a text area comes with, which is worded in the JVM's language
        // and offers Cut and Paste for text that cannot be edited.
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(
                MenuChords.command(row(tr("debuglog.find"), MenuIcons.search(), this::find), "debugLog.find"),
                MenuChords.command(row(tr("debuglog.copy"), MenuIcons.copy(), this::copy), "debugLog.copy"),
                new javafx.scene.control.SeparatorMenuItem(),
                MenuChords.command(row(tr("debuglog.refresh"), null, this::refresh), "debugLog.refresh"),
                MenuChords.command(row(tr("debuglog.export"), null, this::export), "debugLog.export"),
                MenuChords.command(
                        dangerous(row(tr("debuglog.clear"), MenuIcons.clear(), this::clear)), "debugLog.clear"));
        // Each row's chord, from the bindings in force as the menu shows (MENU-15).
        menu.setOnShowing(e -> MenuChords.fill(menu.getItems(), chords));
        if (onReport != null) {
            menu.getItems()
                    .addAll(
                            new javafx.scene.control.SeparatorMenuItem(),
                            MenuChords.command(row(tr("command.reportProblem"), null, onReport), "reportProblem"));
        }
        text.setContextMenu(OneMenu.installRightClick(menu));

        VBox root = new VBox(10, findBar, text, path, buttons);
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, 780, 460);
        var css = DebugLogWindow.class.getResource("/com/termina/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        Fonts.installUiFont(scene);
        // Escape in the find field closes the bar; anywhere else, the window.
        SecondaryWindows.closeOnEscape(stage, scene, findField::isFocused);
        OneMenu.closeOnOutsidePress(scene);
        // Ctrl+F, Cmd+F on macOS: no shell in this window to want the plain chord.
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.F && e.isShortcutDown() && !e.isAltDown()) {
                find();
                e.consume();
            }
        });

        stage.setTitle(tr("debuglog.title"));
        stage.setScene(scene);
        // Owned but not modal: the point is to watch it while provoking whatever is going wrong.
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        Icons.applyTo(stage);
        refresh();
    }

    private static javafx.scene.control.MenuItem row(String title, javafx.scene.@Nullable Node icon, Runnable action) {
        javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(title, icon);
        item.setOnAction(e -> action.run());
        return item;
    }

    /** Marks a row that discards something, so its text is drawn in the danger colour (MENU-12). */
    private static javafx.scene.control.MenuItem dangerous(javafx.scene.control.MenuItem item) {
        item.getStyleClass().add("danger");
        return item;
    }

    /** The bar: the query, the count, and Next, Previous and Close, hidden until asked for. */
    private void buildFindBar() {
        findBar.getStyleClass().add("search-bar");
        findBar.setAlignment(Pos.CENTER_LEFT);
        findField.getStyleClass().add("search-field");
        findField.setPromptText(tr("search.prompt"));
        findField.setAccessibleText(tr("search.prompt"));
        HBox.setHgrow(findField, Priority.ALWAYS);
        // Searched as it is typed (FIND-02), a moment after the last key.
        findTyping.setOnFinished(e -> runFind());
        findField.textProperty().addListener((o, was, now) -> findTyping.playFromStart());
        findField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case ESCAPE -> closeFind();
                case ENTER -> step(!e.isShiftDown());
                default -> {
                    return;
                }
            }
            // Consumed: Enter is not the window's Close button, and Escape closes the bar, not the window.
            e.consume();
        });
        findCount.getStyleClass().add("search-count");
        findCount.setMinWidth(64);
        // Out of the Tab order: Enter, Shift+Enter and Escape in the field do what they do (FIND-16).
        Button next = barButton(MenuIcons.arrowDown(), tr("debuglog.findNext"), () -> step(true));
        Button previous = barButton(MenuIcons.arrowUp(), tr("debuglog.findPrevious"), () -> step(false));
        Button close = barButton(MenuIcons.close(), tr("search.close"), this::closeFind);
        findBar.getChildren().addAll(MenuIcons.search(), findField, findCount, previous, next, close);
        findBar.setVisible(false);
        findBar.setManaged(false);
    }

    private static Button barButton(javafx.scene.Node icon, String name, Runnable action) {
        Button button = new Button(null, icon);
        button.getStyleClass().add("search-button");
        button.setFocusTraversable(false);
        IconNames.name(button, name);
        button.setOnAction(e -> action.run());
        return button;
    }

    /**
     * Opens the find bar, showing the window first; run while the bar is open it steps to the next
     * match (FIND-04). What {@code debugLog.find}, the Find button and Ctrl+F do.
     */
    void find() {
        bringUp();
        if (findBar.isVisible()) {
            step(true);
            findField.requestFocus();
            return;
        }
        findAnchor = text.getCaretPosition() >= text.getLength() ? 0 : text.getCaretPosition();
        findBar.setVisible(true);
        findBar.setManaged(true);
        // A bar shown in this pulse has no skin yet, and a field without one ignores typing.
        findBar.applyCss();
        findBar.layout();
        findField.requestFocus();
        findField.selectAll();
        runFind();
    }

    /** Searches the text for the field's query and goes to the match the search starts on. */
    private void runFind() {
        findTyping.stop();
        if (!findBar.isVisible()) return;
        found = TextFind.matches(text.getText(), findField.getText());
        current = TextFind.startAt(found, findAnchor);
        showMatch();
    }

    /** Next or previous, wrapping at the ends (FIND-06); a query still being typed is searched first. */
    private void step(boolean forward) {
        if (findTyping.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            runFind();
            return;
        }
        current = TextFind.step(current, found, forward);
        showMatch();
    }

    /**
     * The active match, selected and scrolled to, and the count beside the field. A text area
     * draws one selection and nothing else, so the active match is the one shown (FIND-05).
     */
    private void showMatch() {
        if (findField.getText().isEmpty()) {
            findCount.setText("");
            text.deselect();
        } else if (current < 0) {
            findCount.setText(tr("search.none"));
            text.deselect();
        } else {
            findCount.setText(tr("search.count", current + 1, total(found.length)));
            int start = found[current];
            text.selectRange(start, start + findField.getText().length());
        }
    }

    static String total(int count) {
        return count > TextFind.MAX_MATCHES ? TextFind.MAX_MATCHES + "+" : String.valueOf(count);
    }

    /**
     * Closes the bar (FIND-07): the pending search is stopped, so nothing moves once the bar is
     * gone, the selection that showed the match goes, and the keyboard is back in the text.
     */
    private void closeFind() {
        findTyping.stop();
        found = new int[0];
        current = -1;
        text.deselect();
        findBar.setVisible(false);
        findBar.setManaged(false);
        text.requestFocus();
    }

    /** Re-reads the log; what {@code debugLog.refresh} and the Refresh button do. */
    void refresh() {
        String captured = DebugLog.text();
        text.setText(captured.isEmpty() ? tr("debuglog.empty") : captured);
        // Scrolled to the end: the newest line is the one being looked for.
        text.positionCaret(text.getText().length());
        text.setScrollTop(Double.MAX_VALUE);
        path.setText(DebugLog.file() == null ? "" : String.valueOf(DebugLog.file()));
        // An open bar searches what is shown now.
        if (findBar.isVisible()) {
            findAnchor = 0;
            runFind();
        }
    }

    /**
     * Copies the selected text, or the whole log when nothing is selected; what
     * {@code debugLog.copy} and the Copy button do.
     */
    void copy() {
        // Run from the palette with the window closed, the text is as old as its last showing.
        if (!stage.isShowing()) refresh();
        String selected = text.getSelectedText();
        ClipboardContent content = new ClipboardContent();
        content.putString(selected == null || selected.isEmpty() ? text.getText() : selected);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Empties the log; what {@code debugLog.clear} and the Clear button do. */
    void clear() {
        DebugLog.clear();
        refresh();
    }

    /**
     * Export (DIAG-03): the whole session, to a file of the user's choosing, written off the FX
     * thread. What {@code debugLog.export} and the Export button do.
     */
    void export() {
        bringUp();
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setInitialFileName("termina-debug-log.txt");
        java.io.File target = chooser.showSaveDialog(stage);
        if (target == null) return;
        String captured = DebugLog.text();
        Thread writer = new Thread(
                () -> {
                    try {
                        java.nio.file.Files.writeString(target.toPath(), captured + System.lineSeparator());
                    } catch (java.io.IOException failed) {
                        java.util.logging.Logger.getLogger(DebugLogWindow.class.getName())
                                .log(java.util.logging.Level.WARNING, "could not export the debug log", failed);
                    }
                },
                "termina-debuglog-export");
        writer.setDaemon(true);
        writer.start();
    }

    /** Shows the window with the log as it is now; what the Debug Log command does. */
    void show() {
        refresh();
        boolean opening = !stage.isShowing();
        stage.show();
        stage.toFront();
        // The keyboard starts in the text, where the Menu key opens its menu and a selection is made.
        if (opening) text.requestFocus();
    }

    /** Shows the window for an action in it; one already up keeps its text, its place and its search. */
    private void bringUp() {
        if (stage.isShowing()) stage.toFront();
        else show();
    }

    // ---------------------------------------------------------------- for tests and the capture hook

    javafx.scene.Scene scene() {
        return stage.getScene();
    }

    /** Types a query into the find bar, opening it first, and searches at once. */
    void searchFor(String query) {
        if (!findBar.isVisible()) find();
        findField.setText(query);
        runFind();
    }

    /** The bar as it stands: shown or not, the count, the selected text, and who has the keyboard. */
    String findReport() {
        javafx.scene.Node owner = stage.getScene().getFocusOwner();
        return (findBar.isVisible() ? "open" : "closed") + " count=<" + findCount.getText() + "> selected=<"
                + text.getSelectedText() + "> focus="
                + (owner == findField ? "field" : owner == text ? "text" : "other");
    }

    /** Fires a key at the find field, as the keyboard would. */
    void pressInFindForTest(javafx.scene.input.KeyCode code, boolean shift) {
        javafx.event.Event.fireEvent(
                findField,
                new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
    }

    /** The titles of the text's right-click rows. */
    java.util.List<String> menuRowsForTest() {
        return text.getContextMenu().getItems().stream()
                .filter(item -> !(item instanceof javafx.scene.control.SeparatorMenuItem))
                .map(javafx.scene.control.MenuItem::getText)
                .toList();
    }

    String textForTest() {
        return text.getText();
    }
}
