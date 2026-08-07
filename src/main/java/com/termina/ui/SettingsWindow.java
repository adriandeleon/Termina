package com.termina.ui;

import com.termina.config.Settings;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

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

    /** Sidebar groups, in display order. */
    private enum Group {
        GENERAL("General"),
        SYSTEM("System");

        final String title;

        Group(String title) {
            this.title = title;
        }
    }

    /** Pages, in sidebar order within their group. */
    private enum Category {
        APPEARANCE(Group.GENERAL, "Appearance"),
        TERMINAL(Group.GENERAL, "Terminal"),
        ADVANCED(Group.SYSTEM, "Advanced");

        final Group group;
        final String title;

        Category(Group group, String title) {
            this.group = group;
            this.title = title;
        }
    }

    /** One searchable control row. */
    private record Row(Category category, Node node, String keywords, VBox section) {}

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

    private PalettePreview preview;

    public SettingsWindow(Settings settings) {
        this.settings = settings;
        build();
    }

    /** The window's scene, for the development capture hook. */
    public Scene scene() {
        return stage.getScene();
    }

    public void show(Window owner) {
        if (owner != null && stage.getOwner() == null) {
            stage.initOwner(owner);
            stage.initModality(Modality.NONE);
        }
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
        reload();
        stage.show();
    }

    // ---------------------------------------------------------------- shell

    private void build() {
        stage.setTitle("Termina Settings");

        for (Category category : Category.values()) {
            pages.put(category, buildPage(category));
        }

        search.setPromptText("Search settings");
        search.textProperty().addListener((o, old, value) -> filter(value));
        VBox.setMargin(search, new Insets(0, 0, 8, 0));

        sidebar.setPrefWidth(190);
        sidebar.setMinWidth(150);
        sidebar.getStyleClass().add("settings-sidebar");
        sidebar.setCellFactory(list -> new SidebarCell());
        sidebar.getItems().setAll(sidebarItems());
        sidebar.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            if (item instanceof Category category) showPage(category);
        });

        pageHost.setPadding(new Insets(4, 0, 0, 14));
        pageHost.setAlignment(Pos.TOP_LEFT);

        Button reset = new Button("Reset to Defaults");
        reset.setOnAction(e -> {
            settings.resetToDefaults();
            reload();
        });
        Button close = new Button("Close");
        close.setOnAction(e -> stage.hide());
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

        // 20% larger than the original 760x520: the Appearance page overflowed, and a page of
        // preferences that scrolls hides the rows below the fold.
        // Grown from the original 760x520 over several passes; a page of preferences that scrolls
        // hides the rows below the fold, which is the opposite of what a settings window is for.
        Scene scene = new Scene(root, 1153, 789);
        // A scene stylesheet, not part of the theme: it must survive the runtime
        // setUserAgentStylesheet swap that changing the theme performs.
        var css = SettingsWindow.class.getResource("/com/termina/styles/settings.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setScene(scene);
        Icons.applyTo(stage);

        selectFirstVisible();
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
                setDisable(false);
                setMouseTransparent(false);
                return;
            }
            if (item instanceof Group group) {
                setText(group.title.toUpperCase(Locale.ROOT));
                getStyleClass().add("settings-group-header");
                setMouseTransparent(true);
                setDisable(false);
            } else {
                Category category = (Category) item;
                setText("    " + category.title);
                getStyleClass().add("settings-sidebar-item");
                setMouseTransparent(false);
                boolean dimmed = hiddenCategories.contains(category);
                setDisable(dimmed);
                if (dimmed) getStyleClass().add("settings-dimmed");
            }
        }
    }

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

    /** Case-insensitive substring over a row's own keywords. */
    static boolean matches(String query, String keywords) {
        if (query == null || query.isBlank()) return true;
        return keywords.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private void filter(String query) {
        hiddenCategories.clear();
        Set<Category> withHits = new LinkedHashSet<>();

        for (Row row : rows) {
            boolean visible = matches(query, row.keywords());
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

        Object selected = sidebar.getSelectionModel().getSelectedItem();
        if (!(selected instanceof Category category) || hiddenCategories.contains(category)) {
            selectFirstVisible();
        }
    }

    // ---------------------------------------------------------------- page building

    private Region buildPage(Category category) {
        VBox page = new VBox(4);
        page.setFillWidth(true);
        switch (category) {
            case APPEARANCE -> buildAppearance(page);
            case TERMINAL -> buildTerminal(page);
            case ADVANCED -> buildAdvanced(page);
        }
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
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
    private Node row(Category category, VBox section, String title, String description, Region control, String keywords) {
        Label name = new Label(title);
        name.getStyleClass().add("settings-row-title");
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
        rows.add(new Row(category, line, title + " " + description + " " + keywords, section));
        return line;
    }

    // ---------------------------------------------------------------- pages

    private ComboBox<String> themeCombo;
    private ComboBox<String> fontCombo;
    private Spinner<Double> fontSize;
    private Spinner<Integer> scrollback;
    private ComboBox<Settings.CursorShape> cursorShape;
    private CheckBox altIsMeta;
    private CheckBox bell;
    private CheckBox hideTabBar;
    private CheckBox showMenuBar;
    private CheckBox showScrollBar;
    private TextField shellField;
    private Label settingsPath;

    private void buildAppearance(VBox page) {
        VBox theme = section(page, "Theme");

        themeCombo = new ComboBox<>();
        themeCombo.getItems().setAll(Theme.byDisplayName().keySet());
        themeCombo.setPrefWidth(200);
        themeCombo.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            Theme chosen = Theme.byDisplayName().get(value);
            if (chosen != null) {
                settings.setThemeId(chosen.id());
                refreshPreview();
            }
        });
        row(Category.APPEARANCE, theme, "Colour theme",
                "Applies to this window and the terminal palette.", themeCombo,
                "theme colour color dark light appearance palette");

        VBox font = section(page, "Font");

        fontCombo = new ComboBox<>();
        fontCombo.setPrefWidth(240);
        fontCombo.getItems().setAll(MonospaceFonts.available());
        fontCombo.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            settings.setFontFamily(value);
            refreshPreview();
        });
        row(Category.APPEARANCE, font, "Font family",
                "Monospace faces only — the renderer places glyphs on a fixed cell grid.",
                fontCombo, "font family typeface monospace");

        fontSize = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                Settings.MIN_FONT_SIZE, Settings.MAX_FONT_SIZE, Settings.DEFAULT_FONT_SIZE, 1));
        fontSize.setEditable(true);
        fontSize.setPrefWidth(110);
        fontSize.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            settings.setFontSize(value);
            refreshPreview();
        });
        row(Category.APPEARANCE, font, "Font size",
                "Also adjustable at any time with " + shortcutName() + " and the + / - keys.",
                fontSize, "font size zoom scale");

        VBox tabsSection = section(page, "Tabs");

        hideTabBar = new CheckBox();
        hideTabBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            settings.setHideTabBarWhenSingle(value);
        });
        row(Category.APPEARANCE, tabsSection, "Hide the tab bar with a single tab",
                "The row is reclaimed for the terminal, so the shell gains a line.",
                hideTabBar, "tab bar tabs hide single chrome strip header");

        VBox windowSection = section(page, "Window");

        showMenuBar = new CheckBox();
        showMenuBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            settings.setShowMenuBar(value);
        });
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
        // Disabled rather than hidden on macOS: a setting that silently does nothing is worse than
        // one that says why it cannot.
        showMenuBar.setDisable(mac);
        row(Category.APPEARANCE, windowSection, "Show the menu bar",
                mac
                        ? "macOS puts the menus in the screen menu bar, so there is nothing in the "
                                + "window to hide."
                        : "Hiding it keeps every command reachable by keyboard, and Settings stays "
                                + "on the right-click menu.",
                showMenuBar, "menu bar menubar hide chrome window");

        showScrollBar = new CheckBox();
        showScrollBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            settings.setShowScrollBar(value);
        });
        row(Category.APPEARANCE, windowSection, "Show a scrollbar",
                "It takes a column of width rather than floating over the text. The wheel scrolls "
                        + "the history either way.",
                showScrollBar, "scroll bar scrollbar scrollback history gutter");

        VBox previewSection = section(page, "Preview");
        preview = new PalettePreview();
        previewSection.getChildren().add(preview.node());
        rows.add(new Row(Category.APPEARANCE, preview.node(), "preview sample colours font", previewSection));
    }

    private void buildTerminal(VBox page) {
        VBox display = section(page, "Display");

        cursorShape = new ComboBox<>();
        cursorShape.getItems().setAll(Settings.CursorShape.values());
        cursorShape.setPrefWidth(160);
        cursorShape.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            settings.setCursorShape(value);
        });
        row(Category.TERMINAL, display, "Cursor shape",
                "A program can still request its own shape while it is running.",
                cursorShape, "cursor caret shape block underline bar");

        bell = new CheckBox();
        bell.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            settings.setBell(value);
        });
        row(Category.TERMINAL, display, "Visual bell",
                "Flashes the screen instead of making a sound.", bell, "bell alert flash sound");

        VBox session = section(page, "Session");

        scrollback = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                Settings.MIN_SCROLLBACK, Settings.MAX_SCROLLBACK, Settings.DEFAULT_SCROLLBACK, 500));
        scrollback.setEditable(true);
        scrollback.setPrefWidth(130);
        scrollback.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            settings.setScrollbackLines(value);
        });
        row(Category.TERMINAL, session, "Scrollback lines",
                "Held in memory. Takes effect in the next session.", scrollback,
                "scrollback history lines buffer memory");

        shellField = new TextField();
        shellField.setPromptText("Leave empty to use $SHELL");
        shellField.setPrefWidth(260);
        shellField.focusedProperty().addListener((o, was, focused) -> {
            if (!focused && !loading) settings.setShell(shellField.getText());
        });
        shellField.setOnAction(e -> {
            if (!loading) settings.setShell(shellField.getText());
        });
        row(Category.TERMINAL, session, "Shell",
                "Started as a login shell. Takes effect in the next session.", shellField,
                "shell zsh bash program command login");

        VBox input = section(page, "Input");

        altIsMeta = new CheckBox();
        altIsMeta.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            settings.setAltIsMeta(value);
        });
        row(Category.TERMINAL, input, "Use Alt as Meta",
                "On for readline's M-b and M-f. Off lets Alt compose characters, which is what "
                        + "macOS Option normally does.",
                altIsMeta, "alt option meta escape readline compose");
    }

    private void buildAdvanced(VBox page) {
        VBox files = section(page, "Files");

        settingsPath = new Label();
        settingsPath.getStyleClass().add("settings-path");
        settingsPath.setWrapText(true);
        row(Category.ADVANCED, files, "Settings file",
                "A plain properties file; safe to edit by hand while Termina is closed.",
                settingsPath, "settings file path properties config location");
    }

    private static String shortcutName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac") ? "Cmd" : "Ctrl";
    }

    // ---------------------------------------------------------------- state

    /** Fills every control from the stored settings without firing their listeners. */
    private void reload() {
        loading = true;
        try {
            Theme theme = Theme.byId(settings.themeId(), Theme.EDITORA_DARK);
            themeCombo.setValue(theme.displayName());
            fontCombo.setValue(MonospaceFonts.resolve(settings.fontFamily()));
            fontSize.getValueFactory().setValue(settings.fontSize());
            scrollback.getValueFactory().setValue(settings.scrollbackLines());
            cursorShape.setValue(settings.cursorShape());
            altIsMeta.setSelected(settings.altIsMeta());
            bell.setSelected(settings.bell());
            hideTabBar.setSelected(settings.hideTabBarWhenSingle());
            showMenuBar.setSelected(settings.showMenuBar());
            showScrollBar.setSelected(settings.showScrollBar());
            shellField.setText(settings.shell());
            settingsPath.setText(settings.file().toString());
        } finally {
            loading = false;
        }
        refreshPreview();
    }

    private void refreshPreview() {
        if (preview == null) return;
        preview.update(
                Theme.byId(settings.themeId(), Theme.EDITORA_DARK).palette(),
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
            lines.getChildren().setAll(
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
            label.setStyle("-fx-font-family: '" + family + "'; -fx-font-size: " + size
                    + "px; -fx-text-fill: " + toCss(colour) + ";");
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
                    (int) Math.round(color.getRed() * 255),
                    (int) Math.round(color.getGreen() * 255),
                    (int) Math.round(color.getBlue() * 255));
        }
    }
}
