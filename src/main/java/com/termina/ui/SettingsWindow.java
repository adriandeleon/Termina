package com.termina.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import com.termina.config.Settings;
import com.termina.i18n.Messages;
import com.termina.pty.ShellLauncher;
import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/**
 * Preferences, laid out the way Editora's are: a grouped sidebar on the left, one page per category
 * on the right, a search box that narrows both, and <b>no OK or Cancel</b> — every control writes
 * its setting immediately and the running terminal re-applies.
 *
 * <p>Live apply is the reason the window is worth building at all. Choosing a font or a theme is a
 * judgement about how something looks, and a dialog that defers the result until you dismiss it
 * makes you guess.
 */
public final class SettingsWindow {

    /**
     * Sidebar groups, in display order.
     *
     * <p>Each holds a catalogue key rather than a title, resolved on read. An enum constant is
     * initialised once, the first time the class is touched, so a translated title baked in there
     * would be whatever the language was at that moment — fine today, wrong the moment anything
     * relabels without a restart.
     */
    private enum Group {
        GENERAL("settings.group.general"),
        SYSTEM("settings.group.system");

        private final String key;

        Group(String key) {
            this.key = key;
        }

        String title() {
            return tr(key);
        }
    }

    /** Pages, in sidebar order within their group. */
    enum Category {
        APPEARANCE(Group.GENERAL, "settings.cat.appearance", MenuIcons::appearance),
        TERMINAL(Group.GENERAL, "settings.cat.terminal", MenuIcons::terminal),
        PROFILES(Group.GENERAL, "settings.cat.profiles", MenuIcons::profiles),
        KEYBOARD(Group.GENERAL, "settings.cat.keyboard", MenuIcons::keyboard),
        SYNC(Group.SYSTEM, "settings.cat.sync", MenuIcons::sync),
        ADVANCED(Group.SYSTEM, "settings.cat.advanced", MenuIcons::advanced);

        final Group group;
        private final String key;
        /** A supplier, not a node: a node has one parent, and the sidebar's cells are recycled. */
        private final Supplier<Node> icon;

        Category(Group group, String key, Supplier<Node> icon) {
            this.group = group;
            this.key = key;
            this.icon = icon;
        }

        String title() {
            return tr(key);
        }

        /**
         * The pages by catalogue key, in the sidebar's order: the same on every OS (OS-01), which
         * {@code SameOnEveryOsTest} asks as each of them (OS-03). Here and not on the window, so
         * asking loads no control.
         */
        static List<String> keys() {
            List<String> keys = new ArrayList<>();
            for (Category category : values()) keys.add(category.key);
            return keys;
        }
    }

    /**
     * One searchable control row. The title is kept apart from the rest of the text because a hit
     * in it outranks a hit in the description — see {@link #matchRank}.
     */
    private record Row(Category category, Node node, String title, String description, String keywords, VBox section) {}

    private final Settings settings;
    private final Stage stage = new Stage();
    private final Map<Category, Region> pages = new EnumMap<>(Category.class);
    private final List<Row> rows = new ArrayList<>();
    private final ListView<Object> sidebar = new ListView<>();
    private final StackPane pageHost = new StackPane();
    private final TextField search = new TextField();

    private final Set<Category> hiddenCategories = new LinkedHashSet<>();

    /** Guards control listeners while a page is being populated from stored values. */
    private boolean loading;

    /** Set for the duration of {@link #reload}, so a change it provokes cannot start another. */
    private boolean reloading;

    /**
     * Set while a control of this window writes a setting through, so the broadcast that write causes
     * does not reload the window that made it. The control already shows the value; whatever else on
     * the page depends on it is refreshed by the control's own listener.
     */
    private boolean writing;

    private PalettePreview preview;

    public SettingsWindow(Settings settings, com.termina.shell.ShellProfiles profiles) {
        this(settings, profiles, List.of(), null);
    }

    /**
     * @param keyCommands every command the Keyboard page can rebind, under its menu, on its default
     *     chord — a terminal window's table, since the menus are what define them
     * @param sync the application's settings sync, or null where there is none (a test), which leaves
     *     the Sync page unable to connect
     */
    // Every control field is assigned by build(), which makes every page — a loop over the
    // categories and a switch to each page's builder, which NullAway cannot follow. Only the
    // initialization check is off; reads of the fields are still checked.
    @SuppressWarnings("NullAway.Init")
    SettingsWindow(
            Settings settings,
            com.termina.shell.ShellProfiles profiles,
            List<Keybindings.Command> keyCommands,
            @Nullable SettingsSync sync) {
        this.settings = settings;
        this.profiles = profiles;
        this.sync = sync;
        this.keyCommands = keyCommands == null ? List.of() : List.copyOf(keyCommands);
        this.keyDefaults = Keybindings.defaults(this.keyCommands);
        build();
        // The window shows whatever is stored, and what is stored changes behind its back: the
        // theme from the palette, the menu bar from its chord, the zoom from its keys. Without
        // this a checkbox left showing the old value wrote that old value on its next click — a
        // click that did nothing, followed by one that did the opposite of its label.
        settings.addOnChange(this::onSettingsChanged);
        // The file is written a moment after the change, off the FX thread; the row that reports a
        // failed write hears about it then, not when the control was clicked.
        settings.addOnSaved(() -> javafx.application.Platform.runLater(() -> {
            if (stage.isShowing()) refreshSaveError();
        }));
        if (sync != null) sync.addListener(this::refreshSync);
    }

    private final com.termina.shell.ShellProfiles profiles;
    private final @Nullable SettingsSync sync;
    private final List<Keybindings.Command> keyCommands;
    private final Map<String, KeyCombination> keyDefaults;

    /** Types into the search box as the user would, so a capture can reach any page. */
    public void searchForCapture(String query) {
        search.setText(query);
    }

