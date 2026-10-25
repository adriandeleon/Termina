package com.termina.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import static com.termina.i18n.Messages.tr;

/**
 * The find bar: a query field, the count, a regex switch and the three buttons, floating at the top
 * right of the terminal it searches.
 *
 * <p>A child of the {@link TerminalView} rather than of the window, so each pane of a split has its
 * own and a search never wonders which terminal it is about. It only reports what was typed and
 * pressed; the view owns the matches.
 *
 * <p>Enter steps <em>up</em>, to older output, and Shift+Enter down. A terminal is searched for
 * something recent, so the search starts at the bottom and the common move is backwards through
 * time — iTerm2's convention. The arrow keys do the same, for anyone who expects them to.
 */
final class SearchBar extends HBox {

    /** What the view is told. */
    interface Listener {
        void queryChanged(String query, boolean regex);

        /** @param older towards the top of the scrollback */
        void step(boolean older);

        void closed();
    }

    private final TextField field = new TextField();
    private final Label count = new Label();
    private final ToggleButton regex = new ToggleButton(".*");
    private final Listener listener;

    SearchBar(Listener listener) {
        this.listener = listener;
        getStyleClass().add("search-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(4);
        // Out of the traversal order, like the rest of the chrome: Tab belongs to the shell, and a
        // scene looking for something to focus must not settle on a button in here.
        setFocusTraversable(false);

        field.getStyleClass().add("search-field");
        field.setPromptText(tr("search.prompt"));
        field.textProperty().addListener((o, was, now) -> fireQuery());
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        HBox.setHgrow(field, Priority.ALWAYS);

        count.getStyleClass().add("search-count");
        count.setMinWidth(COUNT_MIN_WIDTH);

        regex.getStyleClass().add("search-toggle");
        regex.setFocusTraversable(false);
        regex.setTooltip(new Tooltip(tr("search.regex")));
        regex.selectedProperty().addListener((o, was, now) -> fireQuery());

        Button older = button(MenuIcons.arrowUp(), tr("search.older"), () -> listener.step(true));
        Button newer = button(MenuIcons.arrowDown(), tr("search.newer"), () -> listener.step(false));
        Button close = button(MenuIcons.close(), tr("search.close"), this::close);

        getChildren().addAll(MenuIcons.search(), field, count, regex, older, newer, close);
        // Pinned to its own size, or the view lays it out at whatever width it is given.
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
    }

    /** Keeps the buttons from shuffling sideways as the count grows a digit. */
    private static final double COUNT_MIN_WIDTH = 64;

    private static Button button(javafx.scene.Node icon, String tip, Runnable action) {
        Button button = new Button(null, icon);
        button.getStyleClass().add("search-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(e -> action.run());
        return button;
    }

    private void onKey(KeyEvent e) {
        KeyCode code = e.getCode();
        if (code == KeyCode.ESCAPE) {
            close();
        } else if (code == KeyCode.ENTER) {
            listener.step(!e.isShiftDown());
        } else if (code == KeyCode.UP) {
            listener.step(true);
        } else if (code == KeyCode.DOWN) {
            listener.step(false);
        } else {
            return;
        }
        e.consume();
    }

    private void fireQuery() {
        listener.queryChanged(field.getText(), regex.isSelected());
    }

    private void close() {
        listener.closed();
    }

    /** Puts the caret in the field with its text selected, so typing replaces the last query. */
    void focusField() {
        field.requestFocus();
        field.selectAll();
    }

    String query() {
        return field.getText();
    }

    boolean isRegex() {
        return regex.isSelected();
    }

    boolean fieldIsFocused() {
        return field.isFocused();
    }

    /**
     * Shows where the search stands.
     *
     * @param valid false when the field holds a regular expression that does not compile — the
     *     field says so, rather than the bar reporting "no matches" for a pattern it never ran
     */
    void showCount(int current, int total, boolean valid) {
        field.pseudoClassStateChanged(INVALID, !valid);
        if (!valid) {
            count.setText(tr("search.invalid"));
        } else if (field.getText().isEmpty()) {
            count.setText("");
        } else if (total == 0) {
            count.setText(tr("search.none"));
        } else if (current < 0) {
            count.setText(ScrollbackSearch.total(total));
        } else {
            count.setText(tr("search.count", current + 1, ScrollbackSearch.total(total)));
        }
    }

    /** Sets the query and the switch: a selection being searched for, or a capture run typing. */
    void setQuery(String query, boolean regexOn) {
        regex.setSelected(regexOn);
        field.setText(query);
        field.positionCaret(query.length());
    }

    String countText() {
        return count.getText();
    }

    private static final javafx.css.PseudoClass INVALID = javafx.css.PseudoClass.getPseudoClass("invalid");
}
