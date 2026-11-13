package com.termina.ui;

import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import org.jspecify.annotations.Nullable;

/**
 * The menu bar's rows, which carry no {@code KeyCombination} accelerator (MENU-02, Pauta 2.0.2).
 *
 * <p>An accelerator is the platform's to act on: JavaFX copies it into the scene and runs it for any
 * press that bubbles up unconsumed, and the macOS screen menu bar claims it before JavaFX sees the
 * key at all. Termina's chords are the window's key filter's, so a row only <em>shows</em> its chord,
 * and draws it itself, one of two ways:
 *
 * <ul>
 *   <li><b>In-window bar</b>: a {@link CustomMenuItem} laying out an icon column, the title, a spacer
 *       and the chord. Every title in a menu is sized to the widest ({@link #alignChords}), so the
 *       chords start in one column, as a native accelerator's would.
 *   <li><b>macOS screen menu bar</b>: plain text, the chord appended to the title, and no icon. AppKit
 *       draws those menus and cannot draw a JavaFX node — one {@code CustomMenuItem} anywhere costs the
 *       whole screen menu bar (see {@code TerminalWindow.menusFitASystemMenuBar}) — and text without
 *       graphics is the platform's convention there anyway.
 * </ul>
 *
 * <p>Both keep their title and chord in the item's properties, so either can change without the other
 * being asked for again: a relabelled command keeps its chord, a rebound one its title.
 */
final class MenuRows {

    /** The icon column's width: a glyph's, so rows with and without one start their titles together. */
    static final double ICON_COLUMN = 22;

    /** The size of a title at 100%: the menus' 14 px (MENU-16), which the UI zoom multiplies. */
    static final double TITLE_SIZE = 14;

    /** A title's font now: Inter at {@link #TITLE_SIZE} times the UI zoom, as the stylesheet draws it. */
    private static Font titleFont() {
        return Font.font("Inter", UiZoom.px(TITLE_SIZE));
    }

    private static final String TITLE = "termina.menuRow.title";
    private static final String CHORD = "termina.menuRow.chord";
    private static final String ICON = "termina.menuRow.icon";

    /** Between the title and the chord on the screen menu bar, which has no column to put it in. */
    static final String SYSTEM_GAP = "   ";

    private MenuRows() {}

    /**
     * A command row.
     *
     * @param icon its glyph in-window, or null for an empty column; never drawn on the system bar
     * @param system whether the row is for the macOS screen menu bar
     */
    static MenuItem row(String title, @Nullable Node icon, boolean system) {
        if (system) {
            MenuItem item = new MenuItem(title);
            item.getProperties().put(TITLE, title);
            return item;
        }
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("menu-item-title");
        Label chordLabel = new Label();
        chordLabel.getStyleClass().add("menu-item-chord");
        HBox content = content(icon, titleLabel, chordLabel);
        CustomMenuItem item = new CustomMenuItem(content);
        item.setHideOnClick(true);
        item.getProperties().put(ICON, content.getChildren().getFirst());
        item.getProperties().put(TITLE, titleLabel);
        item.getProperties().put(CHORD, chordLabel);
        return item;
    }

    /**
     * A submenu row: in-window, its graphic is a row's content with no chord, so its title lines up
     * with the commands' around it; on the system bar, plain text.
     */
    static Menu submenu(String title, @Nullable Node icon, boolean system) {
        if (system) return new Menu(title);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("menu-item-title");
        Label chordLabel = new Label();
        chordLabel.getStyleClass().add("menu-item-chord");
        HBox content = content(icon, titleLabel, chordLabel);
        Menu menu = new Menu("", content);
        menu.getProperties().put(ICON, content.getChildren().getFirst());
        menu.getProperties().put(TITLE, titleLabel);
        menu.getProperties().put(CHORD, chordLabel);
        return menu;
    }

    /**
     * Replaces the glyph in a row's icon column, for a row whose command names a different thing
     * from one opening to the next (a profile). Nothing to do on the system bar, which draws none.
     */
    static void setIcon(MenuItem item, @Nullable Node icon) {
        if (!(item.getProperties().get(ICON) instanceof StackPane column)) return;
        if (icon == null) column.getChildren().clear();
        else column.getChildren().setAll(icon);
    }