    /**
     * Presses chords into Keyboard rows' recording fields, as a hand would, and reports what each
     * row then shows. {@code closeWindow:C-S-t,C-S-t;newTab:BS} — rows by id, keys as {@link KeyChord}
     * tokens, {@code BS} for a bare Backspace.
     */
    public String fireKeysForCapture(String spec) {
        StringBuilder out = new StringBuilder();
        for (String part : spec.split(";")) {
            int colon = part.indexOf(':');
            if (colon < 0) continue;
            KeyRow row = keyRows.get(part.substring(0, colon).trim());
            if (row == null) {
                out.append(part, 0, colon).append("=no such row; ");
                continue;
            }
            row.field().requestFocus();
            for (String token : part.substring(colon + 1).split(",")) {
                String t = token.trim();
                KeyEvent press;
                if ("BS".equals(t)) {
                    press = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE, false, false, false, false);
                } else if (KeyChord.toCombination(t) instanceof javafx.scene.input.KeyCodeCombination chord) {
                    press = new KeyEvent(
                            KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            chord.getCode(),
                            chord.getShift() == KeyCombination.ModifierValue.DOWN,
                            chord.getControl() == KeyCombination.ModifierValue.DOWN,
                            chord.getAlt() == KeyCombination.ModifierValue.DOWN,
                            chord.getMeta() == KeyCombination.ModifierValue.DOWN);
                } else {
                    out.append(t).append("=unreadable; ");
                    continue;
                }
                javafx.event.Event.fireEvent(row.field(), press);
                out.append(row.command().id())
                        .append(' ')
                        .append(t)
                        .append(" -> [")
                        .append(row.field().getText())
                        .append("] ")
                        .append(row.status().isVisible() ? "\"" + row.status().getText() + "\"" : "-")
                        .append(row.reset().isDisabled() ? "" : " (reset on)")
                        .append("; ");
            }
        }
        return out.append("stored=").append(settings.keyBindings()).toString();
    }

    /** Every page as built, for the test that exercises their controls. */
    java.util.Collection<Region> pagesForTest() {
        return pages.values();
    }

    /** The window's scene, for the development capture hook. */
    public Scene scene() {
        return stage.getScene();
    }

    /** Opens the window on the Profiles page: Configure Profiles, from the profile menu or a chord. */
    public void showProfiles(Window from) {
        show(from);
        sidebar.getSelectionModel().select(Category.PROFILES);
    }

    /**
     * Shows the window beside {@code from}, without being owned by it. Ownership would close it
     * with the first terminal window to have opened it; see {@link SecondaryWindows}.
     */
    public void show(Window from) {
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
        reload();
        // Asked on every opening, so a `gh auth login` since the last one offers Create on GitHub.
        if (sync != null) sync.checkGitHub();
        SecondaryWindows.placeNear(stage, from);
        stage.show();
    }

    private void onSettingsChanged() {
        if (WindowRules.shouldReload(stage.isShowing(), reloading, writing)) reload();
    }

    /** Runs a write-through from one of this window's controls, marked as this window's own. */
    private void write(Runnable change) {
        boolean was = writing;
        writing = true;
        try {
            change.run();
        } finally {
            writing = was;
        }
    }

    /** Sets a field's text unless the user is in it, the way {@code reloadKeys} leaves a recording field alone. */
    private static void setUnlessEditing(TextField field, String text) {
        if (!field.isFocused()) field.setText(text);
    }

    // ---------------------------------------------------------------- shell

    private void build() {
        stage.setTitle(tr("settings.title", com.termina.AppInfo.NAME));

        for (Category category : Category.values()) {
            pages.put(category, buildPage(category));
        }

        search.setPromptText(tr("settings.search"));
        search.setAccessibleText(tr("settings.search"));
        sidebar.setAccessibleText(tr("settings.sidebar"));
        // Focused on open, and the theme hides a focused field's prompt: settings.css keeps it.
        search.getStyleClass().add("settings-search");
        search.textProperty().addListener((o, old, value) -> filter(value));
        // Enter in the search box is not a request to close the window, which is what the default
        // button would make of it if the event were left to bubble.
        search.setOnAction(javafx.event.Event::consume);
        VBox.setMargin(search, new Insets(0, 0, 8, 0));

        sidebar.setPrefWidth(190);
        sidebar.setMinWidth(150);
        sidebar.getStyleClass().add("settings-sidebar");
        sidebar.setCellFactory(list -> new SidebarCell());
        sidebar.getItems().setAll(sidebarItems());
        sidebar.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            if (item instanceof Category category) showPage(category);
        });
        // The group headers are mouse-transparent, which keeps a click off them; the arrow keys
        // need telling separately, or Down from Appearance lands on "SYSTEM" and selects nothing.
        sidebar.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            int step = e.getCode() == KeyCode.DOWN ? 1 : e.getCode() == KeyCode.UP ? -1 : 0;
            if (step == 0) return;
            List<Object> items = sidebar.getItems();
            int target = WindowRules.nextSelectable(
                    sidebar.getSelectionModel().getSelectedIndex(),
                    step,
                    items.size(),
                    i -> items.get(i) instanceof Category category && !hiddenCategories.contains(category));
            if (target >= 0) sidebar.getSelectionModel().select(target);
            e.consume();
        });

        pageHost.setPadding(new Insets(4, 0, 0, PAGE_GUTTER));
        pageHost.setAlignment(Pos.TOP_LEFT);

        Button reset = new Button(tr("settings.reset"));
        reset.setOnAction(e -> act(SettingsActions.Action.RESET));
        Button close = new Button(tr("settings.close"));
        close.setOnAction(e -> stage.hide());
        close.setDefaultButton(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, reset, spacer, close);
        footer.setPadding(new Insets(12, 0, 0, 0));
        footer.setAlignment(Pos.CENTER_LEFT);

        BorderPane body = new BorderPane();
        body.setLeft(sidebar);
        body.setCenter(pageHost);

        VBox root = new VBox(search, body, footer);
        VBox.setVgrow(body, Priority.ALWAYS);
        root.setPadding(new Insets(14));

        // Grown from the original 760x520 over several passes; a page of preferences that scrolls
        // hides the rows below the fold, which is the opposite of what a settings window is for.
        // Tall enough for the longest page, but never taller than the screen — a settings window
        // whose buttons are below the bottom edge is worse than one that scrolls. Every row added
        // since the first version has pushed this up; the clamp is what stops that becoming a
        // problem on a laptop.
        javafx.geometry.Rectangle2D visible = javafx.stage.Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(
                root,
                Math.min(UiZoom.px(1153), visible.getWidth() * 0.95),
                Math.min(UiZoom.px(985), visible.getHeight() * 0.92));
        // Scene stylesheets, not part of the theme: they must survive the runtime
        // setUserAgentStylesheet swap that changing the theme performs. app.css first, for the
        // state colours and the keyboard focus ring every window shares; settings.css after it,
        // so the window's own rules win where the two meet.
        scene.getStylesheets().add(UiZoom.sheet("app.css"));
        scene.getStylesheets().add(UiZoom.sheet("settings.css"));
        Fonts.installUiFont(scene);
        SecondaryWindows.closeOnEscape(stage, scene);
        // A press anywhere in the window closes the profile list's right-click menu (MENU-07).
        OneMenu.closeOnOutsidePress(scene);
        stage.setScene(scene);
        Icons.applyTo(stage);

        selectFirstVisible();
    }

    /**
     * Runs one of the window's actions as its command does: the window comes up first, on the
     * action's page, so its question and its result have somewhere to be seen (KBD-01, Pauta
     * 6.0.1). The buttons run the same {@link #act}.
     */
    void run(Window from, SettingsActions.Action action) {
        show(from);
        Category page = pageOf(action);
        if (page != null) sidebar.getSelectionModel().select(page);
        act(action);
    }

    private static @Nullable Category pageOf(SettingsActions.Action action) {
        return switch (action) {
            case RESET -> null;
            case IMPORT_THEME, REMOVE_THEME -> Category.APPEARANCE;
            case RESET_SHORTCUTS -> Category.KEYBOARD;
            case ADD_PROFILE, REMOVE_PROFILE -> Category.PROFILES;
            case SYNC_CONNECT, SYNC_CREATE, SYNC_CANCEL, SYNC_ANYWAY, SYNC_REMOVE_CREDENTIALS -> Category.SYNC;
        };
    }

    /** What each button does, and each command. */
    private void act(SettingsActions.Action action) {
        switch (action) {
            case RESET -> confirmReset();
            case IMPORT_THEME -> importThemeFile();
            case REMOVE_THEME -> removeChosenTheme();
            case RESET_SHORTCUTS -> confirmResetKeys();
            case ADD_PROFILE -> addUserProfile();
            case REMOVE_PROFILE -> removeSelectedProfile();
            case SYNC_CONNECT -> toggleSyncConnection();
            case SYNC_CREATE -> createSyncRepositoryOnGitHub();
            case SYNC_CANCEL -> {
                if (sync != null && !sync.cancel()) syncState.setText(tr("sync.state.tooLateToCancel"));
            }
            case SYNC_ANYWAY -> {
                if (sync != null) sync.syncAllowingLargeRemoval();
            }
            case SYNC_REMOVE_CREDENTIALS -> {
                // The token in the address may be the only copy the user has of it.
                if (sync != null && Confirm.ask(stage, Questions.removeSyncCredentials())) {
                    sync.removeCredentialsFromUrl();
                }
            }
        }
    }

    /** The titles of a profile row's right-click menu, with whether each can run: for tests. */
    java.util.List<String> profileMenuRowsForTest() {
        javafx.scene.control.ContextMenu menu = profileList.getContextMenu();
        javafx.event.Event.fireEvent(menu, new javafx.stage.WindowEvent(menu, javafx.stage.WindowEvent.WINDOW_SHOWING));
        return menu.getItems().stream()
                .map(item -> item.getText()
                        + (item.isDisable() ? " (disabled)" : "")
                        + (item.getStyleClass().contains("danger") ? " (danger)" : ""))
                .toList();
    }

    /** Whether a profile of the user's own is selected on the Profiles page: what Remove needs. */
    boolean hasRemovableProfileSelected() {
        com.termina.shell.Profile selected = editing;
        return selected != null && selected.isEditable();
    }

    /**
     * Asks before resetting, and says what the reset leaves alone.
     *
     * <p>The first version reset on the click. The button sits next to Close, the file it clears
     * held the user's profiles, and "put the font back" is what most people mean by it.
     */
    private void confirmReset() {
        if (!Confirm.ask(stage, Questions.resetSettings())) {
            return;
        }
        // One reload, here: the write's own broadcast is not taken as a reason for another.
        write(settings::resetToDefaults);
        reload();
    }

    private List<Object> sidebarItems() {
        List<Object> items = new ArrayList<>();
        Group current = null;
        for (Category category : Category.values()) {
            if (category.group != current) {
                current = category.group;
                items.add(current);
            }
            items.add(category);
        }
        return items;
    }

    /** Group headers are labels, not choices — they must not be selectable or clickable. */
    private final class SidebarCell extends ListCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("settings-group-header", "settings-sidebar-item", "settings-dimmed");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setDisable(false);
                setMouseTransparent(false);
                return;
            }
            if (item instanceof Group group) {
                setText(group.title().toUpperCase(Locale.ROOT));
                setGraphic(null);
                getStyleClass().add("settings-group-header");
                setMouseTransparent(true);
                setDisable(false);
            } else {
                Category category = (Category) item;
                setText(category.title());
                setGraphic(sidebarIcon(category));
                getStyleClass().add("settings-sidebar-item");
                setMouseTransparent(false);
                boolean dimmed = hiddenCategories.contains(category);
                setDisable(dimmed);
                if (dimmed) getStyleClass().add("settings-dimmed");
            }
        }
    }

    /**
     * The glyph in a box of fixed size. A scaled {@code SVGPath} still lays out at its unscaled
     * bounds, and those differ per glyph — the palette is 18 wide, the terminal 20 — so bare, each
     * label would start at a different x. Set fresh on every update rather than toggled: a
     * {@code Labeled} decides once whether it lays out its graphic (see CLAUDE.md).
     */
    private static Node sidebarIcon(Category category) {
        StackPane box = new StackPane(category.icon.get());
        box.setMinSize(SIDEBAR_ICON, SIDEBAR_ICON);
        box.setPrefSize(SIDEBAR_ICON, SIDEBAR_ICON);
        box.setMaxSize(SIDEBAR_ICON, SIDEBAR_ICON);
        box.getStyleClass().add("settings-sidebar-icon");
        return box;
    }

    /** Material's 24dp glyph at {@link MenuIcons}' scale, which is about 17px. */
    private static final double SIDEBAR_ICON = 18;

    private void showPage(Category category) {
        Region page = pages.get(category);
        pageHost.getChildren().setAll(page);
    }

    private void selectFirstVisible() {
        for (Object item : sidebar.getItems()) {
            if (item instanceof Category category && !hiddenCategories.contains(category)) {
                sidebar.getSelectionModel().select(item);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- search

    /** A section's heading text: the label every {@link #section} starts with. */
    private static String headingOf(@Nullable VBox section) {
        if (section == null || section.getChildren().isEmpty()) return "";
        return section.getChildren().get(0) instanceof Label heading ? heading.getText() : "";
    }

    /**
     * What a row's controls show, for the search (SET-03, Pauta 6.0.1): a button's or a switch's
     * label, every option of a choice, a field's prompt. Not the row's own title and description,
     * which are searched as themselves, and not what the user has typed into a field.
     */
    static String visibleText(Node node) {
        StringBuilder text = new StringBuilder();
        collectVisibleText(node, text);
        return text.toString().strip();
    }

    private static void collectVisibleText(Node node, StringBuilder text) {
        if (node.getStyleClass().contains("settings-row-title")
                || node.getStyleClass().contains("settings-row-description")) {
            return;
        }
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            text.append(labeled.getText()).append(' ');
        }
        if (node instanceof ComboBox<?> combo) {
            for (Object option : combo.getItems()) text.append(option).append(' ');
            return; // its field and its popup are the skin's
        }
        if (node instanceof TextField field) {
            if (field.getPromptText() != null)
                text.append(field.getPromptText()).append(' ');
            return;
        }
        if (node instanceof Spinner<?> || node instanceof ListView<?>) return;
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collectVisibleText(child, text);
        }
    }

    private void filter(String query) {
        hiddenCategories.clear();
        Set<Category> withHits = new LinkedHashSet<>();

        int[] ranks = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            // The heading and the page name are searched as one (SET-03): both say where the row is.
            String headings = headingOf(row.section()) + " " + row.category().title();
            SettingsSearch.Texts texts = new SettingsSearch.Texts(
                    row.title(), row.description(), visibleText(row.node()), headings, row.keywords());
            ranks[i] = SettingsSearch.landing(
                    SettingsSearch.score(query, texts), query, row.category().title());
            boolean visible = ranks[i] > 0;
            row.node().setVisible(visible);
            row.node().setManaged(visible);
            if (visible) withHits.add(row.category());
        }
        // A section whose every row is filtered out would otherwise leave a stray heading behind.
        for (Row row : rows) {
            VBox section = row.section();
            if (section == null) continue;
            boolean any = section.getChildren().stream()
                    .skip(1) // the heading itself
                    .anyMatch(Node::isManaged);
            section.setVisible(any);
            section.setManaged(any);
        }
        for (Category category : Category.values()) {
            if (!withHits.contains(category)) hiddenCategories.add(category);
        }
        sidebar.refresh();

        // A typed query lands on the page of its best match; clearing it leaves the user where
        // they were.
        int best = query == null || query.isBlank() ? -1 : SettingsSearch.bestOf(ranks);
        if (best >= 0) {
            sidebar.getSelectionModel().select(rows.get(best).category());
            return;
        }
        Object selected = sidebar.getSelectionModel().getSelectedItem();
        if (!(selected instanceof Category category) || hiddenCategories.contains(category)) {
            selectFirstVisible();
        }
    }

    // ---------------------------------------------------------------- page building

    /** The page's margin from the sidebar on one side and from the scrollbar on the other. */
    private static final double PAGE_GUTTER = 14;

    private Region buildPage(Category category) {
        VBox page = new VBox(4);
        page.setFillWidth(true);
        switch (category) {
            case APPEARANCE -> buildAppearance(page);
            case TERMINAL -> buildTerminal(page);
            case PROFILES -> buildProfiles(page);
            case KEYBOARD -> buildKeyboard(page);
            case SYNC -> buildSync(page);
            case ADVANCED -> buildAdvanced(page);
        }
        page.getChildren().add(helpLink(category));
        // A gutter on the right, matching the one the page host leaves on the left. Without it every
        // control on the page — the combos, the checkboxes, the opacity readout, the preview's own
        // border — ends flush against the scrollbar, and the preview appears to run underneath it.
        // On the content rather than the ScrollPane, so it is inside the scrolled area and the gap
        // is to the bar itself. Kept when the page is short enough not to scroll, because a page
        // that gains and loses its right margin as it grows reads as the window mis-sizing itself.
        page.setPadding(new Insets(0, PAGE_GUTTER, 0, 0));
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    /**
     * The page's link to its page of the documentation, <em>Help for</em> and the page's name
     * (DOCS-11, Pauta 6.0.1), under everything else on it. Where it leads is {@link SettingsHelp}'s
     * to say.
     */
    private Node helpLink(Category category) {
        javafx.scene.control.Hyperlink link =
                new javafx.scene.control.Hyperlink(tr("settings.helpFor", category.title()));
        link.getStyleClass().add("settings-help-link");
        link.setOnAction(e -> {
            link.setVisited(false); // a link opened once is the same link, not a paler one
            if (linkOpener != null) linkOpener.accept(SettingsHelp.url(category));
        });
        return link;
    }

    private java.util.function.@Nullable Consumer<String> linkOpener;

    /** How a page's help link opens its address: the application's one way of opening a link. */
    void openLinksWith(java.util.function.Consumer<String> opener) {
        this.linkOpener = opener;
    }

    private VBox section(VBox page, String title) {
        Label heading = new Label(title);
        heading.getStyleClass().add("settings-section-header");
        VBox box = new VBox(6, heading);
        box.setPadding(new Insets(10, 0, 10, 0));
        page.getChildren().add(box);
        return box;
    }

    /** A titled row with an explanatory line under it, in the shape Editora's settings use. */
    private Node row(
            Category category, VBox section, String title, String description, Region control, String keywords) {
        // An on/off row is a switch (KBD-18, Pauta 6.0.1): it takes focus, Space flips it, and it
        // is announced as a toggle. The checkbox the setting lives on stays, out of the scene, and
        // the switch is its view.
        if (control instanceof CheckBox check) {
            SettingSwitch shown = SettingSwitch.boundTo(check);
            return row(category, section, title, description, shown, shown, keywords);
        }
        return row(category, section, title, description, control, control, keywords);
    }

    /**
     * @param labelled the node the title names, for assistive technology — the control itself,
     *     or the field inside it when the control is a field with a status line under it
     */
    private Node row(
            Category category,
            VBox section,
            String title,
            String description,
            Region control,
            Node labelled,
            String keywords) {
        Label name = new Label(title);
        name.getStyleClass().add("settings-row-title");
        // The checkboxes are built without text, so without this a screen reader announces
        // "check box" and nothing else. Both halves: labelFor wires the title to the control, and
        // the accessible text covers the readers that ask the control directly.
        name.setLabelFor(labelled);
        if (labelled.getAccessibleText() == null) labelled.setAccessibleText(title);
        VBox text = new VBox(2, name);
        if (description != null && !description.isBlank()) {
            Label hint = new Label(description);
            hint.getStyleClass().add("settings-row-description");
            hint.setWrapText(true);
            text.getChildren().add(hint);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        control.setMinWidth(Region.USE_PREF_SIZE);

        HBox line = new HBox(12, text, control);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("settings-row");
        section.getChildren().add(line);
        rows.add(new Row(category, line, title, description == null ? "" : description, keywords, section));
        return line;
    }

    // ---------------------------------------------------------------- field helpers

    /**
     * A field with a verdict under it — "not found", "not executable" — refreshed shortly after
     * typing stops.
     *
     * <p>Debounced rather than per keystroke: each check stats the filesystem, which is cheap on a
     * local disk and not on a network home directory. The verdict is hidden, not blank, when there
     * is nothing to say, so the row does not reserve a line for it. A disabled field says nothing:
     * it is showing a discovered profile the user cannot change.
     */
    private static VBox withValidation(
            TextField field, Label status, java.util.function.Function<String, ShellLauncher.PathStatus> check) {
        status.getStyleClass().add("settings-row-description");
        status.getStyleClass().add("state-error");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);
        PauseTransition debounce = new PauseTransition(Duration.millis(300));
        debounce.setOnFinished(e -> checkPath(
                field, check, verdict -> showValidation(status, field.isDisabled() ? null : validationKey(verdict))));
        field.textProperty().addListener((o, old, text) -> debounce.playFromStart());
        field.disabledProperty().addListener((o, was, disabled) -> debounce.playFromStart());
        return new VBox(2, field, status);
    }

    /**
     * Where a path is checked: the question is the disk's (does it exist, can it run), so it is
     * asked on a thread of its own and never on the FX thread (PERF-01, Pauta 6.0.1). A virtual
     * thread each; a check is one or two file-system calls.
     */
    private static final java.util.concurrent.Executor PATH_CHECKS =
            com.termina.term.TerminalExecutors.virtual("termina-path-check");

    /**
     * Checks what {@code field} holds now, off the FX thread, and hands the verdict back on it.
     * A verdict for text the field no longer holds is dropped: the check for what it holds now
     * is already on its way.
     */
    private static void checkPath(
            TextField field,
            java.util.function.Function<String, ShellLauncher.PathStatus> check,
            java.util.function.Consumer<ShellLauncher.PathStatus> then) {
        String text = field.getText();
        PATH_CHECKS.execute(() -> {
            ShellLauncher.PathStatus verdict = check.apply(text);
            Platform.runLater(() -> {
                if (java.util.Objects.equals(text, field.getText())) then.accept(verdict);
            });
        });
    }

    /** Shows what is wrong with a field's value under it, or takes the line away for null. */
    private static void showValidation(Label status, @Nullable String key) {
        status.setText(key == null ? "" : tr(key));
        status.setVisible(key != null);
        status.setManaged(key != null);
    }

    /**
     * Whether a path field's value is written when the field commits (SET-04, Pauta 6.0.1): an
     * empty one, which means the default, and one that passed. Anything marked is not.
     */
    static boolean commits(ShellLauncher.PathStatus status) {
        return validationKey(status) == null;
    }

    /** The catalogue key for a verdict worth showing, or null when the path is fine or empty. */
    static @Nullable String validationKey(ShellLauncher.PathStatus status) {
        return switch (status) {
            case EMPTY, OK -> null;
            case NOT_FOUND -> "validation.notFound";
            case NOT_EXECUTABLE -> "validation.notExecutable";
            case NOT_A_DIRECTORY -> "validation.notADirectory";
        };
    }

    /**
     * The number in what somebody typed into a number field — "13px", "13,5", " 14 " — or null.
     * The default spinner converter throws on all three, uncaught, on the FX thread.
     */
    static @Nullable Double lenientNumber(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^\\s*([-+]?\\d+(?:[.,]\\d+)?)").matcher(text);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Makes an editable spinner forgiving: text it cannot read keeps the current value instead of
     * throwing, and after a commit the editor shows the value actually taken rather than the text
     * that was typed.
     */
    private static <T extends Number> void makeLenient(
            Spinner<T> spinner, java.util.function.DoubleFunction<T> toValue) {
        SpinnerValueFactory<T> factory = spinner.getValueFactory();
        StringConverter<T> original = factory.getConverter();
        factory.setConverter(new StringConverter<>() {
            @Override
            public String toString(T value) {
                return original.toString(value);
            }

            @Override
            public T fromString(String text) {
                Double number = lenientNumber(text);
                return number == null ? spinner.getValue() : toValue.apply(number);
            }
        });
        Runnable resync =
                () -> spinner.getEditor().setText(factory.getConverter().toString(spinner.getValue()));
        spinner.getEditor().setOnAction(e -> {
            spinner.commitValue();
            resync.run();
        });
        spinner.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) resync.run();
        });
    }

    // ---------------------------------------------------------------- pages

    private ComboBox<String> themeCombo;
    private ComboBox<String> fontCombo;
    private Spinner<Double> fontSize;
    private Spinner<Integer> scrollback;
    private ComboBox<Settings.CursorShape> cursorShape;
    private Spinner<Integer> padding;
    private CheckBox altIsMeta;
    private CheckBox copyOnSelect;
    private CheckBox notifications;
    private CheckBox clipboardWrite;
    private CheckBox typeAhead;
    private CheckBox bell;
    private CheckBox hideTabBar;
    private CheckBox showTabBar;
    private CheckBox showMenuBar;
    private CheckBox menuBarInTitleBar;
    private Spinner<Integer> uiZoom;
    private CheckBox showScrollBar;
    private CheckBox showStatusBar;
    private CheckBox confirmClose;
    private CheckBox restoreSession;
    private ComboBox<String> language;
    private Slider windowOpacity;
    private Label windowOpacityValue;
    private TextField shellField;
    private TextField linkCommandField;
    private Label settingsPath;
    private Label saveError;
    private CheckBox updateCheck;

    private Button removeTheme;
    private Label themeImportResult;

    private void importThemeFile() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("settings.importTheme"));
        chooser.getExtensionFilters()
                .add(new javafx.stage.FileChooser.ExtensionFilter(
                        tr("settings.importTheme.filter"), com.termina.theme.ThemeImport.EXTENSIONS));
        java.io.File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        importTheme(file.toPath());
    }

    /**
     * Imports a file and selects the first theme in it, saying what happened on the row.
     *
     * <p>Read and parsed off the FX thread: the file is a download, and a hostile one used to be able
     * to hang the window (a property list that shares objects exponentially) or take it down (one
     * nested deep enough to overflow the stack). Anything at all that goes wrong is shown on the row;
     * the result is applied back on the FX thread.
     */
    void importTheme(java.nio.file.Path file) {
        Object name = file.getFileName() == null ? file : file.getFileName();
        themeImportResult.setText(tr("settings.importTheme.working", name));
        // A second import while the first is still reading replaces it on the row (PERF-03): the row
        // describes the file last chosen, never an earlier one that happened to finish later.
        themeImport.submit(
                () -> {
                    try {
                        return new ThemeImport(Themes.importFile(file), null);
                    } catch (Throwable e) {
                        String reason = e.getMessage() == null || e.getMessage().isBlank()
                                ? e.getClass().getSimpleName()
                                : e.getMessage();
                        return new ThemeImport(List.of(), reason);
                    }
                },
                result -> {
                    String failure = result.failure();
                    if (failure != null) {
                        themeImportResult.setText(tr("settings.importTheme.failed", name, failure));
                        return;
                    }
                    themeImportResult.setText(tr(
                            "settings.importTheme.done",
                            String.join(
                                    ", ",
                                    result.added().stream()
                                            .map(ThemeEntry::displayName)
                                            .toList())));
                    themeCombo.getItems().setAll(Themes.byDisplayName().keySet());
                    if (!result.added().isEmpty())
                        settings.setThemeId(result.added().getFirst().id());
                });
    }

    /** What reading a theme file gave: the schemes added, or why none were. */
    private record ThemeImport(
            List<ThemeEntry> added, @Nullable String failure) {}

    private final com.termina.work.LatestOnly themeImport = new com.termina.work.LatestOnly(
            task -> {
                Thread worker = new Thread(task, "termina-theme-import");
                worker.setDaemon(true);
                worker.start();
            },
            javafx.application.Platform::runLater);

    private void removeChosenTheme() {
        ThemeEntry chosen = Themes.byDisplayName().get(themeCombo.getValue());
        if (chosen == null || chosen.isBuiltIn()) return;
        // Deletes the imported file, and nothing brings it back but importing it again.
        if (!Confirm.ask(stage, Questions.removeTheme(chosen.displayName()))) {
            return;
        }
        // Off the FX thread, like the import: a file deleted and a folder re-read (PERF-01, Pauta
        // 2.0.2). The button waits for it, so a second click cannot race the first.
        removeTheme.setDisable(true);
        Thread worker = new Thread(
                () -> {
                    String result;
                    try {
                        Themes.remove(chosen.id());
                        result = tr("settings.removeTheme.done", chosen.displayName());
                    } catch (java.io.IOException e) {
                        result = tr("settings.removeTheme.failed", chosen.displayName(), e.getMessage());
                    }
                    String shown = result;
                    javafx.application.Platform.runLater(() -> {
                        themeImportResult.setText(shown);
                        themeCombo.getItems().setAll(Themes.byDisplayName().keySet());
                        // What it falls back to is Editora Dark, which the stored id now means.
                        settings.setThemeId(Theme.EDITORA_DARK.id());
                    });
                },
                "termina-theme-remove");
        worker.setDaemon(true);
        worker.start();
    }

    private void buildAppearance(VBox page) {
        VBox theme = section(page, tr("settings.section.theme"));

        themeCombo = new ComboBox<>();
        themeCombo.getItems().setAll(Themes.byDisplayName().keySet());
        themeCombo.setPrefWidth(200);
        themeCombo.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            ThemeEntry chosen = Themes.byDisplayName().get(value);
            if (chosen != null) {
                write(() -> settings.setThemeId(chosen.id()));
                refreshPreview();
            }
            removeTheme.setDisable(chosen == null || chosen.isBuiltIn());
        });
        row(
                Category.APPEARANCE,
                theme,
                tr("settings.theme"),
                tr("settings.theme.desc"),
                themeCombo,
                "theme colour color dark light appearance palette");

        // Importing: a file chooser for the formats ThemeImport reads, and a line that says what
        // happened — the new theme's name, or why the file was not one.
        Button importTheme = new Button(tr("settings.importTheme.button"));
        importTheme.setOnAction(e -> act(SettingsActions.Action.IMPORT_THEME));
        removeTheme = new Button(tr("settings.removeTheme.button"));
        removeTheme.setOnAction(e -> act(SettingsActions.Action.REMOVE_THEME));
        themeImportResult = new Label();
        themeImportResult.getStyleClass().add("settings-note");
        themeImportResult.setWrapText(true);
        HBox themeButtons = new HBox(8, importTheme, removeTheme);
        VBox themeImport = new VBox(6, themeButtons, themeImportResult);
        row(
                Category.APPEARANCE,
                theme,
                tr("settings.importTheme"),
                tr("settings.importTheme.desc"),
                themeImport,
                "import theme itermcolors terminal windows terminal ghostty kitty alacritty scheme colours file");

        VBox font = section(page, tr("settings.section.font"));

        fontCombo = new ComboBox<>();
        fontCombo.setPrefWidth(240);
        fontCombo.getItems().setAll(MonospaceFonts.available());
        fontCombo.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            write(() -> settings.setFontFamily(value));
            refreshPreview();
        });
        row(
                Category.APPEARANCE,
                font,
                tr("settings.fontFamily"),
                tr("settings.fontFamily.desc"),
                fontCombo,
                "font family typeface monospace");

        fontSize = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                Settings.MIN_FONT_SIZE, Settings.MAX_FONT_SIZE, Settings.DEFAULT_FONT_SIZE, 1));
        fontSize.setEditable(true);
        fontSize.setPrefWidth(110);
        makeLenient(fontSize, d -> d);
        fontSize.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            write(() -> settings.setFontSize(value));
            refreshPreview();
        });
        row(
                Category.APPEARANCE,
                font,
                tr("settings.fontSize"),
                tr("settings.fontSize.desc", shortcutName()),
                fontSize,
                "font size zoom scale");

        VBox tabsSection = section(page, tr("settings.section.tabs"));

        hideTabBar = new CheckBox();
        hideTabBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setHideTabBarWhenSingle(value));
        });
        row(
                Category.APPEARANCE,
                tabsSection,
                tr("settings.hideTabBar"),
                tr("settings.hideTabBar.desc"),
                hideTabBar,
                "tab bar tabs hide single chrome strip header");

        showTabBar = new CheckBox();
        showTabBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setShowTabBar(value));
        });
        row(
                Category.APPEARANCE,
                tabsSection,
                tr("settings.showTabBar"),
                tr("settings.showTabBar.desc"),
                showTabBar,
                "tab bar tabs show hide strip header chrome");

        VBox windowSection = section(page, tr("settings.section.window"));

        showMenuBar = new CheckBox();
        showMenuBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setShowMenuBar(value));
        });
        boolean mac = com.termina.Os.isMac();
        // Disabled rather than hidden on macOS: a setting that silently does nothing is worse than
        // one that says why it cannot.
        showMenuBar.setDisable(mac);
        row(
                Category.APPEARANCE,
                windowSection,
                tr("settings.showMenuBar"),
                mac ? tr("settings.showMenuBar.mac") : tr("settings.showMenuBar.desc"),
                showMenuBar,
                "menu bar menubar hide chrome window");

        menuBarInTitleBar = new CheckBox();
        menuBarInTitleBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setMenuBarInTitleBar(value));
        });
        boolean extendedWindows =
                javafx.application.Platform.isSupported(javafx.application.ConditionalFeature.EXTENDED_WINDOW);
        // Same policy as the row above: disabled with the reason, never silently inert. Two
        // reasons here, and the row says whichever applies.
        menuBarInTitleBar.setDisable(!WindowRules.offersHeaderBarOption(mac, extendedWindows));
        String titleBarReason = mac
                ? tr("settings.menuBarInTitleBar.mac")
                : !extendedWindows
                        ? tr("settings.menuBarInTitleBar.unsupported")
                        : tr("settings.menuBarInTitleBar.desc");
        row(
                Category.APPEARANCE,
                windowSection,
                tr("settings.menuBarInTitleBar"),
                titleBarReason,
                menuBarInTitleBar,
                "title bar titlebar header menu bar unified decorations window buttons");

        // The UI zoom (WIN-12, Pauta 6.0.1): the whole chrome, at once. Written through UiZoom,
        // which scales the stylesheets on a worker before the setting changes.
        uiZoom = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                Settings.MIN_UI_ZOOM, Settings.MAX_UI_ZOOM, Settings.DEFAULT_UI_ZOOM, UiZoom.STEP));
        uiZoom.setEditable(true);
        UiZoom.bind(uiZoom.prefWidthProperty(), 110);
        makeLenient(uiZoom, d -> (int) Math.round(d));
        uiZoom.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            UiZoom.change(settings, value);
        });
        row(
                Category.APPEARANCE,
                windowSection,
                tr("settings.uiZoom"),
                tr("settings.uiZoom.desc"),
                uiZoom,
                "interface ui zoom scale size chrome menus tabs larger smaller percent");

        showScrollBar = new CheckBox();
        showScrollBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setShowScrollBar(value));
        });
        row(
                Category.APPEARANCE,
                windowSection,
                tr("settings.showScrollBar"),
                tr("settings.showScrollBar.desc"),
                showScrollBar,
                "scroll bar scrollbar scrollback history gutter");

        showStatusBar = new CheckBox();
        showStatusBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setShowStatusBar(value));
        });
        row(
                Category.APPEARANCE,
                windowSection,
                tr("settings.showStatusBar"),
                tr("settings.showStatusBar.desc"),
                showStatusBar,
                "status bar statusbar messages message log echo errors");

        windowOpacity = new Slider(WindowOpacity.MIN * 100, WindowOpacity.MAX * 100, 100);
        windowOpacity.setPrefWidth(180);
        windowOpacity.setMajorTickUnit(10);
        windowOpacity.setMinorTickCount(1);
        windowOpacity.setSnapToTicks(true);
        windowOpacityValue = new Label(tr("settings.percent", 100));
        windowOpacityValue.setMinWidth(44);
        // Live while dragging, but written only when the drag ends: every write saves the file and
        // broadcasts a settings change to every terminal in every window, which is not something to
        // do per pixel of travel.
        windowOpacity.valueProperty().addListener((o, old, value) -> {
            windowOpacityValue.setText(tr("settings.percent", WindowOpacity.percent(value.doubleValue() / 100)));
            if (loading || windowOpacity.isValueChanging()) return;
            write(() -> settings.setWindowOpacity(value.doubleValue() / 100));
        });
        windowOpacity.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing && !loading) write(() -> settings.setWindowOpacity(windowOpacity.getValue() / 100));
        });
        HBox opacityControl = new HBox(8, windowOpacity, windowOpacityValue);
        opacityControl.setAlignment(Pos.CENTER_RIGHT);
        row(
                Category.APPEARANCE,
                windowSection,
                tr("settings.windowOpacity"),
                tr("settings.windowOpacity.desc"),
                opacityControl,
                windowOpacity,
                "opacity transparent transparency see through alpha blur");

        VBox languageSection = section(page, tr("settings.section.language"));

        language = new ComboBox<>();
        language.getItems().add("");
        language.getItems().addAll(Messages.available().keySet());
        language.setPrefWidth(180);
        // The endonym, not the name in the current language: someone who has landed in a language
        // they cannot read needs to recognise their own in the list.
        language.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String code) {
                return code == null || code.isBlank() ? tr("settings.language.automatic") : Messages.languageName(code);
            }

            @Override
            public String fromString(String display) {
                return display;
            }
        });
        language.valueProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setUiLanguage(value == null ? "" : value));
        });
        row(
                Category.APPEARANCE,
                languageSection,
                tr("settings.language"),
                tr("settings.language.desc"),
                language,
                "language locale idioma langue sprache lingua idioma interface");

        VBox previewSection = section(page, tr("settings.section.preview"));
        preview = new PalettePreview();
        previewSection.getChildren().add(preview.node());
        rows.add(new Row(
                Category.APPEARANCE,
                preview.node(),
                tr("settings.section.preview"),
                "",
                "preview sample colours font",
                previewSection));
    }

    private void buildTerminal(VBox page) {
        VBox display = section(page, tr("settings.section.display"));

        cursorShape = new ComboBox<>();
        cursorShape.getItems().setAll(Settings.CursorShape.values());
        cursorShape.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Settings.CursorShape shape) {
                return shape == null ? "" : tr("cursor." + shape.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public Settings.@Nullable CursorShape fromString(String display) {
                return null;
            }
        });
        cursorShape.setPrefWidth(160);
        cursorShape.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            write(() -> settings.setCursorShape(value));
        });
        row(
                Category.TERMINAL,
                display,
                tr("settings.cursorShape"),
                tr("settings.cursorShape.desc"),
                cursorShape,
                "cursor caret shape block underline bar");

        padding = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                Settings.MIN_PADDING, Settings.MAX_PADDING, Settings.DEFAULT_PADDING, 1));
        padding.setEditable(true);
        padding.setPrefWidth(110);
        makeLenient(padding, d -> (int) Math.round(d));
        padding.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            write(() -> settings.setPadding(value));
        });
        row(
                Category.TERMINAL,
                display,
                tr("settings.padding"),
                tr("settings.padding.desc"),
                padding,
                "padding margin inset gutter spacing border edge");

        bell = new CheckBox();
        bell.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setBell(value));
        });
        row(Category.TERMINAL, display, tr("settings.bell"), tr("settings.bell.desc"), bell, "bell alert flash sound");

        notifications = new CheckBox();
        notifications.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setNotifications(value));
        });
        row(
                Category.TERMINAL,
                display,
                tr("settings.notifications"),
                tr("settings.notifications.desc"),
                notifications,
                "notification notify desktop osc 9 777 alert toast bubble");

        VBox session = section(page, tr("settings.section.session"));

        confirmClose = new CheckBox();
        confirmClose.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setConfirmClose(value));
        });
        row(
                Category.TERMINAL,
                session,
                tr("settings.confirmClose"),
                tr("settings.confirmClose.desc"),
                confirmClose,
                "close confirm running program prompt ask tab");

        restoreSession = new CheckBox();
        restoreSession.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setRestoreSession(value));
        });
        row(
                Category.TERMINAL,
                session,
                tr("settings.restoreSession"),
                tr("settings.restoreSession.desc"),
                restoreSession,
                "reopen restore session tabs panes launch startup start last time no-session");

        scrollback = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                Settings.MIN_SCROLLBACK, Settings.MAX_SCROLLBACK, Settings.DEFAULT_SCROLLBACK, 500));
        scrollback.setEditable(true);
        scrollback.setPrefWidth(130);
        makeLenient(scrollback, d -> (int) Math.round(d));
        scrollback.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            write(() -> settings.setScrollbackLines(value));
        });
        row(
                Category.TERMINAL,
                session,
                tr("settings.scrollback"),
                tr("settings.scrollback.desc"),
                scrollback,
                "scrollback history lines buffer memory");

        shellField = new TextField();
        shellField.setPromptText(tr("settings.shell.prompt"));
        shellField.setPrefWidth(260);
        // The launcher falls back to $SHELL when this is not executable, on purpose and without a
        // word; this line is the word. Typing /bin/zshh and getting bash otherwise reads as the
        // setting being broken.
        Label shellStatus = new Label();
        VBox shellControl = withValidation(shellField, shellStatus, ShellLauncher::validateShellPath);
        // Committed on Enter and on losing focus, and only a path that passes: one that names
        // nothing is marked and the setting keeps what it had (SET-04, Pauta 6.0.1).
        Runnable commitShell = () -> {
            if (loading) return;
            String typed = shellField.getText();
            // The disk is asked on a worker, which also resolves the command the menus will want
            // for this spelling; the write follows on the FX thread, a moment after the key.
            java.util.function.Function<String, ShellLauncher.PathStatus> check = text -> {
                ShellLauncher.PathStatus verdict = ShellLauncher.validateShellPath(text);
                if (commits(verdict)) com.termina.shell.ShellProfiles.warm(text, List.of());
                return verdict;
            };
            checkPath(shellField, check, verdict -> {
                showValidation(shellStatus, validationKey(verdict));
                if (commits(verdict)) write(() -> settings.setShell(typed));
            });
        };
        shellField.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) commitShell.run();
        });
        shellField.setOnAction(e -> commitShell.run());
        row(
                Category.TERMINAL,
                session,
                tr("settings.shell"),
                tr("settings.shell.desc"),
                shellControl,
                shellField,
                "shell zsh bash program command login");

        VBox links = section(page, tr("settings.section.links"));

        linkCommandField = new TextField();
        linkCommandField.setPromptText(tr("settings.linkOpenCommand.prompt"));
        linkCommandField.setPrefWidth(260);
        linkCommandField.focusedProperty().addListener((o, was, focused) -> {
            if (!focused && !loading) write(() -> settings.setLinkOpenCommand(linkCommandField.getText()));
        });
        linkCommandField.setOnAction(e -> {
            if (!loading) write(() -> settings.setLinkOpenCommand(linkCommandField.getText()));
        });
        row(
                Category.TERMINAL,
                links,
                tr("settings.linkOpenCommand"),
                // tr(), not tr(key, arg): the text shows the literal {file} placeholders, and
                // MessageFormat would try to read those as argument indexes and fail.
                tr("settings.linkOpenCommand.desc").replace("%s", shortcutName()),
                linkCommandField,
                "link url path click open editor command file line");

        VBox input = section(page, tr("settings.section.input"));

        altIsMeta = new CheckBox();
        altIsMeta.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setAltIsMeta(value));
        });
        row(
                Category.TERMINAL,
                input,
                tr("settings.altIsMeta"),
                tr("settings.altIsMeta.desc"),
                altIsMeta,
                "alt option meta escape readline compose");

        // No row for the multi-line paste prompt (SET-16, Pauta 3.0.0): it is on by default, and a
        // row could only turn it off, which is the unsafe way. It stays a settings.json key,
        // input.confirmPaste, for anyone who means to.

        copyOnSelect = new CheckBox();
        copyOnSelect.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setCopyOnSelect(value));
        });
        row(
                Category.TERMINAL,
                input,
                tr("settings.copyOnSelect"),
                tr("settings.copyOnSelect.desc"),
                copyOnSelect,
                "copy select selection clipboard automatic primary highlight");

        clipboardWrite = new CheckBox();
        clipboardWrite.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setClipboardWrite(value));
        });
        row(
                Category.TERMINAL,
                input,
                tr("settings.clipboardWrite"),
                tr("settings.clipboardWrite.desc"),
                clipboardWrite,
                "clipboard copy osc 52 osc52 ssh tmux vim remote program");

        typeAhead = new CheckBox();
        typeAhead.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setTypeAhead(value));
        });
        row(
                Category.TERMINAL,
                input,
                tr("settings.typeAhead"),
                tr("settings.typeAhead.desc"),
                typeAhead,
                "type ahead typeahead predict prediction latency ssh slow remote echo lag");
    }

    // ---------------------------------------------------------------- keyboard

    /** One command's row on the Keyboard page: its recording field, the line under it, and Reset. */
    private record KeyRow(Keybindings.Command command, TextField field, Label status, Button reset) {}

    private final Map<String, KeyRow> keyRows = new java.util.LinkedHashMap<>();

    /** Where a recording field keeps a chord it was refused because another command has it. */
    private static final Object PENDING_TAKEOVER = new Object();

    /**
     * Every rebindable command, a row each, under the menu it lives in — so the settings search
     * finds "close tab" the way it finds "font", and an empty menu's heading goes with its rows.
     *
     * <p>Recording is the palette row's, generalised: focusing a field starts it, the next chord
     * pressed is written through at once. A chord another command has is not taken on the first
     * press — the row says whose it is, and the same chord again moves it, which keeps the whole
     * exchange on the keyboard. Backspace or Delete on its own removes the chord.
     */
    private void buildKeyboard(VBox page) {
        VBox all = section(page, tr("settings.section.shortcuts"));
        Button resetAll = new Button(tr("settings.keys.resetAll"));
        resetAll.setOnAction(e -> act(SettingsActions.Action.RESET_SHORTCUTS));
        row(
                Category.KEYBOARD,
                all,
                tr("settings.keys.resetAll.title"),
                tr("settings.keys.resetAll.desc"),
                resetAll,
                "keyboard shortcut keybinding chord key reset default rebind");

        Map<String, VBox> sections = new java.util.LinkedHashMap<>();
        for (Keybindings.Command command : keyCommands) {
            VBox section = sections.computeIfAbsent(command.group(), group -> section(page, group));
            keyRow(section, command);
        }
    }

    private void keyRow(VBox section, Keybindings.Command command) {
        TextField field = new TextField();
        field.setEditable(false);
        field.setPrefWidth(170);
        field.getStyleClass().add("shortcut-capture");
        field.setPromptText(tr("settings.keys.recording"));
        Label status = new Label();
        status.getStyleClass().add("settings-row-description");
        status.setWrapText(true);
        status.setMaxWidth(260);
        showKeyStatus(status, null, false);
        Button reset = new Button(tr("settings.keys.reset"));
        reset.setOnAction(e -> resetKey(command.id()));
        KeyRow row = new KeyRow(command, field, status, reset);
        keyRows.put(command.id(), row);

        // Recording: the field empties so its prompt says what to do, and fills again with the
        // chord in force when the focus leaves — whether or not anything was recorded.
        field.focusedProperty().addListener((o, was, focused) -> {
            field.getProperties().remove(PENDING_TAKEOVER);
            if (focused) {
                field.setText("");
            } else {
                field.setText(keyText(command.id()));
                showKeyStatus(status, null, false);
            }
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, e -> onKeyRecorded(row, e));

        HBox fieldRow = new HBox(6, field, reset);
        fieldRow.setAlignment(Pos.CENTER_LEFT);
        VBox control = new VBox(2, fieldRow, status);
        HBox line = (HBox) row(
                Category.KEYBOARD,
                section,
                command.label(),
                "",
                control,
                field,
                command.group() + " " + command.id() + " keyboard shortcut keybinding chord key");
        // Fifty of them, with no description: the ordinary row's padding made the page five
        // screens long, and its top-aligned title sat above the field it names.
        line.getStyleClass().add("settings-key-row");
        ((VBox) line.getChildren().get(0)).setAlignment(Pos.CENTER_LEFT);
    }

    private void onKeyRecorded(KeyRow row, KeyEvent e) {
        boolean modified = e.isControlDown() || e.isAltDown() || e.isMetaDown();
        // Tab and Shift+Tab leave the field, as they do everywhere; Ctrl+Tab is a chord.
        if (e.getCode() == KeyCode.TAB && !modified) return;
        e.consume();
        TextField field = row.field();
        if (!modified && (e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.DELETE)) {
            writeKeys(KeybindingEdits.clear(
                    keyDefaults, settings.keyBindings(), row.command().id()));
            field.setText(keyText(row.command().id()));
            showKeyStatus(row.status(), null, false);
            return;
        }
        switch (ShortcutCapture.decide(e.getCode(), e.isControlDown(), e.isAltDown(), e.isMetaDown())) {
            case IGNORE -> {}
            case NEEDS_MODIFIER -> showKeyStatus(row.status(), tr("settings.keys.needsModifier"), true);
            case OK -> {
                KeyCombination chord = ShortcutCapture.combination(e);
                if (chord == null) return; // decide() has ruled out the presses that name no chord
                String id = row.command().id();
                String holder = KeybindingEdits.conflict(effectiveKeys(), chord, id, Keybindings.MAC);
                Object pending = field.getProperties().get(PENDING_TAKEOVER);
                if (holder != null
                        && !(pending instanceof KeyCombination p && Keybindings.same(p, chord, Keybindings.MAC))) {
                    field.getProperties().put(PENDING_TAKEOVER, chord);
                    showKeyStatus(
                            row.status(), tr("settings.keys.taken", chord.getDisplayText(), labelOf(holder)), true);
                    return;
                }
                field.getProperties().remove(PENDING_TAKEOVER);
                writeKeys(KeybindingEdits.rebind(keyDefaults, settings.keyBindings(), id, chord, Keybindings.MAC));
                field.setText(chord.getDisplayText());
                boolean fromShell = ShortcutCapture.takesFromShell(chord, Keybindings.MAC);
                showKeyStatus(
                        row.status(), fromShell ? tr("settings.keys.fromShell", chord.getDisplayText()) : null, false);
            }
        }
    }

    /**
     * Puts one command back on its default. When another command has that chord now, asks first:
     * that command is left with none, and a Reset that quietly unbinds something else is how the
     * user finds out a chord has stopped working.
     */
    private void resetKey(String id) {
        Map<String, String> stored = settings.keyBindings();
        String holder = KeybindingEdits.taken(keyDefaults, stored, id, Keybindings.MAC);
        KeyCombination home = keyDefaults.get(id);
        // taken() names a holder only for a command that has a default chord to take back.
        if (holder != null && home != null) {
            // It takes the chord from another command, which is left with none (CONF-01).
            if (!Confirm.ask(stage, Questions.resetKeyTaken(home.getDisplayText(), labelOf(holder), labelOf(id)))) {
                return;
            }
        }
        writeKeys(KeybindingEdits.reset(keyDefaults, stored, id, Keybindings.MAC));
    }

    private void confirmResetKeys() {
        if (!Confirm.ask(stage, Questions.resetAllKeys())) {
            return;
        }
        writeKeys(Map.of());
    }

    /** One write and one broadcast: every window rebinds, and the rows here are reloaded by hand. */
    private void writeKeys(Map<String, String> stored) {
        write(() -> settings.replaceKeyBindings(stored));
        reloadKeys();
    }

    private Map<String, KeyCombination> effectiveKeys() {
        return Keybindings.resolve(keyDefaults, settings.keyBindings(), Keybindings.MAC);
    }

    /** The chord a command is on, as the platform writes it, or "None". */
    private String keyText(String id) {
        KeyCombination chord = effectiveKeys().get(id);
        return chord == null ? tr("settings.keys.none") : chord.getDisplayText();
    }

    private String labelOf(String id) {
        for (Keybindings.Command command : keyCommands) {
            if (command.id().equals(id)) return command.label();
        }
        return id;
    }

    /** A refusal is red; a note about a chord that was taken is the warning colour. Hidden when null. */
    private static void showKeyStatus(Label status, @Nullable String message, boolean refusal) {
        status.setText(message == null ? "" : message);
        status.getStyleClass().removeAll("state-error", "state-warning");
        status.getStyleClass().add(refusal ? "state-error" : "state-warning");
        status.setVisible(message != null);
        status.setManaged(message != null);
    }

    /**
     * The default profile's description, naming the chord New Tab is on now — not a fixed
     * "Ctrl/Cmd+T", which is Ctrl+Shift+T off macOS and rebindable — or the command's name when it
     * has none.
     */
    private String defaultProfileDescription() {
        KeyCombination chord = effectiveKeys().get(NEW_TAB);
        return tr("settings.defaultProfile.desc", chord == null ? labelOf(NEW_TAB) : chord.getDisplayText());
    }

    private static final String NEW_TAB = "newTab";

    private @Nullable Label defaultProfileHint;

    /**
     * The note under the profile list, naming the chords the first and ninth profiles are on now —
     * Cmd+Option on macOS, Ctrl+Shift elsewhere, and either may have been rebound.
     */
    private String profilesNote() {
        return tr("settings.profiles.note", keyText(PROFILE_CHORD + 1), keyText(PROFILE_CHORD + 9));
    }

    /** {@code TerminalWindow.PROFILE_CHORD_ID}; the commands are that plus 1 to 9. */
    private static final String PROFILE_CHORD = "newTabProfile";

    private @Nullable Label profilesHint;

    private void reloadKeys() {
        Label hint = defaultProfileHint;
        if (hint != null) hint.setText(defaultProfileDescription());
        Label note = profilesHint;
        if (note != null) note.setText(profilesNote());
        Map<String, KeyCombination> effective = effectiveKeys();
        for (KeyRow row : keyRows.values()) {
            String id = row.command().id();
            // Not while recording: the empty field is the prompt.
            if (!row.field().isFocused()) {
                KeyCombination chord = effective.get(id);
                row.field().setText(chord == null ? tr("settings.keys.none") : chord.getDisplayText());
            }
            // Disabled is also off the Tab order, which keeps the page to one stop per command.
            row.reset().setDisable(!KeybindingEdits.isChanged(keyDefaults, effective, id, Keybindings.MAC));
        }
    }

    // ---------------------------------------------------------------- profiles

    private ComboBox<com.termina.shell.Profile> defaultProfile;
    private ListView<com.termina.shell.Profile> profileList;
    private TextField profileName;
    private TextField profileCommand;
    private TextField profileDirectory;
    private CheckBox profileShown;
    private Button removeProfile;
    private Label profileSource;
    private ComboBox<String> profileFont;
    private ComboBox<String> profileFontSize;
    private ComboBox<String> profileTheme;

    /** The sizes offered for a profile; the list's first entry, Default, follows the global one. */
    private static final List<String> PROFILE_FONT_SIZES =
            List.of("9", "10", "11", "12", "13", "14", "15", "16", "18", "20", "22", "24", "28", "32");

    /**
     * The profile list and its editor.
     *
     * <p>A list beside a form rather than a row per field, because the number of profiles is not
     * known in advance — which is the whole reason this page exists. Discovered profiles appear in
     * the same list as written ones and are read-only there: they are re-derived at every launch, so
     * an edit to one would be silently discarded, which is worse than a field that will not take it.
     */
    private void buildProfiles(VBox page) {
        VBox defaults = section(page, tr("settings.section.defaultProfile"));

        defaultProfile = new ComboBox<>();
        defaultProfile.setPrefWidth(260);
        defaultProfile.setCellFactory(list -> new ProfileCell(false));
        defaultProfile.setButtonCell(new ProfileCell(false));
        defaultProfile.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            write(() -> profiles.setDefaultProfileId(value.id()));
            refreshProfileList();
        });
        Node defaultRow = row(
                Category.PROFILES,
                defaults,
                tr("settings.defaultProfile"),
                defaultProfileDescription(),
                defaultProfile,
                "default profile shell new tab startup powershell cmd wsl bash zsh");
        // Names New Tab's chord, which can be rebound while this window is open: reloadKeys refreshes it.
        if (defaultRow.lookup(".settings-row-description") instanceof Label hint) defaultProfileHint = hint;

        VBox list = section(page, tr("settings.section.profiles"));

        profileList = new ListView<>();
        // Tall enough for the shells a normal machine finds plus a few of the user's own. At 200 the
        // list scrolled at seven entries, which on macOS it reaches from discovery alone — so the
        // profiles somebody had actually written were the ones below the fold, on the page whose
        // whole purpose is editing them. The page has the room.
        profileList.setPrefHeight(340);
        profileList.setMinHeight(140);
        profileList.setCellFactory(l -> new ProfileCell(true));
        profileList.setAccessibleText(tr("settings.section.profiles"));
        profileList.getSelectionModel().selectedItemProperty().addListener((o, old, value) -> {
            // Committed before the form is repopulated, and against the profile the form was
            // showing rather than the one now selected. Clicking another row moves focus to the
            // list and changes the selection in the same mouse press, and the order of those two is
            // not ours to decide — so keying the write off the live selection would rename whichever
            // profile had just been clicked, using the text typed for the previous one.
            commitProfileEdit();
            showProfile(value);
        });

        profileName = new TextField();
        profileName.setPromptText(tr("settings.profileName"));
        profileCommand = new TextField();
        profileCommand.setPromptText(tr("settings.profileCommand.prompt"));
        profileDirectory = new TextField();
        profileDirectory.setPromptText(tr("settings.profileDirectory.prompt"));
        // Committed on Enter and on losing focus, which is how the shell and link fields on the
        // Terminal page already behave — a keystroke-by-keystroke write would save the settings file
        // and re-apply every setting in every window once per character.
        for (TextField field : List.of(profileName, profileCommand, profileDirectory)) {
            field.setPrefWidth(320);
            field.focusedProperty().addListener((o, was, focused) -> {
                if (!focused) commitProfileEdit();
            });
            field.setOnAction(e -> commitProfileEdit());
        }

        profileShown = new CheckBox(tr("settings.profileShown"));
        profileShown.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            com.termina.shell.Profile selected = editing;
            if (selected == null) return;
            write(() -> profiles.setHidden(selected.id(), !value));
            refreshProfileList();
        });

        profileSource = new Label();
        profileSource.getStyleClass().add("settings-row-description");
        profileSource.setWrapText(true);

        // A profile's own look. Every profile can have one — the system shell and the discovered
        // ones too — because it is stored by the profile's id rather than in the profile, so these
        // stay enabled where the fields above are read-only. Default follows the global setting.
        String defaultLabel = tr("settings.profileStyle.default");
        profileFont = new ComboBox<>();
        profileFont.getItems().add(defaultLabel);
        profileFont.getItems().addAll(MonospaceFonts.available());
        profileFontSize = new ComboBox<>();
        profileFontSize.getItems().add(defaultLabel);
        profileFontSize.getItems().addAll(PROFILE_FONT_SIZES);
        profileTheme = new ComboBox<>();
        for (ComboBox<String> box : List.of(profileFont, profileFontSize, profileTheme)) {
            box.setPrefWidth(box == profileFontSize ? 120 : 240);
            box.valueProperty().addListener((o, old, value) -> commitProfileStyle());
        }

        Button addProfile = new Button(tr("settings.profileAdd"));
        addProfile.setOnAction(e -> act(SettingsActions.Action.ADD_PROFILE));
        removeProfile = new Button(tr("settings.profileRemove"));
        removeProfile.setOnAction(e -> act(SettingsActions.Action.REMOVE_PROFILE));

        // A row's own menu (MENU-05, Pauta 6.0.1): the list's two actions, each a command. Remove
        // is the row's, so it is styled as dangerous (MENU-12) and greyed for a profile that is
        // not the user's to remove, decided as the menu shows.
        javafx.scene.control.MenuItem addRow = new javafx.scene.control.MenuItem(
                tr("settings.profileAdd"), MenuIcons.command(SettingsActions.Action.ADD_PROFILE.id()));
        addRow.setOnAction(e -> act(SettingsActions.Action.ADD_PROFILE));
        javafx.scene.control.MenuItem removeRow = new javafx.scene.control.MenuItem(
                tr("settings.profileRemove"), MenuIcons.command(SettingsActions.Action.REMOVE_PROFILE.id()));
        removeRow.getStyleClass().add("danger");
        removeRow.setOnAction(e -> act(SettingsActions.Action.REMOVE_PROFILE));
        javafx.scene.control.ContextMenu profileMenu = new javafx.scene.control.ContextMenu(addRow, removeRow);
        MenuChords.command(addRow, SettingsActions.Action.ADD_PROFILE.id());
        MenuChords.command(removeRow, SettingsActions.Action.REMOVE_PROFILE.id());
        profileMenu.setOnShowing(e -> {
            removeRow.setDisable(!hasRemovableProfileSelected());
            // Each row's chord, from the bindings in force now (MENU-15).
            Map<String, KeyCombination> chords = effectiveKeys();
            MenuChords.fill(profileMenu.getItems(), chords::get);
        });
        profileList.setContextMenu(OneMenu.installRightClick(profileMenu));

        HBox buttons = new HBox(8, addProfile, removeProfile);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox form = new VBox(
                6,
                labelled(tr("settings.profileName"), profileName),
                labelled(
                        tr("settings.profileCommand"),
                        profileCommand,
                        withValidation(profileCommand, new Label(), ShellLauncher::validateCommand)),
                labelled(
                        tr("settings.profileDirectory"),
                        profileDirectory,
                        withValidation(profileDirectory, new Label(), ShellLauncher::validateDirectory)),
                labelled(tr("settings.profileFont"), profileFont),
                labelled(tr("settings.profileFontSize"), profileFontSize),
                labelled(tr("settings.profileTheme"), profileTheme),
                profileShown,
                profileSource,
                buttons);
        HBox.setHgrow(form, Priority.ALWAYS);

        HBox editor = new HBox(14, profileList, form);
        HBox.setHgrow(profileList, Priority.SOMETIMES);
        profileList.setPrefWidth(240);
        editor.getStyleClass().add("settings-row");

        list.getChildren().add(editor);
        rows.add(new Row(
                Category.PROFILES,
                editor,
                tr("settings.section.profiles"),
                "",
                "profile shell command wsl powershell cmd bash zsh fish add remove",
                list));

        Label note = new Label(profilesNote());
        profilesHint = note;
        note.getStyleClass().add("settings-row-description");
        note.setWrapText(true);
        list.getChildren().add(note);
    }

    private static VBox labelled(String title, Region control) {
        return labelled(title, control, control);
    }

    /** @param shown what goes under the title — the field itself, or the field with its status line */
    private static VBox labelled(String title, Node field, Region shown) {
        Label name = new Label(title);
        name.getStyleClass().add("settings-row-title");
        name.setLabelFor(field);
        if (field.getAccessibleText() == null) field.setAccessibleText(title);
        return new VBox(2, name, shown);
    }

    /** Name on one line, with what it runs under it — the command is what tells two WSLs apart. */
    private static final class ProfileCell extends ListCell<com.termina.shell.Profile> {
        private final boolean showCommand;

        ProfileCell(boolean showCommand) {
            this.showCommand = showCommand;
        }

        @Override
        protected void updateItem(com.termina.shell.Profile item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (!showCommand) {
                setText(item.name());
                return;
            }
            Label name = new Label(item.name());
            Label command = new Label(item.commandLine());
            command.getStyleClass().add("settings-row-description");
            setText(null);
            setGraphic(new VBox(1, name, command));
        }
    }

    /** Fills the form from the selected profile, and decides what may be edited. */
    private void showProfile(com.termina.shell.Profile profile) {
        // Saved and restored rather than set to false: this runs from the list's selection
        // listener, which fires in the middle of refreshProfileList's own guarded block. Clearing
        // the flag here would leave the rest of that rebuild unguarded, and the default-profile
        // picker being repopulated would write a new default the user never chose.
        boolean was = loading;
        loading = true;
        try {
            boolean present = profile != null;
            boolean editable = present && profile.isEditable();
            profileName.setText(present ? profile.name() : "");
            profileCommand.setText(present ? profile.commandLine() : "");
            profileDirectory.setText(present ? profile.workingDirectory() : "");
            profileName.setDisable(!editable);
            profileCommand.setDisable(!editable);
            profileDirectory.setDisable(!editable);
            removeProfile.setDisable(!editable);
            // The system shell is what a window falls back to, so it is the one entry that cannot be
            // hidden — a settings file that hid it would leave the menu with nothing guaranteed.
            boolean hideable = present && !com.termina.shell.ShellProfiles.SYSTEM_ID.equals(profile.id());
            profileShown.setDisable(!hideable);
            profileShown.setSelected(!present || !profiles.hiddenIds().contains(profile.id()));
            profileSource.setText(present ? sourceNote(profile) : "");
            showProfileStyle(present ? settings.profileStyle(profile.id()) : null);
        } finally {
            loading = was;
        }
        editing = profile;
    }

    /** The three style pickers, for a profile's style or none; the caller holds {@code loading}. */
    private void showProfileStyle(com.termina.config.Settings.@Nullable ProfileStyle style) {
        String defaultLabel = tr("settings.profileStyle.default");
        profileTheme.getItems().setAll(defaultLabel);
        profileTheme.getItems().addAll(Themes.byDisplayName().keySet());
        for (ComboBox<String> box : List.of(profileFont, profileFontSize, profileTheme)) box.setDisable(style == null);
        if (style == null) {
            profileFont.setValue(defaultLabel);
            profileFontSize.setValue(defaultLabel);
            profileTheme.setValue(defaultLabel);
            return;
        }
        profileFont.setValue(style.fontFamily().isEmpty() ? defaultLabel : style.fontFamily());
        Double size = style.fontSize();
        profileFontSize.setValue(size == null ? defaultLabel : formatSize(size));
        ThemeEntry theme = style.themeId().isEmpty() ? null : Themes.byId(style.themeId());
        profileTheme.setValue(
                Themes.followsSystem(style.themeId())
                        ? Themes.systemDisplayName()
                        : theme == null || !theme.id().equals(style.themeId()) ? defaultLabel : theme.displayName());
    }

    private static String formatSize(double size) {
        return size == Math.rint(size) ? String.valueOf((long) size) : String.valueOf(size);
    }

    /** Writes the three pickers to the profile the form is showing. */
    private void commitProfileStyle() {
        if (loading || editing == null) return;
        String defaultLabel = tr("settings.profileStyle.default");
        String font = profileFont.getValue();
        String size = profileFontSize.getValue();
        String themeName = profileTheme.getValue();
        ThemeEntry theme = themeName == null || themeName.equals(defaultLabel)
                ? null
                : Themes.byDisplayName().get(themeName);
        Double fontSize = null;
        if (size != null && !size.equals(defaultLabel)) {
            try {
                fontSize = Double.parseDouble(size);
            } catch (NumberFormatException e) {
                fontSize = null;
            }
        }
        com.termina.config.Settings.ProfileStyle style = new com.termina.config.Settings.ProfileStyle(
                font == null || font.equals(defaultLabel) ? "" : font, fontSize, theme == null ? "" : theme.id());
        String profileId = editing.id();
        write(() -> settings.setProfileStyle(profileId, style));
    }

    /**
     * The profile the form is currently showing.
     *
     * <p>Not the list's selection: the two differ for exactly as long as it takes a click to move
     * focus and change the selection, which is the window in which an edit is committed.
     */
    private com.termina.shell.Profile editing;

    private static String sourceNote(com.termina.shell.Profile profile) {
        return switch (profile.source()) {
            case SYSTEM -> tr("settings.profileSource.system");
            case DISCOVERED -> tr("settings.profileSource.discovered");
            case USER -> tr("settings.profileSource.user");
        };
    }

    /**
     * Writes the form back, if it is on a profile that can take it.
     *
     * <p>A blank command deletes nothing and saves nothing: the field is committed on focus loss, so
     * clearing it on the way to clicking Remove would otherwise drop the profile out of the list
     * before the click landed.
     */
    private void commitProfileEdit() {
        if (loading) return;
        com.termina.shell.Profile selected = editing;
        if (selected == null || !selected.isEditable()) return;
        if (profileName.getText().isBlank() || profileCommand.getText().isBlank()) return;
        String name = profileName.getText().trim();
        String command = profileCommand.getText();
        String directory = profileDirectory.getText();
        // Whether the command and the folder are there is the disk's to say, so it is asked on a
        // worker (PERF-01, Pauta 6.0.1) and the edit is made back here with the two answers.
        PATH_CHECKS.execute(() -> {
            boolean commandPasses = commits(ShellLauncher.validateCommand(command));
            boolean directoryPasses = commits(ShellLauncher.validateDirectory(directory));
            Platform.runLater(
                    () -> applyProfileEdit(selected.id(), name, command, commandPasses, directory, directoryPasses));
        });
    }

    /** The edit itself, on the FX thread, once the worker has said which of the two paths pass. */
    private void applyProfileEdit(
            String id, String name, String command, boolean commandPasses, String directory, boolean directoryPasses) {
        List<com.termina.shell.Profile> user = new ArrayList<>(profiles.userProfiles());
        boolean changed = false;
        for (int i = 0; i < user.size(); i++) {
            if (!user.get(i).id().equals(id)) continue;
            // A command or a directory that is marked is not written (SET-04, Pauta 6.0.1): the
            // profile keeps the one it had, and the mark under the field says why.
            com.termina.shell.Profile edited = user.get(i).withName(name);
            if (commandPasses) edited = edited.withCommandLine(command);
            if (directoryPasses) edited = edited.withWorkingDirectory(directory);
            if (edited.equals(user.get(i))) return;
            user.set(i, edited);
            changed = true;
            break;
        }
        if (!changed) return;
        write(() -> profiles.setUserProfiles(user));
        // The list is redrawn around the profile that was edited, unless the user has since gone
        // to another: the answer came a moment after the key, and the selection is theirs.
        com.termina.shell.Profile now = editing;
        refreshProfileList(now == null ? id : now.id());
    }

    private void addUserProfile() {
        com.termina.shell.Profile created = profiles.newUserProfile(tr("settings.profileNewName"))
                .withName(tr("settings.profileNewName"))
                // Seeded with the system shell rather than left empty: a profile with no command is
                // not saved, so an empty one would vanish the moment the list was rebuilt.
                .withCommandLine(profiles.systemProfile().commandLine());
        List<com.termina.shell.Profile> user = new ArrayList<>(profiles.userProfiles());
        user.add(created);
        write(() -> profiles.setUserProfiles(user));
        refreshProfileList(created.id());
        profileName.requestFocus();
        profileName.selectAll();
    }

    private void removeSelectedProfile() {
        com.termina.shell.Profile selected = editing;
        if (selected == null || !selected.isEditable()) return;
        if (!Confirm.ask(stage, Questions.removeProfile(selected.name()))) {
            return;
        }
        // Its style, the default and the hidden entry go with it: the next profile added may get its id.
        write(() -> profiles.removeUserProfile(selected.id()));
        refreshProfileList();
    }

    private void refreshProfileList() {
        com.termina.shell.Profile selected = profileList.getSelectionModel().getSelectedItem();
        refreshProfileList(selected == null ? null : selected.id());
    }

    /** Rebuilds the list and the default picker, keeping the selection on {@code selectId}. */
    private void refreshProfileList(@Nullable String selectId) {
        loading = true;
        try {
            List<com.termina.shell.Profile> all = profiles.all();
            // The picker offers only what is on the menu; the list shows hidden ones too, since
            // hiding one is done from the list and there would otherwise be no way back.
            List<com.termina.shell.Profile> listed = new ArrayList<>(all);
            for (com.termina.shell.Profile hidden : hiddenProfiles()) {
                if (listed.stream().noneMatch(p -> p.id().equals(hidden.id()))) listed.add(hidden);
            }
            profileList.getItems().setAll(listed);
            defaultProfile.getItems().setAll(all);
            defaultProfile.setValue(profiles.defaultProfile());
        } finally {
            loading = false;
        }
        for (com.termina.shell.Profile profile : profileList.getItems()) {
            if (profile.id().equals(selectId)) {
                profileList.getSelectionModel().select(profile);
                return;
            }
        }
        profileList.getSelectionModel().selectFirst();
    }

    /** Hidden entries still have to be listed, or unhiding one would be impossible from here. */
    private List<com.termina.shell.Profile> hiddenProfiles() {
        List<com.termina.shell.Profile> hidden = new ArrayList<>();
        Set<String> ids = profiles.hiddenIds();
        if (ids.isEmpty()) return hidden;
        // Rebuilt without the hide filter, which is the only way to see what is being hidden.
        for (com.termina.shell.Profile profile : profiles.allIncludingHidden()) {
            if (ids.contains(profile.id())) hidden.add(profile);
        }
        return hidden;
    }

    private void buildAdvanced(VBox page) {
        VBox files = section(page, tr("settings.section.files"));

        settingsPath = new Label();
        settingsPath.getStyleClass().add("settings-path");
        settingsPath.setWrapText(true);
        // A save that fails is otherwise invisible: the control shows the new value, the log has a
        // warning nobody reads, and the file still holds the old one until the next launch.
        saveError = new Label();
        saveError.getStyleClass().add("settings-row-description");
        saveError.getStyleClass().add("state-error");
        saveError.setWrapText(true);
        VBox fileControl = new VBox(2, settingsPath, saveError);
        fileControl.setMaxWidth(420);
        row(
                Category.ADVANCED,
                files,
                tr("settings.settingsFile"),
                tr("settings.settingsFile.desc"),
                fileControl,
                settingsPath,
                "settings file path properties config location save error");

        // SET-12 (Pauta 2.0.2). Each button reports on its row; the palette's commands report to the
        // window that ran them.
        ConfigTransfer transfer = new ConfigTransfer(settings);
        Label transferResult = new Label();
        transferResult.getStyleClass().add("settings-row-description");
        transferResult.setWrapText(true);
        transferResult.setMaxWidth(420);
        Reporter onRow = (severity, message) -> {
            transferResult.setText(message);
            transferResult.getStyleClass().remove("state-error");
            if (severity == MessageLog.Severity.ERROR)
                transferResult.getStyleClass().add("state-error");
        };
        Button exportConfig = new Button(tr("settings.exportConfig.button"));
        exportConfig.setOnAction(e -> transfer.export(onRow));
        row(
                Category.ADVANCED,
                files,
                tr("settings.exportConfig"),
                tr("settings.exportConfig.desc"),
                exportConfig,
                "export backup configuration zip settings save copy move computer");
        Button importConfig = new Button(tr("settings.importConfig.button"));
        importConfig.setOnAction(e -> transfer.importFrom(stage, onRow));
        row(
                Category.ADVANCED,
                files,
                tr("settings.importConfig"),
                tr("settings.importConfig.desc"),
                new VBox(4, importConfig, transferResult),
                importConfig,
                "import restore configuration zip settings backup load computer");

        VBox updates = section(page, tr("settings.section.updates"));

        updateCheck = new CheckBox();
        updateCheck.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            write(() -> settings.setUpdateCheck(value));
        });
        row(
                Category.ADVANCED,
                updates,
                tr("settings.updateCheck"),
                tr("settings.updateCheck.desc"),
                updateCheck,
                "update check startup github release network privacy automatic");

        VBox diagnostics = section(page, tr("settings.section.diagnostics"));
        Button showDebugLog = new Button(tr("settings.debugLog.button"));
        showDebugLog.setOnAction(e -> showDebugLog());
        row(
                Category.ADVANCED,
                diagnostics,
                tr("settings.debugLog"),
                tr("settings.debugLog.desc"),
                showDebugLog,
                "debug log diagnostics errors warnings crash trace troubleshoot");
    }

    /** Opens the application's Debug Log; null where no window manager made this window. */
    private java.util.function.@Nullable Consumer<javafx.stage.Window> debugLogOpener;

    /**
     * How this page's button opens the Debug Log: the application's one window, the one the
     * {@code debugLog} command opens, with Report a Problem on it (DIAG-03, Pauta 6.0.1).
     */
    void openDebugLogWith(java.util.function.Consumer<javafx.stage.Window> opener) {
        this.debugLogOpener = opener;
    }

    private void showDebugLog() {
        if (debugLogOpener != null) {
            debugLogOpener.accept(stage);
            return;
        }
        // No application around this window (a test): a log window of its own, which has nowhere
        // to send a report from.
        if (debugLog == null) debugLog = new DebugLogWindow(stage);
        debugLog.show();
    }

    /** Built on first use, and only without an opener. */
    private @Nullable DebugLogWindow debugLog;

    // ---------------------------------------------------------------- sync

    private TextField syncRepository;
    private TextField syncBranch;
    private Label syncFieldError;
    private Button syncConnect;
    private Button syncCreateGitHub;
    /** A repository is being made on GitHub; the connection's buttons wait for it. */
    private boolean syncCreating;

    private Label syncState;

    /** The sync's phase as a pill (SET-21, Pauta 6.0.1): one word, colored for how it stands. */
    private final Label syncPill = new Label();

    private Label syncAccount;
    private Button syncNow;
    private Button syncCancel;
    private Button syncAnyway;
    private Button syncRemoveCredentials;
    private CheckBox syncAuto;
    private Spinner<Integer> syncInterval;

    private void buildSync(VBox page) {
        VBox repository = section(page, tr("settings.section.syncRepository"));

        syncRepository = new TextField();
        syncRepository.setPromptText(tr("settings.sync.repository.prompt"));
        syncRepository.setPrefWidth(320);
        syncRepository.textProperty().addListener((o, old, text) -> {
            if (!loading) showSyncFieldError(null);
        });
        commitOnEnterOrBlur(syncRepository, this::commitSyncFields);
        syncFieldError = new Label();
        syncFieldError.getStyleClass().add("settings-row-description");
        syncFieldError.getStyleClass().add("state-error");
        syncFieldError.setWrapText(true);
        syncFieldError.setMaxWidth(320);
        showSyncFieldError(null);
        VBox repositoryControl = new VBox(2, syncRepository, syncFieldError);
        row(
                Category.SYNC,
                repository,
                tr("settings.sync.repository"),
                tr("settings.sync.repository.desc"),
                repositoryControl,
                syncRepository,
                "sync repository git url github remote ssh https private dotfiles");

        syncBranch = new TextField();
        syncBranch.setPromptText(Settings.DEFAULT_SYNC_BRANCH);
        syncBranch.setPrefWidth(160);
        commitOnEnterOrBlur(syncBranch, this::commitSyncFields);
        row(
                Category.SYNC,
                repository,
                tr("settings.sync.branch"),
                tr("settings.sync.branch.desc"),
                syncBranch,
                "sync branch git main");

        syncConnect = new Button();
        syncConnect.setOnAction(e -> act(SettingsActions.Action.SYNC_CONNECT));
        // Offered only while gh is installed and signed in, which `show` asks.
        syncCreateGitHub = new Button(tr("settings.sync.createGithub"));
        syncCreateGitHub.setOnAction(e -> act(SettingsActions.Action.SYNC_CREATE));
        setShown(syncCreateGitHub, false);
        row(
                Category.SYNC,
                repository,
                tr("settings.sync.connection"),
                tr("settings.sync.connection.desc"),
                new HBox(6, syncConnect, syncCreateGitHub),
                syncConnect,
                "sync connect disconnect enable disable create github new private repository gh");

        VBox status = section(page, tr("settings.section.syncStatus"));

        syncState = new Label();
        syncState.setWrapText(true);
        syncState.getStyleClass().add("settings-row-description");
        syncNow = new Button(tr("settings.sync.now"));
        syncNow.setOnAction(e -> {
            if (sync != null) sync.syncNow();
        });
        syncCancel = new Button(tr("dialog.cancel"));
        syncCancel.setOnAction(e -> act(SettingsActions.Action.SYNC_CANCEL));
        syncAnyway = new Button(tr("settings.sync.anyway"));
        // The dangerous alternative beside the safe one, in red (CONF-11, Pauta 6.0.1): it is
        // shown only once a sync has stopped to ask.
        syncAnyway.getStyleClass().add("danger");
        syncAnyway.setOnAction(e -> act(SettingsActions.Action.SYNC_ANYWAY));
        // Said, because it is otherwise invisible: which of the user's GitHub accounts this computer
        // syncs as, whichever one `gh auth switch` last made active.
        syncAccount = new Label();
        syncAccount.setWrapText(true);
        syncAccount.getStyleClass().add("settings-row-description");
        syncRemoveCredentials = new Button(tr("settings.sync.removeCredentials"));
        syncRemoveCredentials.setOnAction(e -> act(SettingsActions.Action.SYNC_REMOVE_CREDENTIALS));
        HBox buttons = new HBox(6, syncNow, syncCancel, syncAnyway, syncRemoveCredentials);
        syncPill.getStyleClass().add("sync-pill");
        VBox stateControl = new VBox(6, new HBox(syncPill), syncState, syncAccount, buttons);
        stateControl.setMaxWidth(420);
        stateControl.setPrefWidth(420);
        row(
                Category.SYNC,
                status,
                tr("settings.sync.status"),
                "",
                stateControl,
                syncState,
                "sync status last synced now error failed cancel");

        syncAuto = new CheckBox();
        syncAuto.selectedProperty().addListener((o, old, value) -> {
            if (!loading) write(() -> settings.setSyncAuto(value));
        });
        row(
                Category.SYNC,
                status,
                tr("settings.sync.auto"),
                tr("settings.sync.auto.desc"),
                syncAuto,
                "sync automatic background startup interval");

        syncInterval = new Spinner<>(
                Settings.MIN_SYNC_INTERVAL, Settings.MAX_SYNC_INTERVAL, Settings.DEFAULT_SYNC_INTERVAL, 5);
        syncInterval.setEditable(true);
        syncInterval.setPrefWidth(110);
        makeLenient(syncInterval, value -> (int) Math.round(value));
        syncInterval.valueProperty().addListener((o, old, value) -> {
            if (!loading && value != null) write(() -> settings.setSyncIntervalMinutes(value));
        });
        row(
                Category.SYNC,
                status,
                tr("settings.sync.interval"),
                tr("settings.sync.interval.desc"),
                syncInterval,
                "sync interval minutes how often schedule");

        VBox what = section(page, tr("settings.section.syncWhat"));
        CheckBox keys = new CheckBox();
        keys.setSelected(true);
        keys.setDisable(true);
        row(
                Category.SYNC,
                what,
                tr("settings.sync.keybindings"),
                tr("settings.sync.keybindings.desc"),
                keys,
                "sync key bindings shortcuts chords keyboard mac windows linux");
        Label notSynced = new Label(tr("settings.sync.notSynced"));
        notSynced.getStyleClass().add("settings-row-description");
        notSynced.setWrapText(true);
        what.getChildren().add(notSynced);
    }

    /** A text field's commit: Enter in it, and the focus leaving it (SET-04). */
    private static void commitOnEnterOrBlur(TextField field, Runnable commit) {
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) commit.run();
        });
    }

    /**
     * Writes the repository and branch as they are typed in, on Enter or when the focus leaves
     * either field, so they are settings like any other and not something only Connect stores
     * (SET-04, Pauta 6.0.1). A value that cannot work is marked and not written. The fields are
     * disabled while connected, so this never moves a sync that is running.
     */
    private void commitSyncFields() {
        if (loading || settings.syncEnabled() || syncCreating) return;
        String url =
                syncRepository.getText() == null ? "" : syncRepository.getText().strip();
        String branch = syncBranch.getText() == null || syncBranch.getText().isBlank()
                ? Settings.DEFAULT_SYNC_BRANCH
                : syncBranch.getText().strip();
        String problem = syncCommitProblem(url, branch);
        if (problem != null) {
            showSyncFieldError(
                    "settings.sync.repository.credentials".equals(problem)
                            ? tr(problem, com.termina.sync.SyncEngine.withoutCredentials(url))
                            : tr(problem));
            return;
        }
        showSyncFieldError(null);
        write(() -> {
            settings.setSyncRepository(url);
            settings.setSyncBranch(branch);
        });
    }

    /**
     * What is wrong with the sync fields as a value to store, or null. An empty repository is
     * nothing to connect to but a fine thing to store: it is how the field is cleared.
     */
    static @Nullable String syncCommitProblem(String url, String branch) {
        if (url.isEmpty()) {
            return com.termina.sync.SyncEngine.isUsableBranch(branch) ? null : "settings.sync.branch.invalid";
        }
        return syncFieldProblem(url, branch);
    }

    private void showSyncFieldError(@Nullable String text) {
        syncFieldError.setText(text == null ? "" : text);
        syncFieldError.setVisible(text != null);
        syncFieldError.setManaged(text != null);
    }

    /**
     * Connect checks the fields first and says what is wrong with them on the spot, rather than storing
     * a URL that can never work and reporting it as a failed sync later. A password in the URL is refused
     * outright — it would sit in plain text in the settings file — with the URL it should have been
     * offered back.
     */
    private void toggleSyncConnection() {
        if (sync == null) return;
        if (settings.syncEnabled()) {
            write(() -> settings.setSyncEnabled(false));
            sync.disconnected();
            refreshSync();
            return;
        }
        String url =
                syncRepository.getText() == null ? "" : syncRepository.getText().strip();
        String branch = syncBranch.getText() == null || syncBranch.getText().isBlank()
                ? Settings.DEFAULT_SYNC_BRANCH
                : syncBranch.getText().strip();
        String problem = syncFieldProblem(url, branch);
        if (problem != null) {
            showSyncFieldError(
                    "settings.sync.repository.credentials".equals(problem)
                            ? tr(problem, com.termina.sync.SyncEngine.withoutCredentials(url))
                            : tr(problem));
            return;
        }
        showSyncFieldError(null);
        write(() -> {
            settings.setSyncRepository(url);
            settings.setSyncBranch(branch);
            settings.setSyncEnabled(true);
        });
        sync.connected();
        refreshSync();
    }

    /**
     * "Create on GitHub…": asks for a name, makes a private repository with gh, then connects to it as
     * though its URL had been typed — the branch field and every check on the way are the same.
     */
    private void createSyncRepositoryOnGitHub() {
        if (sync == null || syncCreating) return;
        TextInputDialog ask = new TextInputDialog(DEFAULT_SYNC_REPOSITORY_NAME);
        ask.initOwner(stage);
        ask.setTitle(tr("settings.cat.sync"));
        ask.setHeaderText(tr("dialog.sync.createGithub.header"));
        ask.setContentText(tr("dialog.sync.createGithub.name"));
        Confirm.prompt(ask, tr("dialog.create"));
        String name = ask.showAndWait().map(String::strip).orElse("");
        if (name.isEmpty()) return;
        if (!com.termina.sync.GitHubRepo.isName(name)) {
            showSyncFieldError(tr("settings.sync.createGithub.invalidName"));
            return;
        }
        showSyncFieldError(null);
        syncCreating = true;
        refreshSync();
        sync.createOnGitHub(name, created -> {
            syncCreating = false;
            if (!created.ok()) {
                showSyncFieldError(tr("settings.sync.createGithub.failed", created.error()));
                refreshSync();
                return;
            }
            syncRepository.setText(created.url());
            toggleSyncConnection();
        });
    }

    static final String DEFAULT_SYNC_REPOSITORY_NAME = "termina-sync";

    /** The catalogue key for what stops these fields connecting, or null when they can. */
    static @Nullable String syncFieldProblem(String url, String branch) {
        if (!com.termina.sync.SyncEngine.isUsableUrl(url)) return "settings.sync.repository.invalid";
        if (com.termina.sync.SyncEngine.hasCredentials(url)) return "settings.sync.repository.credentials";
        if (!com.termina.sync.SyncEngine.isUsableBranch(branch)) return "settings.sync.branch.invalid";
        return null;
    }

    /** The pill's word for a phase, as a catalogue key. */
    static String syncPillKey(SettingsSync.Phase phase) {
        return "sync.pill." + phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Puts the sync's state on the page: which button says what, and what the last run did. */
    private void refreshSync() {
        if (syncState == null) return;
        boolean connected = settings.syncEnabled();
        SettingsSync.Phase phase =
                sync == null ? SettingsSync.Phase.OFF : sync.state().phase();
        syncConnect.setText(tr(connected ? "settings.sync.disconnect" : "settings.sync.connect"));
        syncConnect.setDisable(sync == null || syncCreating);
        syncPill.setText(tr(syncPillKey(phase)));
        syncPill.getStyleClass().removeAll("off", "idle", "syncing", "problem");
        syncPill.getStyleClass().add(phase.name().toLowerCase(java.util.Locale.ROOT));
        syncRepository.setDisable(connected || syncCreating);
        syncBranch.setDisable(connected || syncCreating);
        setShown(syncCreateGitHub, sync != null && !connected && sync.gitHubAvailable());
        syncCreateGitHub.setDisable(syncCreating);
        syncState.setText(
                syncCreating
                        ? tr("sync.state.creating")
                        : sync == null ? tr("sync.state.off") : sync.stateText(this::labelOf));
        String account = connected && sync != null ? sync.account() : "";
        syncAccount.setText(account.isEmpty() ? "" : tr("sync.state.account", account));
        setShown(syncAccount, !account.isEmpty());
        boolean active = sync != null && sync.active();
        boolean syncing = phase == SettingsSync.Phase.SYNCING;
        syncNow.setDisable(!active || syncing);
        setShown(syncCancel, syncing);
        com.termina.sync.SyncReport report = sync == null ? null : sync.state().report();
        setShown(
                syncAnyway,
                !syncing && report != null && report.status() == com.termina.sync.SyncReport.Status.NEEDS_CONFIRMATION);
        setShown(
                syncRemoveCredentials,
                connected && com.termina.sync.SyncEngine.hasCredentials(settings.syncRepository()));
        syncInterval.setDisable(!settings.syncAuto());
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static String shortcutName() {
        return com.termina.Os.isMac() ? "Cmd" : "Ctrl";
    }

    // ---------------------------------------------------------------- state

    /** Fills every control from the stored settings without firing their listeners. */
    private void reload() {
        reloading = true;
        try {
            reloadControls();
        } finally {
            reloading = false;
        }
    }

    private void reloadControls() {
        loading = true;
        try {
            ThemeEntry theme = Themes.byId(settings.themeId());
            themeCombo.getItems().setAll(Themes.byDisplayName().keySet());
            themeCombo.setValue(
                    Themes.followsSystem(settings.themeId()) ? Themes.systemDisplayName() : theme.displayName());
            removeTheme.setDisable(theme.isBuiltIn());
            fontCombo.setValue(MonospaceFonts.resolve(settings.fontFamily()));
            fontSize.getValueFactory().setValue(settings.fontSize());
            uiZoom.getValueFactory().setValue(settings.uiZoom());
            scrollback.getValueFactory().setValue(settings.scrollbackLines());
            cursorShape.setValue(settings.cursorShape());
            padding.getValueFactory().setValue(settings.padding());
            altIsMeta.setSelected(settings.altIsMeta());
            copyOnSelect.setSelected(settings.copyOnSelect());
            clipboardWrite.setSelected(settings.clipboardWrite());
            typeAhead.setSelected(settings.typeAhead());
            reloadKeys();
            bell.setSelected(settings.bell());
            notifications.setSelected(settings.notifications());
            confirmClose.setSelected(settings.confirmClose());
            restoreSession.setSelected(settings.restoreSession());
            hideTabBar.setSelected(settings.hideTabBarWhenSingle());
            showTabBar.setSelected(settings.showTabBar());
            showMenuBar.setSelected(settings.showMenuBar());
            menuBarInTitleBar.setSelected(settings.menuBarInTitleBar());
            showScrollBar.setSelected(settings.showScrollBar());
            showStatusBar.setSelected(settings.showStatusBar());
            windowOpacity.setValue(WindowOpacity.clamp(settings.windowOpacity()) * 100);
            language.setValue(settings.uiLanguage());
            // Not under the user's fingers: a change from elsewhere — a sync run, a zoom chord in a
            // terminal window — reloads this window, and a field being typed into keeps its text.
            setUnlessEditing(shellField, settings.shell());
            setUnlessEditing(linkCommandField, settings.linkOpenCommand());
            updateCheck.setSelected(settings.updateCheck());
            setUnlessEditing(syncRepository, settings.syncRepository());
            setUnlessEditing(syncBranch, settings.syncBranch());
            syncAuto.setSelected(settings.syncAuto());
            syncInterval.getValueFactory().setValue(settings.syncIntervalMinutes());
            settingsPath.setText(settings.file().toString());
            refreshSaveError();
        } finally {
            loading = false;
        }
        // Outside the guarded block: it guards itself, and it has to run with the flag in the state
        // it leaves rather than inherit one that is about to be cleared.
        refreshProfileList();
        refreshPreview();
        refreshSync();
    }

    private void refreshSaveError() {
        String error = settings.lastSaveError();
        saveError.setText(error == null ? "" : tr("settings.settingsFile.error", error));
        saveError.setVisible(error != null);
        saveError.setManaged(error != null);
    }

    private void refreshPreview() {
        if (preview == null) return;
        preview.update(
                Themes.byId(settings.themeId()).palette(),
                MonospaceFonts.resolve(settings.fontFamily()),
                settings.fontSize());
    }

    /**
     * A sample of the chosen theme and font.
     *
     * <p>Rendered with the real palette and the real font rather than described in words, because
     * the only useful question about a terminal font is whether it looks right.
     */
    private static final class PalettePreview {
        private final VBox box = new VBox(6);
        private final VBox lines = new VBox(1);
        private final HBox swatches = new HBox(3);

        PalettePreview() {
            box.setPadding(new Insets(10));
            box.getChildren().addAll(lines, swatches);
            box.getStyleClass().add("settings-preview");
        }

        Node node() {
            return box;
        }

        void update(TerminalPalette palette, String family, double size) {
            String bg = toCss(palette.background());
            box.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 6;");

            Color[] ansi = palette.ansi();
            lines.getChildren()
                    .setAll(
                            line(family, size, palette.foreground(), "user@host ~ % ls --color"),
                            coloured(family, size, ansi),
                            line(family, size, ansi[8], "# dim comment    " + "日本語  ✓"));

            swatches.getChildren().clear();
            for (Color color : ansi) {
                Region swatch = new Region();
                swatch.setPrefSize(18, 12);
                swatch.setStyle("-fx-background-color: " + toCss(color) + "; -fx-background-radius: 2;");
                swatches.getChildren().add(swatch);
            }
        }

        private static Label line(String family, double size, Color colour, String text) {
            Label label = new Label(text);
            label.setStyle("-fx-font-family: '" + family + "'; -fx-font-size: " + size + "px; -fx-text-fill: "
                    + toCss(colour) + ";");
            return label;
        }

        private static HBox coloured(String family, double size, Color[] ansi) {
            HBox row = new HBox(0);
            String[] words = {"src", "build", "README.md", "run.sh", "notes.txt", "vendor"};
            int[] colours = {4, 4, 5, 2, 7, 6};
            for (int i = 0; i < words.length; i++) {
                row.getChildren().add(line(family, size, ansi[colours[i]], words[i] + "  "));
            }
            return row;
        }

        private static String toCss(Color color) {
            return String.format(
                    "#%02x%02x%02x",
                    (int) Math.round(color.getRed() * 255), (int) Math.round(color.getGreen() * 255), (int)
                            Math.round(color.getBlue() * 255));
        }
    }
}
