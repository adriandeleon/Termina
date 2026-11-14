package com.termina.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import static com.termina.i18n.Messages.tr;

/**
 * The Message Log (DIAG-01, MLOG-02..08, Pauta 6.0.1): this session's messages, newest first, with
 * the time each was said. Opened from the status bar's echo area or Help → Message Log.
 *
 * <p>A card in the window's overlay host, over the terminal and just above the status bar, and
 * never a window of its own (MLOG-08): the log answers "what just happened?" beside the line that
 * said it. {@value #WIDTH} px wide with a list {@value #LIST_HEIGHT} px high, at the default
 * zoom, and less of either in a window too small for that.
 *
 * <p>Escape closes it, and so does a press outside it or the chord that opened it; no fixed
 * Ctrl+G, since Termina's key table binds no cancel command for one to follow (MLOG-03). Ctrl+C
 * (Cmd+C) copies the selected lines, and so do the chords bound to Copy and to the log's own Copy
 * command (MLOG-04). Opening it counts as reading the errors in it.
 *
 * <p>A filter field over the list narrows it as it is typed (FIND-03, Pauta 6.0.1); Escape in the
 * field empties it, and from an empty one goes back to the list. A row's right-click menu has
 * Copy and Clear (MENU-05), which are the commands {@code messageLog.copy} and
 * {@code messageLog.clear} and what the two buttons do (KBD-01).
 */
final class MessageLogCard {

    /** The card's width at the default zoom (MLOG-08). */
    static final double WIDTH = 773;

    /** The list's height at the default zoom (MLOG-08). */
    static final double LIST_HEIGHT = 336;

    /** The gap between the card and the window's edge, and between it and the status bar. */
    private static final double GAP = 8;

    private final OverlayHost host;
    private final java.util.function.DoubleSupplier statusBarHeight;
    private final VBox card = new VBox(10);

    private final MessageLog log;
    private final ListView<MessageLog.Entry> list = new ListView<>();
    private final javafx.scene.control.TextField filter = new javafx.scene.control.TextField();
    private final Label noEntries = new Label(tr("messagelog.empty"));

    /** Where the menu's rows read their commands' chords; none until the manager says. */
    private java.util.function.Function<String, javafx.scene.input.@org.jspecify.annotations.Nullable KeyCombination>
            chords = id -> null;

    void setChords(
            java.util.function.Function<String, javafx.scene.input.@org.jspecify.annotations.Nullable KeyCombination>
                    chords) {
        this.chords = chords;
    }

    /** The chords in force that move a row and a page; the key maps' own until the window says. */
    private java.util.function.Supplier<java.util.Map<String, PaletteKeys.Action>> moves =
            () -> PaletteKeys.moves(id -> KeyChord.of(Keymap.lists().get(id), Keybindings.MAC));

    void setMoves(java.util.function.Supplier<java.util.Map<String, PaletteKeys.Action>> moves) {
        this.moves = moves;
    }

    private final Runnable refresh = this::refresh;

    /**
     * @param host the window's overlay host, which shows the card and gives the keyboard back
     * @param statusBarHeight how much room the status bar takes at the bottom now; 0 when hidden
     */
    MessageLogCard(MessageLog log, OverlayHost host, java.util.function.DoubleSupplier statusBarHeight) {
        this.log = log;
        this.host = host;
        this.statusBarHeight = statusBarHeight;
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPlaceholder(noEntries);
        list.setAccessibleText(tr("messagelog.title"));
        filter.setPromptText(tr("messagelog.filter"));
        filter.setAccessibleText(tr("messagelog.filter"));
        filter.textProperty().addListener((o, was, now) -> refresh());
        filter.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.ESCAPE) return;
            // One layer at a time: a filter with something in it empties, an empty one gives the
            // list back; the next Escape, in the list, closes the log.
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
            if (copies(e, chords.apply("edit.copy"), chords.apply("messageLog.copy"))) {
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
        clearRow.getStyleClass().add("danger");
        clearRow.setOnAction(e -> clear());
        MenuChords.command(copyRow, "messageLog.copy");
        MenuChords.command(clearRow, "messageLog.clear");
        javafx.scene.control.ContextMenu rowMenu = new javafx.scene.control.ContextMenu(copyRow, clearRow);
        // Each row's chord, from the bindings in force as the menu shows (MENU-15).
        rowMenu.setOnShowing(e -> MenuChords.fill(rowMenu.getItems(), chords));
        list.setContextMenu(OneMenu.installRightClick(rowMenu));
        Button close = new Button(tr("debuglog.close"));
        close.setOnAction(e -> hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        cutNote.getStyleClass().add("settings-row-description");
        HBox buttons = new HBox(8, copy, clear, cutNote, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(tr("messagelog.title"));
        title.getStyleClass().add("message-log-title");
        card.getStyleClass().add("message-log-card");
        card.getChildren().addAll(title, filter, list, buttons);
        list.setMinHeight(0);
        StackPane.setAlignment(card, Pos.BOTTOM_LEFT);

        // On the card, ahead of the list and the field: Escape and the chord that opened the log
        // close it, and Ctrl+F (Cmd+F) goes to the filter, where a find bar's chord would lead.
        card.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeyCombination opener = chords.apply("view.messageLog");
            if (closes(e, filter.isFocused(), opener)) {
                hide();
                e.consume();
            } else if (e.getCode() == KeyCode.F && e.isShortcutDown() && !e.isAltDown()) {
                filter.requestFocus();
                filter.selectAll();
                e.consume();
            } else {
                // The picker's keys (QOPEN-12, Pauta 6.0.1): the chords bound to a row and a
                // page move the list, from the filter too, and the one bound to cancelling
                // closes the log (MLOG-03): read from the bindings, never a fixed letter.
                PaletteKeys.Action action = listAction(KeyChord.of(e), moves.get());
                if (action == PaletteKeys.Action.CANCEL) {
                    hide();
                    e.consume();
                } else if (PaletteNav.navigate(list, action, entry -> true)) {
                    e.consume();
                }
            }
        });
    }

