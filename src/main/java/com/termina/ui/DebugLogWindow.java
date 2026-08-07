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

import static com.termina.i18n.Messages.tr;

/** Shows what {@link DebugLog} has captured, with a button to copy it into a bug report. */
final class DebugLogWindow {

    private final Stage stage = new Stage();
    private final TextArea text = new TextArea();
    private final Label path = new Label();

    DebugLogWindow(Window owner) {
        text.setEditable(false);
        text.setWrapText(false);
        text.getStyleClass().add("debug-log-text");
        VBox.setVgrow(text, Priority.ALWAYS);

        path.getStyleClass().add("settings-path");
        path.setWrapText(true);

        Button refresh = new Button(tr("debuglog.refresh"));
        refresh.setOnAction(e -> refresh());
        Button copy = new Button(tr("debuglog.copy"));
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        Button clear = new Button(tr("debuglog.clear"));
        clear.setOnAction(e -> {
            DebugLog.clear();
            refresh();
        });
        Button close = new Button(tr("debuglog.close"));
        close.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, refresh, copy, clear, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, text, path, buttons);
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, 780, 460);
        var css = DebugLogWindow.class.getResource("/com/termina/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        Fonts.installUiFont(scene);

        stage.setTitle(tr("debuglog.title"));
        stage.setScene(scene);
        // Owned but not modal: the point is to watch it while provoking whatever is going wrong.
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        Icons.applyTo(stage);
        refresh();
    }

    private void refresh() {
        String captured = DebugLog.text();
        text.setText(captured.isEmpty() ? tr("debuglog.empty") : captured);
        // Scrolled to the end: the newest line is the one being looked for.
        text.positionCaret(text.getText().length());
        text.setScrollTop(Double.MAX_VALUE);
        path.setText(DebugLog.file() == null ? "" : String.valueOf(DebugLog.file()));
    }

    void show() {
        refresh();
        stage.show();
        stage.toFront();
    }
}
