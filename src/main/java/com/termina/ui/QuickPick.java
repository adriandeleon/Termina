package com.termina.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/**
 * The one picker for every list choice (QOPEN-01, Pauta 6.0.1): a colour theme, a font, a cursor
 * shape, the profile a new tab starts. A port of Editora's {@code ui.QuickOpen}, and the Command
 * Palette's card in the window's {@link OverlayHost}: a title naming the command, a field to type
 * in, the list, and the palette's own legend of keys.
 *
 * <ul>
 *   <li>The items are read fresh on every showing and kept for it, so typing filters a list that
 *       does not move (QOPEN-03); {@link #reload} reads them again and keeps what was typed.
 *   <li>With nothing typed the list is in the supplier's order, the value in force selected
 *       (QOPEN-04). Typing ranks by fuzzy score, ties in the supplier's order (QOPEN-05).
 *   <li>The keys are the palette's, from the same table (QOPEN-06, QOPEN-07): the chords bound to
 *       the four {@code nav} commands, the arrows, Page Up and Down, Enter, Escape.
 *   <li>A picker may preview the row the selection moves to, and then has an undo for a
 *       dismissal without a choice (QOPEN-08).
 * </ul>
 *
 * @param <T> what is chosen
 */
final class QuickPick<T> {

    /** How many rows the list shows before it scrolls; fewer rows make a shorter card. */
    static final int MAX_VISIBLE = 10;

    private final OverlayHost host;
    private final Supplier<List<T>> itemsSupplier;
    private final Function<T, String> label;
    private final Function<T, String> detail;
    private final Consumer<T> onChoose;
    private final Supplier<Map<String, PaletteKeys.Action>> moves;

    private final Label header = new Label();
    private final TextField input = new TextField();
    private final ListView<T> list = new ListView<>();
    private final Label hint = new Label();
    private final VBox card = new VBox(6);

    private final ObservableList<T> items = FXCollections.observableArrayList();
    private List<T> all = List.of();

    /** The query of the current filter pass, read by the cells to embolden the matched characters. */
    private String currentQuery = "";

    /** Says which item is the value in force, selected when the picker opens. */
    private @Nullable Predicate<T> current;

    private @Nullable Consumer<T> onPreview;
    private @Nullable Runnable onCancelled;

    /** Whether this showing ended in a choice: a dismissal undoes the preview, a choice does not. */
    private boolean chosen;

    private boolean showing;

