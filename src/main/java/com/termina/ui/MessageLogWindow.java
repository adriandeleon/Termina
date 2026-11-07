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
 */
final class MessageLogWindow {

    private final Stage stage = new Stage();
    private final MessageLog log;
    private final ListView<MessageLog.Entry> list = new ListView<>();
    private final Runnable refresh = this::refresh;

    MessageLogWindow(MessageLog log) {
        this.log = log;
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPlaceholder(new Label(tr("messagelog.empty")));
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
                copySelected();
                e.consume();
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        Button copy = new Button(tr("debuglog.copy"));
        copy.setOnAction(e -> copySelected());
        Button clear = new Button(tr("debuglog.clear"));
        clear.setOnAction(e -> log.clear());
        Button close = new Button(tr("debuglog.close"));
        close.setOnAction(e -> stage.hide());
        close.setDefaultButton(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, copy, clear, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, list, buttons);
        root.setPadding(new Insets(12));
        Scene scene = new Scene(root, 640, 380);
        var css = MessageLogWindow.class.getResource("/com/termina/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        Fonts.installUiFont(scene);
        SecondaryWindows.closeOnEscape(stage, scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.G && e.isControlDown()) {
                stage.hide();
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

    private void refresh() {
        list.getItems().setAll(log.entries());
        // Seen now, so the status bar's count goes (MLOG-06).
        log.removeListener(refresh);
        log.markRead();
        log.addListener(refresh);
    }

    private void copySelected() {
        var selected = list.getSelectionModel().getSelectedItems();
        var lines = (selected.isEmpty() ? list.getItems() : selected)
                .stream().map(MessageLog::line).toList();
        if (lines.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(String.join(System.lineSeparator(), lines) + System.lineSeparator());
        Clipboard.getSystemClipboard().setContent(content);
    }
}