    /**
     * What a key does in the log beyond the list's own keys: the action of a chord the bindings
     * give a row, a page or cancelling, or of the two fixed ones for the ends. Nothing for the arrows,
     * Page Up and Down, Home and End, which the list moves on itself and extends a selection with;
     * nothing for Enter, there being nothing to accept; nothing for Escape, which {@link #closes}
     * has. Pure.
     */
    static PaletteKeys.Action listAction(
            @org.jspecify.annotations.Nullable String token, java.util.Map<String, PaletteKeys.Action> moves) {
        if (token == null) return PaletteKeys.Action.NONE;
        PaletteKeys.Action moved = moves.get(token);
        return moved != null ? moved : PaletteKeys.EMACS.getOrDefault(token, PaletteKeys.Action.NONE);
    }

    /**
     * Whether a key press closes the log: Escape anywhere but in the filter, where it peels the
     * filter's own layers first, and the chord bound to the command that opened it.
     */
    static boolean closes(
            KeyEvent e, boolean inFilter, javafx.scene.input.@org.jspecify.annotations.Nullable KeyCombination opener) {
        if (e.getCode() == KeyCode.ESCAPE) return !inFilter;
        return opener != null && opener.match(e);
    }

    /** The card's width and its list's height at a UI zoom of {@code factor}. Pure. */
    static double[] sizeAt(double factor) {
        return new double[] {WIDTH * factor, LIST_HEIGHT * factor};
    }

    /**
     * Whether a key press copies the selected lines (MLOG-04, Pauta 6.0.1): the platform's copy
     * chord, C with the shortcut modifier, whatever the bindings say, and the chords the bindings
     * in force give the terminal's Copy and the log's own, read from them and not written here.
     */
    static boolean copies(
            KeyEvent e,
            javafx.scene.input.@org.jspecify.annotations.Nullable KeyCombination copy,
            javafx.scene.input.@org.jspecify.annotations.Nullable KeyCombination ownCopy) {
        if (e.getCode() == KeyCode.C && e.isShortcutDown()) return true;
        return (copy != null && copy.match(e)) || (ownCopy != null && ownCopy.match(e));
    }

    /** Shows the log over the window, or closes it when it is already up. */
    void show() {
        if (isShowing()) {
            hide();
            return;
        }
        double[] size = sizeAt(UiZoom.factor().get());
        card.setMaxWidth(size[0]);
        card.setPrefWidth(size[0]);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        list.setPrefHeight(size[1]);
        // Just above the status bar's echo area, which is at the window's bottom left.
        StackPane.setMargin(card, new Insets(GAP, GAP, statusBarHeight.getAsDouble() + GAP, GAP));
        // What is behind the card is not dimmed: the log is a glance at the terminal's side, and
        // the terminal stays readable around it. A press there closes the log (MLOG-03).
        host.show(
                card,
                new OverlayHost.Scrim("message-log-backdrop", tr("messagelog.close")),
                () -> log.removeListener(refresh));
        log.addListener(refresh);
        refresh();
        // A card added in this pulse has no skin yet, and a list without one takes no focus.
        card.applyCss();
        card.layout();
        list.requestFocus();
    }

    /** Closes the log; the host gives the keyboard back to where it was, or to the terminal. */
    void hide() {
        host.hide(card);
    }

    boolean isShowing() {
        return host.isShowing(card);
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
     * Copies the selected lines, or every line shown when none is selected; with the card closed,
     * the whole log. What {@code messageLog.copy}, the Copy button and Ctrl+C in the list do.
     */
    void copy() {
        var selected = list.getSelectionModel().getSelectedItems();
        java.util.List<MessageLog.Entry> entries =
                !isShowing() ? log.entries() : selected.isEmpty() ? list.getItems() : selected;
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

    /** The card itself, for a test to look inside. */
    javafx.scene.Node card() {
        return card;
    }

    /** The card's width and its list's height as laid out now. */
    double[] sizeForTest() {
        return new double[] {card.getWidth(), list.getHeight()};
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
        javafx.scene.Scene scene = host.scene();
        javafx.scene.Node owner = scene == null ? null : scene.getFocusOwner();
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