    /**
     * @param title names the command, over the field
     * @param prompt what the empty field says
     * @param itemsSupplier the list, asked on every showing
     * @param label a row's text, which typing is matched against
     * @param detail what a row says at its right edge, or "" for nothing
     * @param onChoose runs with the chosen item, after the card has gone
     * @param moves the chords in force that move the selection ({@link PaletteKeys#moves})
     */
    QuickPick(
            OverlayHost host,
            String title,
            String prompt,
            Supplier<List<T>> itemsSupplier,
            Function<T, String> label,
            Function<T, String> detail,
            Consumer<T> onChoose,
            Supplier<Map<String, PaletteKeys.Action>> moves) {
        this.host = host;
        this.itemsSupplier = itemsSupplier;
        this.label = label;
        this.detail = detail;
        this.onChoose = onChoose;
        this.moves = moves;

        header.setText(title);
        header.getStyleClass().add("palette-title");
        input.getStyleClass().add("palette-input");
        input.setPromptText(prompt);
        // Named for a screen reader, which reads no prompt (ACC-01, Pauta 6.0.1).
        input.setAccessibleText(prompt);
        input.textProperty().addListener((o, was, now) -> filter(now));
        HBox inputRow = new HBox(9, input);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        inputRow.getStyleClass().add("palette-input-row");
        HBox.setHgrow(input, Priority.ALWAYS);

        list.setItems(items);
        list.getStyleClass().add("palette-list");
        list.setAccessibleText(title);
        setPlaceholder(tr("picker.noMatch"));
        // A whole number of rows, as many as there are up to MAX_VISIBLE, at the UI zoom
        // (WIN-12): the list works its height out itself.
        UiZoom.own(list);
        UiZoom.follow(list, this::resizeList);
        list.insetsProperty().addListener((o, was, now) -> resizeList());
        items.addListener((javafx.collections.ListChangeListener<T>) change -> resizeList());
        list.setCellFactory(view -> new ItemCell());
        // The field keeps the keyboard and the list is driven from it, as in the palette.
        list.setFocusTraversable(false);
        list.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> input.requestFocus());
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            // Only a row the user moved to: nothing is previewed for the row the picker opens on.
            if (showing && onPreview != null && now != null) onPreview.accept(now);
        });

        hint.getStyleClass().add("palette-hint");
        card.getStyleClass().addAll("command-palette", "quick-pick");
        card.getChildren().addAll(header, inputRow, list, hint);
        card.setPrefWidth(620);
        card.setMaxSize(620, Region.USE_PREF_SIZE);
        StackPane.setAlignment(card, Pos.TOP_CENTER);

        // On the card, so it runs before the ListView's own handling of Up and Down and before the
        // field swallows anything.
        card.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        TextInputKeys.install(input);
    }

    /** Says which item is the value in force, so the picker opens on it and not on the first row. */
    void setCurrent(Predicate<T> current) {
        this.current = current;
    }

    /** What the list says while it has no rows: never a blank card (QOPEN-10). */
    void setPlaceholder(String text) {
        Label empty = new Label(text);
        empty.getStyleClass().add("palette-hint");
        empty.setWrapText(true);
        list.setPlaceholder(empty);
    }

    /**
     * Makes the picker preview the highlighted row: {@code onPreview} runs as the selection moves,
     * and {@code onCancelled} runs if the picker is dismissed without a choice. A preview is only
     * worth having with its undo: arrowing through a list and pressing Escape must leave things as
     * they were.
     */
    void setPreview(Consumer<T> onPreview, Runnable onCancelled) {
        this.onPreview = onPreview;
        this.onCancelled = onCancelled;
    }

    /** Shows the picker over the window, in place of any card that is up. */
    void show() {
        all = List.copyOf(itemsSupplier.get());
        hint.setText(PaletteKeys.legend(moves.get(), PaletteKeys.hint("select", "↵")));
        chosen = false;
        showing = false; // the first selection below is the picker's own, and previews nothing
        input.clear();
        filter("");
        host.show(card, new OverlayHost.Scrim("palette-backdrop", tr("picker.close")), () -> {
            showing = false;
            Runnable undo = onCancelled;
            if (!chosen && undo != null) undo.run();
        });
        showing = true;
        input.requestFocus();
    }

    /**
     * Reads the items again while the picker is up and keeps what was typed: for a source that
     * answered after {@link #show}, such as the bundled fonts still being registered.
     */
    void reload() {
        if (!showing) return;
        T selected = list.getSelectionModel().getSelectedItem();
        all = List.copyOf(itemsSupplier.get());
        showing = false; // re-reading is not the user moving: no preview for it
        filter(input.getText());
        int at = selected == null ? -1 : items.indexOf(selected);
        if (at >= 0) {
            list.getSelectionModel().select(at);
            list.scrollTo(at);
        }
        showing = true;
    }

    void hide() {
        host.hide(card);
    }

    boolean isShowing() {
        return showing;
    }

    private void resizeList() {
        double row = UiZoom.px(CommandPalette.ROW_HEIGHT);
        list.setFixedCellSize(row);
        double height = listHeight(
                items.size(), row, list.getInsets().getTop() + list.getInsets().getBottom());
        list.setMinHeight(height);
        list.setPrefHeight(height);
        list.setMaxHeight(height);
    }

    /**
     * The list's height for {@code count} rows of {@code row} pixels inside {@code insets} of
     * chrome: as many rows as there are, one at least so the placeholder has somewhere to be, and
     * {@link #MAX_VISIBLE} at most. Pure.
     */
    static double listHeight(int count, double row, double insets) {
        return Math.max(1, Math.min(count, MAX_VISIBLE)) * row + insets;
    }

    private void onKey(KeyEvent e) {
        PaletteKeys.Action action = PaletteNav.action(e, true, moves.get());
        switch (action) {
            case CANCEL -> hide();
            case ACCEPT -> chooseSelected();
            default -> {
                if (!PaletteNav.navigate(list, action, item -> true)) return; // typing: reaches the field
            }
        }
        e.consume();
    }

    private void chooseSelected() {
        T item = list.getSelectionModel().getSelectedItem();
        if (item == null) return;
        // Hidden first, so the keyboard is already back where it belongs when the choice runs;
        // and marked chosen before that, since hiding is what runs the undo.
        chosen = true;
        hide();
        onChoose.accept(item);
    }

    private void filter(@Nullable String query) {
        currentQuery = query == null ? "" : query.trim();
        items.setAll(ordered(all, currentQuery, label));
        if (items.isEmpty()) return;
        int start = startIndex(items, currentQuery, current);
        list.getSelectionModel().select(start);
        list.scrollTo(start);
    }

    /**
     * The rows for a query. Nothing typed: the supplier's own order, which is the meaningful one
     * (QOPEN-04). Otherwise by fuzzy score, and the supplier's index is the last tiebreak, so rows
     * that score the same keep their order and do not shuffle under the cursor (QOPEN-05). Pure.
     */
    static <T> List<T> ordered(List<T> all, String query, Function<T, String> label) {
        String q = query.trim();
        if (q.isEmpty()) return new ArrayList<>(all);
        record Scored<U>(U item, int score, int index) {}
        List<Scored<T>> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            int score = FuzzyMatch.scoreOf(label.apply(all.get(i)), q);
            if (score != FuzzyMatch.NO_SCORE) scored.add(new Scored<>(all.get(i), score, i));
        }
        scored.sort(
                Comparator.comparingInt((Scored<T> s) -> s.score()).reversed().thenComparingInt(Scored::index));
        List<T> matches = new ArrayList<>(scored.size());
        for (Scored<T> s : scored) matches.add(s.item());
        return matches;
    }

    /**
     * The row a filter pass selects: the value in force while nothing is typed and the list has
     * it, else the first. Pure.
     */
    static <T> int startIndex(List<T> rows, String query, @Nullable Predicate<T> current) {
        if (!query.isEmpty() || current == null) return 0;
        for (int i = 0; i < rows.size(); i++) {
            if (current.test(rows.get(i))) return i;
        }
        return 0;
    }

    // ---- for tests and capture runs ----

    /** The rows showing, by label. */
    List<String> rowsForTest() {
        return items.stream().map(label).toList();
    }

    /** The highlighted row's label, or "" for none. */
    String selectedForTest() {
        T item = list.getSelectionModel().getSelectedItem();
        return item == null ? "" : label.apply(item);
    }

    /** Types into the field as the user would. */
    void setQueryForTest(String text) {
        input.setText(text);
    }

    String queryForTest() {
        return input.getText();
    }

    /** Fires a key, given as a {@link KeyChord} token, at the field; false for a token no key has. */
    boolean fireKeyForTest(String token) {
        if (!(KeyChord.toCombination(token) instanceof javafx.scene.input.KeyCodeCombination chord)) return false;
        boolean shift = chord.getShift() == javafx.scene.input.KeyCombination.ModifierValue.DOWN;
        boolean control = chord.getControl() == javafx.scene.input.KeyCombination.ModifierValue.DOWN;
        boolean alt = chord.getAlt() == javafx.scene.input.KeyCombination.ModifierValue.DOWN;
        boolean meta = chord.getMeta() == javafx.scene.input.KeyCombination.ModifierValue.DOWN;
        // A list asked to scroll before it has a size scrolls to the wrong place.
        card.applyCss();
        card.layout();
        javafx.event.Event.fireEvent(
                input, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", chord.getCode(), shift, control, alt, meta));
        return true;
    }

    String titleForTest() {
        return header.getText();
    }

    String legendForTest() {
        return hint.getText();
    }

    /** What the empty list says. */
    String placeholderForTest() {
        return list.getPlaceholder() instanceof Label says ? says.getText() : "";
    }

    /** A row: its label on the left with the match emboldened, its detail at the right. */
    private final class ItemCell extends ListCell<T> {
        private final TextFlow title = new TextFlow();
        private final Label sub = new Label();
        private final HBox layout = new HBox(10, title, spacer(), sub);

        private static Region spacer() {
            Region region = new Region();
            HBox.setHgrow(region, Priority.ALWAYS);
            return region;
        }

        ItemCell() {
            // Never wider than the list: a long detail gives way with an ellipsis, and the label
            // keeps its width.
            setPrefWidth(0);
            title.setMinWidth(Region.USE_PREF_SIZE);
            sub.getStyleClass().add("keybinding");
            sub.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
            layout.setAlignment(Pos.CENTER_LEFT);
            setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY || isEmpty() || getItem() == null) return;
                getListView().getSelectionModel().select(getItem());
                chooseSelected();
            });
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            String text = label.apply(item);
            String more = detail.apply(item);
            title.getChildren().setAll(MatchText.runs(text, currentQuery));
            sub.setText(more);
            // What a screen reader says for the row (ACC-08): the keyboard stays in the field.
            setAccessibleText(more.isEmpty() ? text : text + ", " + more);
            setGraphic(layout);
        }
    }
}
