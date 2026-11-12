package com.termina.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import static com.termina.i18n.Messages.tr;

/**
 * The Message Log (DIAG-01, MLOG-01..06, Pauta 2.0.2): this session's messages, newest first, with
 * the time each was said. Opened from the status bar's echo area or Help → Message Log. Escape or
 * C-g closes it, and so does going back to the window behind; Ctrl+C (Cmd+C) copies the selected
 * lines. Opening it counts as reading the errors in it.
 *
 * <p>A filter field over the list narrows it as it is typed (FIND-03, Pauta 6.0.1); Escape in the
 * field empties it, and from an empty one goes back to the list. A row's right-click menu has
 * Copy and Clear (MENU-05), which are the commands {@code messageLog.copy} and
 * {@code messageLog.clear} and what the two buttons do (KBD-01).
 */
final class MessageLogWindow {

    private final Stage stage = new Stage();
    private final MessageLog log;
    private final ListView<MessageLog.Entry> list = new ListView<>();
    private final javafx.scene.control.TextField filter = new javafx.scene.control.TextField();
    private final Label noEntries = new Label(tr("messagelog.empty"));
    private final Runnable refresh = this::refresh;

    MessageLogWindow(MessageLog log) {
        this.log = log;
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPlaceholder(noEntries);
        filter.setPromptText(tr("messagelog.filter"));
        filter.setAccessibleText(tr("messagelog.filter"));
        filter.textProperty().addListener((o, was, now) -> refresh());
        filter.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.ESCAPE) return;
            // One layer at a time: a filter with something in it empties, an empty one gives the
            // list back; the next Escape, in the list, closes the window.
            if (filter.getText().isEmpty()) list.requestFocus();
            else filter.clear();
            e.consume();
        });
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(MessageLog.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                getStyleClass().removeAll("state-error", "state-warning");
                if (empty || entry == null) {
                    setText(null);
                    return;
                }
                setText(MessageLog.line(entry));
                setWrapText(true);
                setPrefWidth(0);
                switch (entry.severity()) {
                    case ERROR -> getStyleClass().add("state-error");
                    case WARNING -> getStyleClass().add("state-warning");
                    case INFO -> {}
                }
            }
        });
        list.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
                copy();
                e.consume();
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        Button copy = new Button(tr("debuglog.copy"));
        copy.setOnAction(e -> copy());
        Button clear = new Button(tr("debuglog.clear"));
        clear.setOnAction(e -> clear());
        // A row's menu (MENU-05): the same two actions, each a command.
        javafx.scene.control.MenuItem copyRow =
                new javafx.scene.control.MenuItem(tr("debuglog.copy"), MenuIcons.copy());
        copyRow.setOnAction(e -> copy());
        javafx.scene.control.MenuItem clearRow =
                new javafx.scene.control.MenuItem(tr("debuglog.clear"), MenuIcons.clear());
        clearRow.setOnAction(e -> clear());
        list.setContextMenu(OneMenu.installRightClick(new javafx.scene.control.ContextMenu(copyRow, clearRow)));
        Button close = new Button(tr("debuglog.close"));
        close.setOnAction(e -> stage.hide());
        close.setDefaultButton(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        cutNote.getStyleClass().add("settings-row-description");
        HBox buttons = new HBox(8, copy, clear, cutNote, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, filter, list, buttons);
        root.setPadding(new Insets(12));
        Scene scene = new Scene(root, 640, 380);
        var css = MessageLogWindow.class.getResource("/com/termina/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        Fonts.installUiFont(scene);
        // Escape in the filter is the filter's; anywhere else it closes the window.
        SecondaryWindows.closeOnEscape(stage, scene, filter::isFocused);
        OneMenu.closeOnOutsidePress(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.G && e.isControlDown()) {
                stage.hide();
                e.consume();
            } else if (e.getCode() == KeyCode.F && e.isShortcutDown() && !e.isAltDown()) {
                // Ctrl+F, Cmd+F on macOS: to the filter, where a find bar's chord would lead.
                filter.requestFocus();
                filter.selectAll();
                e.consume();
            }
        });
        stage.setTitle(tr("messagelog.title"));
        stage.setScene(scene);
        // A glance, not a place to work: going back to the window behind closes it (MLOG-03).
        stage.focusedProperty().addListener((o, was, now) -> {
            if (was && !now) stage.hide();
        });
        stage.setOnHidden(e -> log.removeListener(refresh));
        Icons.applyTo(stage);
    }

    void show(Window from) {
        log.addListener(refresh);
        refresh();
        if (!stage.isShowing()) SecondaryWindows.placeNear(stage, from);
        stage.show();
        stage.toFront();
        list.requestFocus();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    /** Says the oldest messages are gone, when they are (PERF-11): a log that stops short says nothing. */
    private final Label cutNote = new Label();

    private void refresh() {
        String typed = filter.getText() == null ? "" : filter.getText();
        list.getItems()
                .setAll(log.entries().stream()
                        .filter(entry -> TextFind.passes(MessageLog.line(entry), typed))
                        .toList());
        // An empty list says why it is empty: nothing was said, or nothing said passes the filter.
        noEntries.setText(tr(typed.isBlank() || log.entries().isEmpty() ? "messagelog.empty" : "search.none"));
        cutNote.setText(log.cut() == 0 ? "" : tr("messagelog.cut", log.cut()));
        // Seen now, so the status bar's count goes (MLOG-06).
        log.removeListener(refresh);
        log.markRead();
        log.addListener(refresh);
    }

    /**
     * Copies the selected lines, or every line shown when none is selected; with the window closed,
     * the whole log. What {@code messageLog.copy}, the Copy button and Ctrl+C in the list do.
     */
    void copy() {
        var selected = list.getSelectionModel().getSelectedItems();
        java.util.List<MessageLog.Entry> entries =
                !stage.isShowing() ? log.entries() : selected.isEmpty() ? list.getItems() : selected;
        var lines = entries.stream().map(MessageLog::line).toList();
        if (lines.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(String.join(System.lineSeparator(), lines) + System.lineSeparator());
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Empties the log; what {@code messageLog.clear} and the Clear button do. */
    void clear() {
        log.clear();
    }

    // ---------------------------------------------------------------- for tests and the capture hook

    javafx.scene.Scene scene() {
        return stage.getScene();
    }

    /** Types into the filter, as the keyboard would. */
    void filterBy(String typed) {
        filter.setText(typed);
    }

    /** Fires Escape at the filter field. */
    void escapeInFilterForTest() {
        javafx.event.Event.fireEvent(
                filter, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
    }

    /** The filter's text, the lines shown, what an empty list says, and who has the keyboard. */
    String report() {
        javafx.scene.Node owner = stage.getScene().getFocusOwner();
        return "filter=<" + filter.getText() + "> lines="
                + list.getItems().stream().map(MessageLog.Entry::text).toList()
                + " empty=<" + noEntries.getText() + "> focus="
                + (owner == filter ? "filter" : owner == list ? "list" : "other");
    }

    /** The titles of a row's right-click menu. */
    java.util.List<String> menuRowsForTest() {
        return list.getContextMenu().getItems().stream()
                .map(javafx.scene.control.MenuItem::getText)
                .toList();
    }
}
