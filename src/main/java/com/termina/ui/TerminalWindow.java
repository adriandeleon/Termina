package com.termina.ui;

import com.termina.config.Settings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/** One window: a menu bar over a tab strip, each tab a terminal with its own shell. */
public final class TerminalWindow {

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");

    /**
     * Whether the menus go to the screen menu bar rather than into the window.
     *
     * <p>{@code -Dtermina.forceInWindowMenuBar=true} makes a Mac behave like the other platforms.
     * Without it the in-window menu bar — the only place the hide setting does anything — could not
     * be seen or tested on the one machine this is developed on.
     */
    private static final boolean SYSTEM_MENU_BAR =
            MAC && !Boolean.getBoolean("termina.forceInWindowMenuBar");

    private final WindowManager windows;
    private final Settings settings;
    private final Stage stage;
    private final TabPane tabs = new TabPane();
    private final List<MenuAction> bindings = new ArrayList<>();

    TerminalWindow(WindowManager windows, Settings settings, Stage stage) {
        this.windows = windows;
        this.settings = settings;
        this.stage = stage;

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        // Disposal is driven off the list rather than off the close button, so that every removal
        // path is covered — the button, Close Tab, the shell exiting, and closing the window. A
        // missed one leaks a PTY process, two pump threads and the emulator thread, per tab.
        tabs.getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) {
                for (Tab removed : change.getRemoved()) {
                    // A reorder is a remove followed by an add. Without this guard, dragging a tab
                    // would dispose the session being dragged — killing the shell and leaving an
                    // empty tab behind, for a gesture that is supposed to change nothing but order.
                    if (reordering) continue;
                    terminalOf(removed).close();
                }
            }
            // Same reason: mid-reorder the list is briefly one short, and for a single-tab window
            // briefly empty, which would close the window out from under the drag.
            if (!reordering && tabs.getTabs().isEmpty() && stage.isShowing()) stage.close();
            applyTabBarVisibility();
        });

        BorderPane root = new BorderPane(tabs);
        menuBar = buildMenuBar();
        root.setTop(menuBar);
        applyMenuBarVisibility();

        Scene scene = new Scene(root, 900, 560);
        Theme theme = Theme.byId(settings.themeId(), Theme.EDITORA_DARK);
        scene.setFill(theme.palette().background());
        var appCss = TerminalWindow.class.getResource("/com/termina/styles/app.css");
        if (appCss != null) scene.getStylesheets().add(appCss.toExternalForm());

        // A scene FILTER, not the menu's accelerators: filters run in the capturing phase, before
        // TerminalView's own filter turns Ctrl+<letter> into a control byte for the shell.
        // Accelerators fire only after an unconsumed bubble, which for those chords never happens.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);

        stage.setScene(scene);
        Icons.applyTo(stage);
        stage.titleProperty().bind(Bindings.createStringBinding(
                () -> {
                    Tab selected = tabs.getSelectionModel().getSelectedItem();
                    return selected == null ? "Termina" : selected.getText();
                },
                tabs.getSelectionModel().selectedItemProperty()));

        tabs.getSelectionModel().selectedItemProperty().addListener((o, old, tab) -> {
            // Focus has to follow the tab or the newly shown terminal silently swallows typing.
            if (tab != null) Platform.runLater(() -> terminalOf(tab).requestFocus());
        });
    }

    /**
     * Whether the menu bar should take up a row in the window.
     *
     * <p>False on macOS whatever the setting says: the menus are in the screen menu bar, and the
     * node only remains in the scene graph because that is what JavaFX forwards from. Leaving it
     * measurable there costs a band of empty chrome above the terminal.
     */
    static boolean menuBarOccupiesSpace(boolean systemMenuBar, boolean showMenuBar) {
        return !systemMenuBar && showMenuBar;
    }

    private void applyMenuBarVisibility() {
        if (menuBar == null) return;
        boolean occupies = menuBarOccupiesSpace(SYSTEM_MENU_BAR, settings.showMenuBar());
        menuBar.getStyleClass().removeAll(COLLAPSED_MENU_BAR);
        if (!occupies) menuBar.getStyleClass().add(COLLAPSED_MENU_BAR);
    }

    private static final String COLLAPSED_MENU_BAR = "collapsed-menu-bar";

    /** Whether the tab strip is worth its row of chrome. */
    static boolean shouldShowTabBar(int tabCount, boolean hideWhenSingle) {
        return !hideWhenSingle || tabCount > 1;
    }

    /**
     * Shows or collapses the tab strip.
     *
     * <p>Collapsing changes how many rows the terminal has, so the layout pass that follows resizes
     * the PTY — the shell learns about it the same way it learns about a window resize.
     */
    private void applyTabBarVisibility() {
        boolean show = shouldShowTabBar(tabs.getTabs().size(), settings.hideTabBarWhenSingle());
        tabs.getStyleClass().removeAll(HIDE_TAB_BAR);
        if (!show) tabs.getStyleClass().add(HIDE_TAB_BAR);
    }

    private static final String HIDE_TAB_BAR = "hide-tab-bar";

    // ---------------------------------------------------------------- tabs

    /** Opens a tab and starts a shell in it. */
    public void openTab() {
        TerminalView terminal = new TerminalView(settings.fontSize());
        applySettingsTo(terminal);
        terminal.setSessionOptions(settings.scrollbackLines(), settings.shell());
        terminal.setOnOpenSettings(() -> windows.showSettings(stage));
        terminal.setOnNewTab(this::openTab);
        terminal.setOnNewWindow(windows::openWindow);

        Tab tab = new Tab();
        tab.setContent(terminal);
        tab.setUserData(terminal);
        // The title goes on a Label graphic rather than on the Tab: a Tab is not a Node, so it has
        // nowhere to attach drag handlers. The label still shows whatever the shell calls itself,
        // which is what makes a row of tabs useful.
        Label title = new Label();
        title.getStyleClass().add("tab-title");
        title.textProperty().bind(terminal.getDisplay().windowTitleProperty());
        tab.setGraphic(title);
        installTabDrag(tab, title);

        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);

        try {
            terminal.start();
        } catch (IOException e) {
            tabs.getTabs().remove(tab);
            reportShellFailure(e);
            return;
        }
        // The shell exiting closes its own tab, not the window — the other tabs are still alive.
        terminal.setOnSessionEnded(() -> Platform.runLater(() -> tabs.getTabs().remove(tab)));
        Platform.runLater(terminal::requestFocus);
    }

    /** Identifies our own drags, so a drop from another application is not mistaken for one. */
    private static final DataFormat TAB_DRAG = new DataFormat("application/x-termina-tab");

    /** The tab being dragged. The Dragboard can only carry serialisable data, and a Tab is not. */
    private Tab draggedTab;

    /**
     * Drag-to-reorder.
     *
     * <p>JavaFX has no tab reordering of its own, so this is the whole gesture: the label is the
     * drag source, the drop side is decided by which half of the target it lands on, and an accent
     * edge shows where it would go.
     */
    private void installTabDrag(Tab tab, Label title) {
        title.setOnDragDetected(e -> {
            if (tabs.getTabs().size() < 2) return;
            draggedTab = tab;
            var board = title.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            // The payload is a marker, not the data: identifying our drag is all it is for.
            content.put(TAB_DRAG, "tab");
            board.setContent(content);
            board.setDragView(title.snapshot(null, null));
            title.getStyleClass().add("tab-dragging");
            e.consume();
        });

        title.setOnDragOver(e -> {
            if (draggedTab == null || e.getGestureSource() == title) return;
            if (!e.getDragboard().hasContent(TAB_DRAG)) return;
            e.acceptTransferModes(TransferMode.MOVE);
            markDropSide(title, e.getX() > title.getWidth() / 2);
            e.consume();
        });

        title.setOnDragExited(e -> clearDropMarks(title));

        title.setOnDragDropped(e -> {
            clearDropMarks(title);
            if (draggedTab == null || !e.getDragboard().hasContent(TAB_DRAG)) return;
            int from = tabs.getTabs().indexOf(draggedTab);
            int over = tabs.getTabs().indexOf(tab);
            boolean after = e.getX() > title.getWidth() / 2;
            moveTab(from, TabReorder.insertIndex(from, over, after, tabs.getTabs().size()));
            e.setDropCompleted(true);
            e.consume();
        });

        title.setOnDragDone(e -> {
            title.getStyleClass().remove("tab-dragging");
            draggedTab = null;
            for (Tab other : tabs.getTabs()) {
                if (other.getGraphic() instanceof Label l) clearDropMarks(l);
            }
        });
    }

    private static void markDropSide(Label title, boolean after) {
        title.getStyleClass().removeAll("tab-drop-before", "tab-drop-after");
        title.getStyleClass().add(after ? "tab-drop-after" : "tab-drop-before");
    }

    private static void clearDropMarks(Label title) {
        title.getStyleClass().removeAll("tab-drop-before", "tab-drop-after");
    }

    /** True while a drag is rewriting the tab list, so removals are not treated as closes. */
    private boolean reordering;

    /**
     * Moves a tab, keeping it selected.
     *
     * <p>Public because the drop handler and the development capture hook both drive it, and
     * because "does reordering preserve the session?" is worth being able to ask directly.
     */
    public void moveTab(int from, int to) {
        if (!TabReorder.isMove(from, to)) return;
        if (from < 0 || from >= tabs.getTabs().size()) return;

        reordering = true;
        try {
            Tab tab = tabs.getTabs().remove(from);
            tabs.getTabs().add(Math.min(to, tabs.getTabs().size()), tab);
            tabs.getSelectionModel().select(tab);
        } finally {
            reordering = false;
        }
        applyTabBarVisibility();
    }

    /** Tab titles in order — for the capture hook, to check a reorder did what was asked. */
    public List<String> tabTitles() {
        List<String> titles = new ArrayList<>();
        for (Tab tab : tabs.getTabs()) titles.add(tab.getText() == null ? label(tab) : tab.getText());
        return titles;
    }

    private static String label(Tab tab) {
        return tab.getGraphic() instanceof Label l ? l.getText() : "";
    }

    public void closeCurrentTab() {
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        if (selected != null) tabs.getTabs().remove(selected);
    }

    /** Shifts the selected tab one place, stopping at the ends rather than wrapping. */
    private void moveSelectedTab(int delta) {
        int from = tabs.getSelectionModel().getSelectedIndex();
        int to = from + delta;
        // Deliberately not wrapping: dragging cannot wrap either, and a tab that jumps from one end
        // to the other reads as a mistake.
        if (from < 0 || to < 0 || to >= tabs.getTabs().size()) return;
        moveTab(from, to);
    }

    private void selectRelativeTab(int delta) {
        int count = tabs.getTabs().size();
        if (count < 2) return;
        int index = tabs.getSelectionModel().getSelectedIndex();
        // Wraps, as every tabbed terminal does.
        tabs.getSelectionModel().select(Math.floorMod(index + delta, count));
    }

    private static TerminalView terminalOf(Tab tab) {
        return (TerminalView) tab.getUserData();
    }

    /** Every live terminal in this window. */
    public List<TerminalView> terminals() {
        List<TerminalView> views = new ArrayList<>();
        for (Tab tab : tabs.getTabs()) views.add(terminalOf(tab));
        return views;
    }

    public TerminalView activeTerminal() {
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        return selected == null ? null : terminalOf(selected);
    }

    public Stage stage() {
        return stage;
    }

    /** Heights of the chrome above the terminal, for diagnosing stray bands. */
    public String layoutReport() {
        javafx.scene.Node header = tabs.lookup(".tab-header-area");
        return "menuBar h=" + (menuBar == null ? "?" : menuBar.getHeight())
                + " managed=" + (menuBar != null && menuBar.isManaged())
                + " visible=" + (menuBar != null && menuBar.isVisible())
                + " | tabHeader h=" + (header == null ? "absent" : header.getBoundsInParent().getHeight())
                + " | tabs styleClass=" + tabs.getStyleClass()
                + " | tabPane y=" + tabs.getBoundsInParent().getMinY();
    }

    private MenuBar menuBar;

    public void show() {
        stage.show();
    }

    public void close() {
        stage.close();
    }

    // ---------------------------------------------------------------- actions

    /**
     * Actions on the active terminal are looked up at invocation, never captured — the menu is
     * built once and the active tab changes underneath it.
     */
    private void withActiveTerminal(java.util.function.Consumer<TerminalView> action) {
        TerminalView terminal = activeTerminal();
        if (terminal != null) action.accept(terminal);
    }

    private MenuBar buildMenuBar() {
        MenuBar bar = new MenuBar();
        // On macOS the menu belongs to the screen, not the window; the focused window's bar wins.
        bar.setUseSystemMenuBar(SYSTEM_MENU_BAR);
        // ...but the node stays in the scene graph and keeps its own padding, which shows up as a
        // band of empty chrome above the terminal. Collapsed in CSS rather than hidden, so the
        // system-menu registration that depends on it being live is untouched.


        Menu file = menu("File",
                register(MenuAction.of("New Tab", MenuAction.appChord(KeyCode.T), this::openTab)),
                register(MenuAction.of("New Window", MenuAction.appChord(KeyCode.N), windows::openWindow)),
                null,
                register(MenuAction.of("Close Tab", MenuAction.appChord(KeyCode.W), this::closeCurrentTab)),
                register(MenuAction.of("Close Window", MenuAction.shiftChord(KeyCode.W), this::close)),
                null,
                register(MenuAction.of("Settings…",
                        new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN),
                        () -> windows.showSettings(stage))));

        Menu edit = menu("Edit",
                register(MenuAction.of("Copy", MenuAction.appChord(KeyCode.C),
                        () -> withActiveTerminal(TerminalView::copySelection))),
                register(MenuAction.of("Paste", MenuAction.appChord(KeyCode.V),
                        () -> withActiveTerminal(TerminalView::paste))),
                register(MenuAction.of("Select All", MenuAction.appChord(KeyCode.A),
                        () -> withActiveTerminal(TerminalView::selectAll))),
                null,
                register(MenuAction.of("Clear Scrollback", MenuAction.appChord(KeyCode.K),
                        () -> withActiveTerminal(TerminalView::clearScrollback))));

        Menu view = menu("View",
                register(MenuAction.of("Zoom In",
                        new KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN),
                        () -> zoom(1))),
                register(MenuAction.of("Zoom Out",
                        new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN),
                        () -> zoom(-1))),
                register(MenuAction.of("Actual Size",
                        new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN),
                        () -> settings.setFontSize(Settings.DEFAULT_FONT_SIZE))),
                null,
                // Hiding it from the menu it lives in is only safe because the right-click menu
                // reaches Settings, which is how it comes back.
                register(MenuAction.of("Hide Menu Bar",
                        new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN,
                                KeyCombination.SHIFT_DOWN),
                        () -> settings.setShowMenuBar(!settings.showMenuBar()))));

        Menu window = menu("Window",
                register(MenuAction.of("Next Tab", MenuAction.shiftChord(KeyCode.CLOSE_BRACKET),
                        () -> selectRelativeTab(1))),
                register(MenuAction.of("Previous Tab", MenuAction.shiftChord(KeyCode.OPEN_BRACKET),
                        () -> selectRelativeTab(-1))),
                null,
                // Dragging is the usual way, but a tab strip that can only be reordered by mouse is
                // unreachable from the keyboard entirely.
                register(MenuAction.of("Move Tab Left",
                        new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN,
                                KeyCombination.SHIFT_DOWN),
                        () -> moveSelectedTab(-1))),
                register(MenuAction.of("Move Tab Right",
                        new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN,
                                KeyCombination.SHIFT_DOWN),
                        () -> moveSelectedTab(1))));

        updateItem = MenuAction.of("Check for Updates…", () -> windows.checkForUpdatesNow(this::report))
                .toMenuItem();
        Menu help = new Menu("Help");
        help.getItems().addAll(
                MenuAction.of("About " + com.termina.AppInfo.NAME, () -> windows.showAbout(stage))
                        .toMenuItem(),
                updateItem);

        // The Help menu is where an available update shows up. Termina has no status bar, which is
        // where Editora puts its badge, and a banner over the terminal would cost a row of the
        // thing the user is actually looking at.
        windows.addUpdateListener(this::refreshUpdateItem);
        refreshUpdateItem();

        bar.getMenus().addAll(file, edit, view, window, help);
        return bar;
    }

    private javafx.scene.control.MenuItem updateItem;

    private void refreshUpdateItem() {
        if (updateItem == null) return;
        var update = windows.availableUpdate();
        if (update == null) {
            updateItem.setText("Check for Updates…");
            updateItem.setOnAction(e -> windows.checkForUpdatesNow(this::report));
        } else {
            updateItem.setText("Version " + update.version() + " is available…");
            updateItem.setOnAction(e -> windows.openReleasePage());
        }
    }

    /**
     * Shows a transient message. With no status bar, an information-only alert is the honest
     * option — it is the only surface that cannot be missed, and this only ever runs in response to
     * the user picking the menu item.
     */
    private void report(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle(com.termina.AppInfo.NAME);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.show();
    }

    /** Records the binding for the scene filter and hands the action back for the menu. */
    private MenuAction register(MenuAction action) {
        if (action.accelerator() != null) bindings.add(action);
        return action;
    }

    /** A null entry becomes a separator. */
    private static Menu menu(String title, MenuAction... actions) {
        Menu menu = new Menu(title);
        for (MenuAction action : actions) {
            menu.getItems().add(action == null ? new SeparatorMenuItem() : action.toMenuItem());
        }
        return menu;
    }

    private void onKeyPressed(KeyEvent e) {
        for (MenuAction binding : bindings) {
            if (binding.matches(e)) {
                binding.action().run();
                e.consume();
                return;
            }
        }
        // Zoom-in reaches here as EQUALS on most layouts, since + is the shifted key.
        if (e.isShortcutDown() && !e.isShiftDown()
                && (e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.ADD)) {
            zoom(1);
            e.consume();
        }
    }

    /** Zoom writes through the settings so it persists and shows up in the settings window. */
    private void zoom(double delta) {
        settings.setFontSize(settings.fontSize() + delta);
    }

    // ---------------------------------------------------------------- settings

    /** Re-applies everything that can change while a session is running, to every tab. */
    public void applySettings() {
        Theme theme = Theme.byId(settings.themeId(), Theme.EDITORA_DARK);
        if (stage.getScene() != null) stage.getScene().setFill(theme.palette().background());
        applyTabBarVisibility();
        applyMenuBarVisibility();
        for (TerminalView terminal : terminals()) applySettingsTo(terminal);
    }

    private void applySettingsTo(TerminalView terminal) {
        Theme theme = Theme.byId(settings.themeId(), Theme.EDITORA_DARK);
        terminal.setPalette(theme.palette());
        terminal.setFontFamily(settings.fontFamily());
        terminal.setFontSize(settings.fontSize());
        terminal.setPreferredCursor(settings.cursorShape());
        terminal.setAltIsMeta(settings.altIsMeta());
        terminal.setBellEnabled(settings.bell());
    }

    private void reportShellFailure(IOException e) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle("Termina");
        alert.setHeaderText("Could not start a shell");
        alert.setContentText(String.valueOf(e.getMessage()));
        alert.showAndWait();
    }
}
