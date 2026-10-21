package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.termina.ui.ShortcutReference.Shortcut;

import static com.termina.i18n.Messages.tr;

/**
 * Every shortcut in one place, searchable.
 *
 * <p>The rows are handed in by the window each time it opens, built from the menus and the binding
 * table it actually has — so the list is the truth about this window rather than a document that
 * could drift from it. A window of its own, unowned like About, so it can sit beside the terminal
 * while the chord is tried.
 */
final class ShortcutsWindow {

    private final Stage stage = new Stage();
    private final TextField search = new TextField();
    private final VBox list = new VBox(2);
    private List<Shortcut> all = List.of();

    ShortcutsWindow() {
        build();
    }

    void show(Window from, List<Shortcut> shortcuts) {
        all = List.copyOf(shortcuts);
        search.setText("");
        refill("");
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
        } else {
            SecondaryWindows.placeNear(stage, from);
            stage.show();
        }
        search.requestFocus();
    }

    Scene scene() {
        return stage.getScene();
    }

    /** Types into the search field as the user would. */
    void setQueryForCapture(String query) {
        search.setText(query);
    }

    /** The rows as shown, one line each — for a capture run to assert on. */
    List<String> rows() {
        List<String> out = new ArrayList<>();
        ShortcutReference.grouped(ShortcutReference.matching(all, search.getText()))
                .forEach((group, rows) -> rows.forEach(r -> out.add(group + ": " + r.label() + " = " + r.keys())));
        return out;
    }

    private void build() {
        stage.setTitle(tr("shortcuts.title"));
        Icons.applyTo(stage);

        search.setPromptText(tr("shortcuts.search"));
        search.textProperty().addListener((o, was, now) -> refill(now));

        list.setPadding(new Insets(0, 8, 0, 0));
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("shortcuts-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button close = new Button(tr("shortcuts.close"));
        close.setOnAction(e -> stage.hide());
        close.setDefaultButton(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(spacer, close);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 0, 0, 0));

        VBox root = new VBox(8, search, scroll, footer);
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root, 540, 620);
        var css = ShortcutsWindow.class.getResource("/com/termina/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        Fonts.installUiFont(scene);
        SecondaryWindows.closeOnEscape(stage, scene);
        // Enter in the search field is for searching, not for the default button.
        search.setOnAction(e -> e.consume());
        stage.setScene(scene);
    }

    private void refill(String query) {
        list.getChildren().clear();
        var groups = ShortcutReference.grouped(ShortcutReference.matching(all, query));
        if (groups.isEmpty()) {
            Label empty = new Label(tr("shortcuts.empty"));
            empty.getStyleClass().add("shortcuts-empty");
            list.getChildren().add(empty);
            return;
        }
        groups.forEach((group, rows) -> {
            Label heading = new Label(group);
            heading.getStyleClass().add("shortcuts-group");
            list.getChildren().add(heading);
            for (Shortcut shortcut : rows) {
                Label label = new Label(shortcut.label());
                label.setWrapText(true);
                HBox.setHgrow(label, Priority.ALWAYS);
                label.setMaxWidth(Double.MAX_VALUE);
                Label keys = new Label(shortcut.keys());
                keys.getStyleClass().add("shortcuts-keys");
                keys.setMinWidth(Region.USE_PREF_SIZE);
                HBox row = new HBox(12, label, keys);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("shortcuts-row");
                list.getChildren().add(row);
            }
        });
    }
}
