package com.termina.ui;

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
import javafx.scene.control.Slider;
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

import com.termina.config.Settings;
import com.termina.i18n.Messages;

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
    private enum Category {
        APPEARANCE(Group.GENERAL, "settings.cat.appearance"),
        TERMINAL(Group.GENERAL, "settings.cat.terminal"),
        PROFILES(Group.GENERAL, "settings.cat.profiles"),
        ADVANCED(Group.SYSTEM, "settings.cat.advanced");

        final Group group;
        private final String key;

        Category(Group group, String key) {
            this.group = group;
            this.key = key;
        }

        String title() {
            return tr(key);
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

    public SettingsWindow(Settings settings, com.termina.shell.ShellProfiles profiles) {
        this.settings = settings;
        this.profiles = profiles;
        build();
    }

    private final com.termina.shell.ShellProfiles profiles;

    /** The window's scene, for the development capture hook. */
    /** Types into the search box as the user would, so a capture can reach any page. */
    public void searchForCapture(String query) {
        search.setText(query);
    }

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
        stage.setTitle(tr("settings.title", com.termina.AppInfo.NAME));

        for (Category category : Category.values()) {
            pages.put(category, buildPage(category));
        }

        search.setPromptText(tr("settings.search"));
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

        pageHost.setPadding(new Insets(4, 0, 0, PAGE_GUTTER));
        pageHost.setAlignment(Pos.TOP_LEFT);

        Button reset = new Button(tr("settings.reset"));
        reset.setOnAction(e -> {
            settings.resetToDefaults();
            reload();
        });
        Button close = new Button(tr("settings.close"));
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
        // Tall enough for the longest page, but never taller than the screen — a settings window
        // whose buttons are below the bottom edge is worse than one that scrolls. Every row added
        // since the first version has pushed this up; the clamp is what stops that becoming a
        // problem on a laptop.
        javafx.geometry.Rectangle2D visible = javafx.stage.Screen.getPrimary().getVisualBounds();
        Scene scene =
                new Scene(root, Math.min(1153, visible.getWidth() * 0.95), Math.min(985, visible.getHeight() * 0.92));
        // A scene stylesheet, not part of the theme: it must survive the runtime
        // setUserAgentStylesheet swap that changing the theme performs.
        var css = SettingsWindow.class.getResource("/com/termina/styles/settings.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        Fonts.installUiFont(scene);
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
                setText(group.title().toUpperCase(Locale.ROOT));
                getStyleClass().add("settings-group-header");
                setMouseTransparent(true);
                setDisable(false);
            } else {
                Category category = (Category) item;
                setText("    " + category.title());
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

    /** The page's margin from the sidebar on one side and from the scrollbar on the other. */
    private static final double PAGE_GUTTER = 14;

    private Region buildPage(Category category) {
        VBox page = new VBox(4);
        page.setFillWidth(true);
        switch (category) {
            case APPEARANCE -> buildAppearance(page);
            case TERMINAL -> buildTerminal(page);
            case PROFILES -> buildProfiles(page);
            case ADVANCED -> buildAdvanced(page);
        }
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
    private CheckBox confirmClose;
    private ComboBox<String> language;
    private Slider windowOpacity;
    private Label windowOpacityValue;
    private TextField shellField;
    private TextField linkCommandField;
    private Label settingsPath;

    private void buildAppearance(VBox page) {
        VBox theme = section(page, tr("settings.section.theme"));

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
        row(
                Category.APPEARANCE,
                theme,
                tr("settings.theme"),
                tr("settings.theme.desc"),
                themeCombo,
                "theme colour color dark light appearance palette");

        VBox font = section(page, tr("settings.section.font"));

        fontCombo = new ComboBox<>();
        fontCombo.setPrefWidth(240);
        fontCombo.getItems().setAll(MonospaceFonts.available());
        fontCombo.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            settings.setFontFamily(value);
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
        fontSize.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            settings.setFontSize(value);
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
            settings.setHideTabBarWhenSingle(value);
        });
        row(
                Category.APPEARANCE,
                tabsSection,
                tr("settings.hideTabBar"),
                tr("settings.hideTabBar.desc"),
                hideTabBar,
                "tab bar tabs hide single chrome strip header");

        VBox windowSection = section(page, tr("settings.section.window"));

        showMenuBar = new CheckBox();
        showMenuBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            settings.setShowMenuBar(value);
        });
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
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

        showScrollBar = new CheckBox();
        showScrollBar.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            settings.setShowScrollBar(value);
        });
        row(
                Category.APPEARANCE,
                windowSection,
                tr("settings.showScrollBar"),
                tr("settings.showScrollBar.desc"),
                showScrollBar,
                "scroll bar scrollbar scrollback history gutter");

        windowOpacity = new Slider(WindowOpacity.MIN * 100, WindowOpacity.MAX * 100, 100);
        windowOpacity.setPrefWidth(180);
        windowOpacity.setMajorTickUnit(10);
        windowOpacity.setMinorTickCount(1);
        windowOpacity.setSnapToTicks(true);
        windowOpacityValue = new Label("100%");
        windowOpacityValue.setMinWidth(44);
        // Live while dragging, but written only when the drag ends: every write saves the file and
        // broadcasts a settings change to every terminal in every window, which is not something to
        // do per pixel of travel.
        windowOpacity.valueProperty().addListener((o, old, value) -> {
            windowOpacityValue.setText(WindowOpacity.percent(value.doubleValue() / 100) + "%");
            if (loading || windowOpacity.isValueChanging()) return;
            settings.setWindowOpacity(value.doubleValue() / 100);
        });
        windowOpacity.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing && !loading) settings.setWindowOpacity(windowOpacity.getValue() / 100);
        });
        HBox opacityControl = new HBox(8, windowOpacity, windowOpacityValue);
        opacityControl.setAlignment(Pos.CENTER_RIGHT);
        row(
                Category.APPEARANCE,
                windowSection,
                tr("settings.windowOpacity"),
                tr("settings.windowOpacity.desc"),
                opacityControl,
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
            settings.setUiLanguage(value == null ? "" : value);
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
        rows.add(new Row(Category.APPEARANCE, preview.node(), "preview sample colours font", previewSection));
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
            public Settings.CursorShape fromString(String display) {
                return null;
            }
        });
        cursorShape.setPrefWidth(160);
        cursorShape.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            settings.setCursorShape(value);
        });
        row(
                Category.TERMINAL,
                display,
                tr("settings.cursorShape"),
                tr("settings.cursorShape.desc"),
                cursorShape,
                "cursor caret shape block underline bar");

        bell = new CheckBox();
        bell.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            settings.setBell(value);
        });
        row(Category.TERMINAL, display, tr("settings.bell"), tr("settings.bell.desc"), bell, "bell alert flash sound");

        VBox session = section(page, tr("settings.section.session"));

        confirmClose = new CheckBox();
        confirmClose.selectedProperty().addListener((o, old, value) -> {
            if (loading) return;
            settings.setConfirmClose(value);
        });
        row(
                Category.TERMINAL,
                session,
                tr("settings.confirmClose"),
                tr("settings.confirmClose.desc"),
                confirmClose,
                "close confirm running program prompt ask tab");

        scrollback = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                Settings.MIN_SCROLLBACK, Settings.MAX_SCROLLBACK, Settings.DEFAULT_SCROLLBACK, 500));
        scrollback.setEditable(true);
        scrollback.setPrefWidth(130);
        scrollback.valueProperty().addListener((o, old, value) -> {
            if (loading || value == null) return;
            settings.setScrollbackLines(value);
        });
        row(
                Category.TERMINAL,
                session,
                tr("settings.scrollback"),
                tr("settings.scrollback.desc"),
                scrollback,
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
        row(
                Category.TERMINAL,
                session,
                tr("settings.shell"),
                tr("settings.shell.desc"),
                shellField,
                "shell zsh bash program command login");

        VBox links = section(page, tr("settings.section.links"));

        linkCommandField = new TextField();
        linkCommandField.setPromptText("/path/to/editor {file}:{line}");
        linkCommandField.setPrefWidth(260);
        linkCommandField.focusedProperty().addListener((o, was, focused) -> {
            if (!focused && !loading) settings.setLinkOpenCommand(linkCommandField.getText());
        });
        linkCommandField.setOnAction(e -> {
            if (!loading) settings.setLinkOpenCommand(linkCommandField.getText());
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
            settings.setAltIsMeta(value);
        });
        row(
                Category.TERMINAL,
                input,
                tr("settings.altIsMeta"),
                tr("settings.altIsMeta.desc"),
                altIsMeta,
                "alt option meta escape readline compose");
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
            profiles.setDefaultProfileId(value.id());
            refreshProfileList();
        });
        row(
                Category.PROFILES,
                defaults,
                tr("settings.defaultProfile"),
                tr("settings.defaultProfile.desc"),
                defaultProfile,
                "default profile shell new tab startup powershell cmd wsl bash zsh");

        VBox list = section(page, tr("settings.section.profiles"));

        profileList = new ListView<>();
        // Tall enough for the shells a normal machine finds plus a few of the user's own. At 200 the
        // list scrolled at seven entries, which on macOS it reaches from discovery alone — so the
        // profiles somebody had actually written were the ones below the fold, on the page whose
        // whole purpose is editing them. The page has the room.
        profileList.setPrefHeight(340);
        profileList.setMinHeight(140);
        profileList.setCellFactory(l -> new ProfileCell(true));
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
        profileCommand.setPromptText("wsl.exe -d Ubuntu");
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
            profiles.setHidden(selected.id(), !value);
            refreshProfileList();
        });

        profileSource = new Label();
        profileSource.getStyleClass().add("settings-row-description");
        profileSource.setWrapText(true);

        Button addProfile = new Button(tr("settings.profileAdd"));
        addProfile.setOnAction(e -> addUserProfile());
        removeProfile = new Button(tr("settings.profileRemove"));
        removeProfile.setOnAction(e -> removeSelectedProfile());

        HBox buttons = new HBox(8, addProfile, removeProfile);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox form = new VBox(
                6,
                labelled(tr("settings.profileName"), profileName),
                labelled(tr("settings.profileCommand"), profileCommand),
                labelled(tr("settings.profileDirectory"), profileDirectory),
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
                tr("settings.section.profiles") + " profile shell command wsl powershell cmd bash zsh fish add remove",
                list));

        Label note = new Label(tr("settings.profiles.note"));
        note.getStyleClass().add("settings-row-description");
        note.setWrapText(true);
        list.getChildren().add(note);
    }

    private static VBox labelled(String title, Region control) {
        Label name = new Label(title);
        name.getStyleClass().add("settings-row-title");
        return new VBox(2, name, control);
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
        } finally {
            loading = was;
        }
        editing = profile;
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

        List<com.termina.shell.Profile> user = new ArrayList<>(profiles.userProfiles());
        boolean changed = false;
        for (int i = 0; i < user.size(); i++) {
            if (!user.get(i).id().equals(selected.id())) continue;
            com.termina.shell.Profile edited = user.get(i)
                    .withName(profileName.getText().trim())
                    .withCommandLine(profileCommand.getText())
                    .withWorkingDirectory(profileDirectory.getText());
            if (edited.equals(user.get(i))) return;
            user.set(i, edited);
            changed = true;
            break;
        }
        if (!changed) return;
        profiles.setUserProfiles(user);
        refreshProfileList(selected.id());
    }

    private void addUserProfile() {
        com.termina.shell.Profile created = profiles.newUserProfile(tr("settings.profileNewName"))
                .withName(tr("settings.profileNewName"))
                // Seeded with the system shell rather than left empty: a profile with no command is
                // not saved, so an empty one would vanish the moment the list was rebuilt.
                .withCommandLine(profiles.systemProfile().commandLine());
        List<com.termina.shell.Profile> user = new ArrayList<>(profiles.userProfiles());
        user.add(created);
        profiles.setUserProfiles(user);
        refreshProfileList(created.id());
        profileName.requestFocus();
        profileName.selectAll();
    }

    private void removeSelectedProfile() {
        com.termina.shell.Profile selected = editing;
        if (selected == null || !selected.isEditable()) return;
        List<com.termina.shell.Profile> user = new ArrayList<>(profiles.userProfiles());
        user.removeIf(profile -> profile.id().equals(selected.id()));
        profiles.setUserProfiles(user);
        refreshProfileList();
    }

    private void refreshProfileList() {
        com.termina.shell.Profile selected = profileList.getSelectionModel().getSelectedItem();
        refreshProfileList(selected == null ? null : selected.id());
    }

    /** Rebuilds the list and the default picker, keeping the selection on {@code selectId}. */
    private void refreshProfileList(String selectId) {
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
        row(
                Category.ADVANCED,
                files,
                tr("settings.settingsFile"),
                tr("settings.settingsFile.desc"),
                settingsPath,
                "settings file path properties config location");

        VBox diagnostics = section(page, tr("settings.section.diagnostics"));
        Button showDebugLog = new Button(tr("settings.debugLog.button"));
        showDebugLog.setOnAction(e -> {
            if (debugLog == null) debugLog = new DebugLogWindow(stage);
            debugLog.show();
        });
        row(
                Category.ADVANCED,
                diagnostics,
                tr("settings.debugLog"),
                tr("settings.debugLog.desc"),
                showDebugLog,
                "debug log diagnostics errors warnings crash trace troubleshoot");
    }

    /** Built on first use: most sessions never open it. */
    private DebugLogWindow debugLog;

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
            confirmClose.setSelected(settings.confirmClose());
            hideTabBar.setSelected(settings.hideTabBarWhenSingle());
            showMenuBar.setSelected(settings.showMenuBar());
            showScrollBar.setSelected(settings.showScrollBar());
            windowOpacity.setValue(WindowOpacity.clamp(settings.windowOpacity()) * 100);
            language.setValue(settings.uiLanguage());
            shellField.setText(settings.shell());
            linkCommandField.setText(settings.linkOpenCommand());
            settingsPath.setText(settings.file().toString());
        } finally {
            loading = false;
        }
        // Outside the guarded block: it guards itself, and it has to run with the flag in the state
        // it leaves rather than inherit one that is about to be cleared.
        refreshProfileList();
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
