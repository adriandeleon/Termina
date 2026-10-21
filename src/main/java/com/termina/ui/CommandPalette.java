package com.termina.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import static com.termina.i18n.Messages.tr;

/**
 * Every command in the window, findable by typing part of its name — Editora's palette, in shape
 * and in behaviour.
 *
 * <p>The card is the kit's: no title, the first row <em>is</em> the query, led by a chip naming the
 * chord that opened it, then eight rows of commands with the matched characters emboldened and the
 * chord on the right, a line describing the highlighted command, and a legend of the keys. The
 * cursor only rests on a command that can run; one that cannot is listed greyed with the reason as
 * its tooltip, because a command that is simply missing reads as the palette not knowing it.
 *
 * <p>An in-scene overlay rather than a window of its own. A second Stage would take OS focus away
 * from the terminal, and getting it back is exactly the thing that has to work: whatever the user
 * does here, the next keystroke belongs to the shell again.
 *
 * <p>The commands are the menu bar's own {@link MenuAction}s. They are not a parallel list that
 * could disagree with the menus — a command reaches the palette by being in a menu, and its
 * shortcut is shown here because the action already carries it.
 */
final class CommandPalette {

    /** Height of one command row, and how many the list shows before it scrolls. */
    static final double ROW_HEIGHT = 36;

    /** Fixed, so the card does not jump as the match count changes under the typing. */
    static final int VISIBLE_ROWS = 8;

    /** What a match through a keyword loses against any match on the label: more than a label can score. */
    static final int KEYWORD_PENALTY = 1_000_000;

    private final StackPane host;
    private final Runnable onHidden;

    private final TextField input = new TextField();
    /** The input row's chip: the chord that opens the palette, so it introduces itself by its key. */
    private final Label prefixChip = new Label();

    private final ListView<MenuAction> list = new ListView<>();
    private final ObservableList<MenuAction> items = FXCollections.observableArrayList();
    /** One-line description of the highlighted command, between the list and the legend. */
    private final Label desc = new Label();
    /** The navigation legend under the list. */
    private final Label hint = new Label();

    private final VBox card = new VBox(6);
    private final Region backdrop = new Region();

    private List<MenuAction> commands = List.of();
    /** The query of the current filter pass, read by the cells to highlight the matched characters. */
    private String currentQuery = "";
    /** Which commands can run right now; the rest are listed greyed and stepped over. */
    private Predicate<MenuAction> enabled = a -> true;
    /** Why a greyed command cannot run, for its tooltip; null or blank installs none. */
    private Function<MenuAction, String> disabledReason = a -> null;

    CommandPalette(StackPane host, Runnable onHidden) {
        this.host = host;
        this.onHidden = onHidden;

        backdrop.getStyleClass().add("palette-backdrop");
        // Dismiss on PRESS and consume it: a click is press+release, so consuming the press means
        // the gesture that closes the palette cannot also reach the terminal behind it.
        backdrop.setOnMousePressed(e -> {
            hide();
            e.consume();
        });

        prefixChip.getStyleClass().add("palette-prefix");
        setOpener(MenuAction.DEFAULT_PALETTE_CHORD);
        input.getStyleClass().add("palette-input");
        input.setPromptText(tr("palette.prompt"));
        input.textProperty().addListener((o, was, now) -> filter(now));
        HBox inputRow = new HBox(9, prefixChip, input);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        inputRow.getStyleClass().add("palette-input-row");
        HBox.setHgrow(input, Priority.ALWAYS);

        list.setItems(items);
        list.getStyleClass().add("palette-list");
        list.setPlaceholder(new Label(tr("palette.empty")));
        // A whole number of rows: a fixed pixel height cut the last row in half.
        list.setFixedCellSize(ROW_HEIGHT);
        list.setPrefHeight(listHeight(0));
        list.insetsProperty().addListener((o, was, in) -> list.setPrefHeight(listHeight(in.getTop() + in.getBottom())));
        list.setCellFactory(view -> new CommandCell());
        // The query field keeps the keyboard; the list is driven from it. A click on a row runs
        // the row (see CommandCell), and must not leave the field without focus for the next key.
        list.setFocusTraversable(false);
        list.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> input.requestFocus());
        list.getSelectionModel().selectedItemProperty().addListener((o, was, selected) -> {
            String d = selected == null ? "" : selected.description();
            desc.setText(d.isEmpty() ? " " : d); // one line tall always, so the card never jitters
        });