    /** The glyph in a row's icon column, or null for an empty column or a system-bar row. */
    static @Nullable Node iconOf(MenuItem item) {
        if (!(item.getProperties().get(ICON) instanceof StackPane column)) return null;
        return column.getChildren().isEmpty() ? null : column.getChildren().getFirst();
    }

    /** Sets a row's title, keeping its chord. */
    static void setTitle(MenuItem item, String title) {
        if (item.getProperties().get(TITLE) instanceof Label label) {
            label.setText(title);
        } else {
            item.getProperties().put(TITLE, title);
            item.setText(systemText(title, chordText(item)));
        }
    }

    /** Sets a row's chord, or clears it; keeps its title. */
    static void setChord(MenuItem item, @Nullable KeyCombination chord) {
        String text = chord == null ? "" : chord.getDisplayText();
        if (item.getProperties().get(CHORD) instanceof Label label) {
            label.setText(text);
        } else {
            item.getProperties().put(CHORD, text);
            item.setText(systemText(titleOf(item), text));
        }
    }

    /** A row's title, without its chord, however it is drawn. */
    static String titleOf(MenuItem item) {
        Object title = item.getProperties().get(TITLE);
        if (title instanceof Label label) return label.getText();
        if (title instanceof String text) return text;
        return item.getText() == null ? "" : item.getText();
    }

    /** The chord a row shows, or an empty string. */
    static String chordText(MenuItem item) {
        Object chord = item.getProperties().get(CHORD);
        if (chord instanceof Label label) return label.getText() == null ? "" : label.getText();
        if (chord instanceof String text) return text;
        return "";
    }

    /** Whether {@code item} is a row made here, rather than a separator or the zoom row. */
    static boolean isRow(MenuItem item) {
        return item.getProperties().containsKey(TITLE);
    }

    /** The system bar's text for a row: the title, and the chord after a gap when there is one. */
    static String systemText(String title, String chord) {
        return chord.isEmpty() ? title : title + SYSTEM_GAP + chord;
    }

    /**
     * Gives every in-window title in {@code items} the width of the widest, so the menu's chords start
     * in one column. Measured rather than laid out: a menu's rows are laid out at their preferred width,
     * not stretched to the popup's, and the popup is not on screen yet when a menu is about to open.
     */
    static void alignChords(List<MenuItem> items) {
        double widest = 0;
        Text measure = new Text();
        measure.setFont(titleFont());
        for (MenuItem item : items) {
            if (item.getProperties().get(TITLE) instanceof Label label) {
                measure.setText(label.getText());
                widest = Math.max(widest, measure.getLayoutBounds().getWidth());
            }
        }
        double width = Math.ceil(widest) + 2;
        for (MenuItem item : items) {
            if (item.getProperties().get(TITLE) instanceof Label label) {
                label.setMinWidth(width);
                label.setPrefWidth(width);
            }
        }
    }

    /** Icon, title, spacer, chord: one row's layout. */
    private static HBox content(@Nullable Node icon, Label title, Label chord) {
        StackPane iconColumn = new StackPane();
        // At least a glyph's width, and wider only for a graphic that needs it (a profile's tick and
        // icon): every row of one menu has the same kind, so the titles still start together.
        UiZoom.bind(iconColumn.minWidthProperty(), ICON_COLUMN);
        iconColumn.setAlignment(Pos.CENTER_LEFT);
        iconColumn.getStyleClass().add("menu-item-icon");
        if (icon != null) iconColumn.getChildren().add(icon);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(iconColumn, title, spacer, chord);
        // One small gap after the icon and a wide one before the chord column, which one spacing
        // value cannot say.
        UiZoom.hboxMargin(title, 0, 0, 0, 8);
        UiZoom.hboxMargin(chord, 0, 0, 0, 18);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(Double.MAX_VALUE);
        // A row drawn from its own nodes reads as nothing unless it says what it is: JavaFX names
        // such a row from its content's accessible text (ACC-01, Pauta 6.0.1). Bound, so a row
        // that is relabelled, Pin Tab to Unpin Tab, is read as what it now says.
        box.accessibleTextProperty().bind(title.textProperty());
        return box;
    }
}
