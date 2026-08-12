package com.termina.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import static com.termina.i18n.Messages.tr;

/**
 * The zoom controls as one menu row, the way a browser presents them.
 *
 * <p>Three separate items made the reader do arithmetic: Zoom In said nothing about where the zoom
 * currently was, and Actual Size was the only hint that there was a level at all. One row shows the
 * level and changes it in place.
 *
 * <p><b>The menu deliberately stays open</b> while the buttons are pressed. Zooming is something
 * you do two or three times to find the right size, and a menu that closes after each step makes
 * that four gestures instead of one — which is why every browser behaves this way.
 *
 * <p>The percentage is itself the reset, as in Firefox: a separate "Actual Size" row would be a
 * second thing to explain for something the number already implies.
 */
final class ZoomMenuRow {

    /** What the row does. Supplied by the window, which owns the settings the zoom lives in. */
    interface Actions {
        void zoomIn();

        void zoomOut();

        void reset();

        void toggleFullScreen();

        int percent();
    }

    /** Separates full screen from the zoom trio. The row's own spacing is 4. */
    private static final double FULL_SCREEN_GAP = 10;

    private CustomMenuItem item;
    private final Label percent = new Label();
    private final Actions actions;

    ZoomMenuRow(Actions actions) {
        this.actions = actions;
        Label title = new Label(tr("menu.zoom"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        Button out = button("−", tr("menu.zoomOut"), actions::zoomOut);
        Button in = button("+", tr("menu.zoomIn"), actions::zoomIn);

        // Not a Label: the percentage is the reset control, and a reader who does not know that
        // still gets the number. Styled flat so it reads as a value rather than a third button.
        Button reset = button("", tr("menu.actualSize"), actions::reset);
        reset.getStyleClass().add("zoom-value");
        reset.setGraphic(percent);

        // Closes the menu, unlike its neighbours. The row stays open because zooming is something
        // you do two or three times to find the right size; going full screen is done once, and a
        // menu left sitting over the newly full-screen window reads as nothing having happened —
        // the menu being the thing you are looking at. Firefox's panel behaves the same way.
        Button fullScreen = button("⤢", tr("menu.fullScreen"), () -> {
            actions.toggleFullScreen();
            if (item != null && item.getParentPopup() != null)
                item.getParentPopup().hide();
        });

        HBox row = new HBox(title, spacer, out, reset, in, fullScreen);
        // A gap before full screen, wider than the 4px between the zoom controls. Those three are one
        // control — a value with a decrement and an increment — and full screen is a different action
        // that happens to live on the same row: it is the one button here that closes the menu. Set
        // as a layout margin rather than in CSS because JavaFX has no -fx-margin; spacing between
        // children belongs to the parent.
        HBox.setMargin(fullScreen, new Insets(0, 0, 0, FULL_SCREEN_GAP));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("zoom-row");

        item = new CustomMenuItem(row);
        // Without this the menu closes on the first press and the next step needs the menu reopened.
        item.setHideOnClick(false);
        refresh();
    }

    private Button button(String text, String tip, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("zoom-button");
        button.setFocusTraversable(false);
        Tooltip tooltip = new Tooltip(tip);
        tooltip.setShowDelay(Duration.millis(400));
        button.setTooltip(tooltip);
        button.setOnAction(e -> {
            action.run();
            // The menu is still open and still showing the old number.
            refresh();
        });
        return button;
    }

    /**
     * Re-reads the level.
     *
     * <p>Pulled rather than bound: the zoom is a settings value shared by every window and both
     * menus, and a binding per row would be four listeners kept alive for a label nobody is looking
     * at. Called when the menu opens, and after each press because the menu stays open.
     */
    void refresh() {
        percent.setText(actions.percent() + "%");
    }

    CustomMenuItem item() {
        return item;
    }
}