        desc.getStyleClass().add("palette-desc");
        desc.setMaxWidth(Double.MAX_VALUE);
        desc.setTextOverrun(OverrunStyle.ELLIPSIS);
        desc.setText(" ");
        hint.getStyleClass().add("palette-hint");
        hint.setText(PaletteKeys.legend(PaletteKeys.hint("run", "↵")));

        card.getStyleClass().add("command-palette");
        card.getChildren().addAll(inputRow, list, desc, hint);
        card.setPrefWidth(620);
        card.setMaxSize(620, Region.USE_PREF_SIZE); // hug the content; don't stretch to fill the overlay
        StackPane.setAlignment(card, Pos.TOP_CENTER);

        // On the card, so it runs before the ListView's own handling of Up/Down and before the
        // TextField swallows anything. Enter and Escape would otherwise never reach us.
        card.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // Emacs caret movement and editing in the query field. Installed after onKey, so the
        // palette's own navigation (C-n, C-p, C-v, C-g …) consumes those chords first.
        TextInputKeys.install(input);
    }

    /** The list height that shows exactly {@link #VISIBLE_ROWS} rows inside {@code insets} of chrome. Pure. */
    static double listHeight(double insets) {
        return VISIBLE_ROWS * ROW_HEIGHT + insets;
    }

    /** Names the chord that opens the palette on the chip; null hides the chip rather than lying. */
    void setOpener(KeyCombination opener) {
        prefixChip.setText(opener == null ? "" : opener.getDisplayText());
        prefixChip.setVisible(opener != null);
        prefixChip.setManaged(opener != null);
    }

    boolean isShowing() {
        return host.getChildren().contains(card);
    }

    /** Shows every command as runnable. */
    void show(List<MenuAction> commands) {
        show(commands, a -> true, a -> null);
    }

    /**
     * Shows the palette over {@code commands}, or hides it if it is already up.
     *
     * @param enabled which commands can run right now; the others are listed greyed and stepped over
     * @param disabledReason why a greyed command cannot run, shown as its tooltip; null for no tooltip
     */
    void show(List<MenuAction> commands, Predicate<MenuAction> enabled, Function<MenuAction, String> disabledReason) {
        if (isShowing()) {
            hide();
            return;
        }
        this.commands = List.copyOf(commands);
        this.enabled = enabled == null ? a -> true : enabled;
        this.disabledReason = disabledReason == null ? a -> null : disabledReason;
        input.clear();
        filter("");
        host.getChildren().addAll(backdrop, card);
        input.requestFocus();
    }

    void hide() {
        if (!isShowing()) return;
        host.getChildren().removeAll(backdrop, card);
        onHidden.run();
    }

    private boolean isEnabled(MenuAction action) {
        return enabled.test(action);
    }

    private void onKey(KeyEvent e) {
        PaletteKeys.Action action = PaletteKeys.action(e, true);
        switch (action) {
            case CANCEL -> hide();
            case ACCEPT -> runSelected();
            default -> {
                // The cursor only ever rests on an enabled command; greyed-out ones are stepped over.
                if (!PaletteKeys.navigate(list, action, this::isEnabled)) return; // typing: reaches the field
            }
        }
        e.consume();
    }

    private void runSelected() {
        MenuAction action = list.getSelectionModel().getSelectedItem();
        if (action == null || !isEnabled(action)) return; // a greyed command is not actionable
        // Hidden first: the command may open a window or a dialog, and the palette must not still
        // be sitting over it — or, worse, take the focus back afterwards.
        hide();
        action.action().run();
    }

    private void filter(String query) {
        currentQuery = query == null ? "" : query.trim();
        items.setAll(orderedMatches(commands, currentQuery));
        selectFirstEnabled();
    }

    /**
     * The command ordering. An empty query preserves menu order, which is an order the user has
     * already learned; a real query uses the fuzzy score, then the shorter and then the alphabetical
     * label, which keeps the order stable across keystrokes instead of letting equal-scoring rows
     * shuffle under the cursor.
     */
    static List<MenuAction> orderedMatches(Collection<MenuAction> commands, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return new ArrayList<>(commands);
        record Scored(MenuAction command, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (MenuAction command : commands) {
            int score = FuzzyMatch.scoreOf(command.label(), q);
            if (score == FuzzyMatch.NO_SCORE) score = keywordScore(command, q);
            if (score != FuzzyMatch.NO_SCORE) scored.add(new Scored(command, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score)
                .reversed()
                .thenComparingInt((Scored s) -> s.command().label().length())
                .thenComparing(s -> s.command().label(), String.CASE_INSENSITIVE_ORDER));
        List<MenuAction> matches = new ArrayList<>(scored.size());
        for (Scored s : scored) matches.add(s.command());
        return matches;
    }

    /**
     * The score of {@code command} for a query that did not match its label, through its
     * {@link MenuAction#keywords()}: the query has to begin with (the start of) a keyword, and is
     * then matched against "keyword label" — "file new" finds "New Tab" through the File menu.
     * Always below a label match. The haystack is only built for a command whose keyword the query
     * starts with, so a keystroke does not allocate a string per command.
     */
    static int keywordScore(MenuAction command, String query) {
        String keywords = command.keywords();
        if (keywords.isEmpty()) return FuzzyMatch.NO_SCORE;
        int token = query.indexOf(' ');
        int head = Math.min(token < 0 ? query.length() : token, keywords.length());
        if (head == 0 || !keywords.regionMatches(true, 0, query, 0, head)) return FuzzyMatch.NO_SCORE;
        int score = FuzzyMatch.scoreOf(keywords + " " + command.label(), query);
        return score == FuzzyMatch.NO_SCORE ? score : score - KEYWORD_PENALTY;
    }

    /** Selects the first <em>enabled</em> result; the cursor never rests on a greyed command. */
    private void selectFirstEnabled() {
        for (int i = 0; i < items.size(); i++) {
            if (isEnabled(items.get(i))) {
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                return;
            }
        }
        list.getSelectionModel().clearSelection(); // every match is disabled
    }

    // ---------------------------------------------------------------- capture hooks

    /** The visible result labels, so a capture run can assert on the ranking rather than a picture. */
    List<String> visibleCommands() {
        return items.stream().map(MenuAction::label).toList();
    }

    /** The highlighted row, with the matched characters bracketed, or "-" when nothing is selected. */
    String selectedForCapture() {
        MenuAction selected = list.getSelectionModel().getSelectedItem();
        return selected == null ? "-" : MatchText.describe(selected.label(), currentQuery);
    }

    /** Types into the query field as the user would, running the same filter. */
    void setQueryForCapture(String text) {
        input.setText(text);
        // A TextField given the focus by code selects its whole content; typing replaces that, but
        // a set text keeps it, and the capture then photographs a query that looks highlighted.
        input.positionCaret(text.length());
    }

    /**
     * Fires a chord at the palette by its {@link KeyChord} token, exactly as a key press would arrive —
     * through the card's filter, then the field's. Returns false for a token that names no key.
     */
    boolean fireKeyForCapture(String token) {
        KeyCombination combination = KeyChord.toCombination(token);
        if (!(combination instanceof KeyCodeCombination chord)) return false;
        boolean shift = chord.getShift() == KeyCombination.ModifierValue.DOWN;
        boolean control = chord.getControl() == KeyCombination.ModifierValue.DOWN;
        boolean alt = chord.getAlt() == KeyCombination.ModifierValue.DOWN;
        boolean meta = chord.getMeta() == KeyCombination.ModifierValue.DOWN;
        Event.fireEvent(input, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", chord.getCode(), shift, control, alt, meta));
        return true;
    }

    /** A row: the command on the left with the match emboldened, its chord right-aligned. */
    private final class CommandCell extends ListCell<MenuAction> {
        private final TextFlow title = new TextFlow();
        private final Label chord = new Label();
        private final HBox layout = new HBox(10, title, spacer(), chord);

        private static Region spacer() {
            Region region = new Region();
            HBox.setHgrow(region, Priority.ALWAYS);
            return region;
        }

        CommandCell() {
            chord.getStyleClass().add("keybinding");
            layout.setAlignment(Pos.CENTER_LEFT);
            // Click an enabled command to run it; a greyed one is inert.
            setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY || isEmpty() || getItem() == null) return;
                if (!isEnabled(getItem())) return;
                getListView().getSelectionModel().select(getItem());
                runSelected();
            });
        }

        @Override
        protected void updateItem(MenuAction item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            title.getChildren().setAll(MatchText.runs(item.label(), currentQuery));
            chord.setText(item.accelerator() == null ? "" : item.accelerator().getDisplayText());
            layout.getStyleClass().remove("palette-disabled");
            setTooltip(null); // cells are recycled: never leave a previous row's explanation behind
            if (!isEnabled(item)) {
                layout.getStyleClass().add("palette-disabled");
                // Say why it is greyed: a grey row with no explanation reads as a bug, not a state.
                String why = disabledReason.apply(item);
                if (why != null && !why.isBlank()) {
                    Tooltip tip = new Tooltip(why);
                    tip.setWrapText(true);
                    tip.setMaxWidth(380);
                    tip.getStyleClass().add("palette-disabled-tooltip");
                    setTooltip(tip);
                }
            }
            setGraphic(layout);
        }
    }
}
