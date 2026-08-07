package com.termina.ui;

import com.termina.config.Settings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
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
import javafx.scene.layout.StackPane;
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

        // The new-tab button floats over the right end of the tab strip rather than being a tab of
        // its own. A sentinel "+" tab would pollute every count and index in this class — tab
        // disposal, reordering, the hide-when-single rule, next/previous — each of which would then
        // need to know it is not a real tab. This keeps the model honest.
        newTabButton.setFocusTraversable(false);
        newTabButton.getStyleClass().add("new-tab-button");
        // A shaped Region, not a "+" character. The tab's close button is a shape JavaFX draws from
        // CSS, and a font glyph beside it never matches: different size, different weight, and a
        // baseline rather than a centre to align to.
        javafx.scene.layout.Region plus = new javafx.scene.layout.Region();
        plus.getStyleClass().add("tab-strip-glyph");
        newTabButton.setGraphic(plus);
        newTabButton.setOnAction(e -> openTab());
        Tooltip newTabTip = new Tooltip("New Tab (" + MenuAction.appChord(KeyCode.T).getDisplayText() + ")");
        newTabTip.setShowDelay(Duration.millis(400));
        newTabButton.setTooltip(newTabTip);
        StackPane.setAlignment(newTabButton, Pos.TOP_RIGHT);
        StackPane tabHost = new StackPane(tabs, newTabButton);

        tabs.widthProperty().addListener((o, was, now) -> applyTabWidths());
        tabs.getTabs().addListener((ListChangeListener<Tab>) c -> applyTabWidths());

        BorderPane root = new BorderPane(tabHost);
        menuBar = buildMenuBar();
        root.setTop(menuBar);
        applyMenuBarVisibility();

        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double width = WindowGeometry.fit(settings.windowWidth(), DEFAULT_WIDTH, screen.getWidth());
        double height = WindowGeometry.fit(settings.windowHeight(), DEFAULT_HEIGHT, screen.getHeight());
        restoredWidth = width;
        restoredHeight = height;
        Scene scene = new Scene(root, width, height);
        Theme theme = Theme.byId(settings.themeId(), Theme.EDITORA_DARK);
        scene.setFill(theme.palette().background());
        var appCss = TerminalWindow.class.getResource("/com/termina/styles/app.css");
        if (appCss != null) scene.getStylesheets().add(appCss.toExternalForm());
        Fonts.installUiFont(scene);

        // A scene FILTER, not the menu's accelerators: filters run in the capturing phase, before
        // TerminalView's own filter turns Ctrl+<letter> into a control byte for the shell.
        // Accelerators fire only after an unconsumed bubble, which for those chords never happens.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);

        stage.setScene(scene);
        Icons.applyTo(stage);
        if (settings.windowMaximized()) stage.setMaximized(true);
        trackGeometry();
        tabs.getSelectionModel().selectedItemProperty().addListener((o, old, tab) -> {
            bindTitleTo(tab);
            // Focus has to follow the tab or the newly shown terminal silently swallows typing.
            if (tab != null) Platform.runLater(() -> terminalOf(tab).requestFocus());
        });
        bindTitleTo(null);
    }

    /**
     * Follows the selected tab's title, as macOS Terminal does.
     *
     * <p>Bound to the terminal's own title property rather than to the tab, so it tracks the shell
     * live rather than only at the moment of selection. It cannot read {@code Tab.getText()} at
     * all: the title moved onto a Label graphic when tabs became draggable — a Tab is not a Node
     * and has nowhere to attach drag handlers — which left {@code getText()} null and the window
     * title empty.
     */
    private void bindTitleTo(Tab tab) {
        stage.titleProperty().unbind();
        if (tab == null) {
            stage.setTitle(com.termina.AppInfo.NAME);
            return;
        }
        stage.titleProperty().bind(terminalOf(tab).getDisplay().windowTitleProperty());
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

    /**
     * Applies the menu bar's visibility. The two platforms need different mechanisms.
     *
     * <p>Under a <b>system menu bar</b> the node has to stay live — that is what JavaFX forwards to
     * the screen bar — so it is only collapsed to zero height, and nothing paints in the window
     * because the menus are not there.
     *
     * <p><b>Everywhere else</b> the bar really is in the window, and hiding it has to remove it
     * from painting as well as layout. CSS is not enough for that, and relying on it was a bug:
     * {@code visibility: hidden} never applied (the node still reported {@code isVisible() == true})
     * and a zero-height Region does not clip its children, so the menu buttons carried on painting
     * over the terminal's first rows while occupying no space of their own.
     */
    private void applyMenuBarVisibility() {
        if (menuBar == null) return;
        boolean occupies = menuBarOccupiesSpace(SYSTEM_MENU_BAR, settings.showMenuBar());
        if (SYSTEM_MENU_BAR) {
            menuBar.getStyleClass().removeAll(COLLAPSED_MENU_BAR);
            menuBar.getStyleClass().add(COLLAPSED_MENU_BAR);
            menuBar.setVisible(true);
            menuBar.setManaged(true);
            return;
        }
        menuBar.getStyleClass().removeAll(COLLAPSED_MENU_BAR);
        menuBar.setVisible(occupies);
        menuBar.setManaged(occupies);
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
        // The button lives in the strip, so it goes with it — and unmanaged as well as invisible,
        // for the same reason the menu bar had to be: a hidden-but-managed node still paints.
        newTabButton.setVisible(show);
        newTabButton.setManaged(show);
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
        tab.setContextMenu(buildTabMenu(tab));
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

    /**
     * The menu for right-clicking a tab header.
     *
     * <p>Built per tab and attached with {@code Tab.setContextMenu}, which is JavaFX's own hook for
     * this — so it appears on the header rather than on the terminal, and the tab it acts on is the
     * one that was clicked rather than whichever happens to be selected.
     */
    private ContextMenu buildTabMenu(Tab tab) {
        MenuItem closeOthers = tabItem("Close Other Tabs", MenuIcons.closeOthers(),
                () -> closeOtherTabs(tab));
        MenuItem closeRight = tabItem("Close Tabs to the Right", MenuIcons.closeRight(),
                () -> closeTabsToTheRight(tab));
        MenuItem moveLeft = tabItem("Move Tab Left", MenuIcons.arrowLeft(),
                () -> moveTabBy(tab, -1));
        MenuItem moveRight = tabItem("Move Tab Right", MenuIcons.arrowRight(),
                () -> moveTabBy(tab, 1));

        ContextMenu menu = new ContextMenu(
                tabItem("New Tab", MenuIcons.newTab(), this::openTab),
                new SeparatorMenuItem(),
                tabItem("Close Tab", MenuIcons.close(), () -> tabs.getTabs().remove(tab)),
                closeOthers,
                closeRight,
                new SeparatorMenuItem(),
                moveLeft,
                moveRight);
        // Recomputed per show: how many tabs there are, and where this one sits, both change long
        // after the menu was built.
        menu.setOnShowing(e -> {
            int index = tabs.getTabs().indexOf(tab);
            int count = tabs.getTabs().size();
            closeOthers.setDisable(count < 2);
            closeRight.setDisable(index < 0 || index >= count - 1);
            moveLeft.setDisable(index <= 0);
            moveRight.setDisable(index < 0 || index >= count - 1);
        });
        return menu;
    }

    private static MenuItem tabItem(String text, javafx.scene.Node icon, Runnable action) {
        MenuItem item = new MenuItem(text, icon);
        item.setOnAction(e -> action.run());
        return item;
    }

    private void closeOtherTabs(Tab keep) {
        for (Tab other : new ArrayList<>(tabs.getTabs())) {
            if (other != keep) tabs.getTabs().remove(other);
        }
    }

    private void closeTabsToTheRight(Tab from) {
        int index = tabs.getTabs().indexOf(from);
        if (index < 0) return;
        // Backwards, so removing one does not shift the indices of those still to be removed.
        for (int i = tabs.getTabs().size() - 1; i > index; i--) {
            tabs.getTabs().remove(i);
        }
    }

    private void moveTabBy(Tab tab, int delta) {
        int from = tabs.getTabs().indexOf(tab);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= tabs.getTabs().size()) return;
        moveTab(from, to);
    }

    /** The clicked tab's menu, for the development capture hook. */
    public javafx.scene.Scene showTabMenuForCapture(int index, double screenX, double screenY) {
        if (index < 0 || index >= tabs.getTabs().size()) return null;
        ContextMenu menu = tabs.getTabs().get(index).getContextMenu();
        if (menu == null) return null;
        menu.show(stage, screenX, screenY);
        return menu.getScene();
    }

    /** Selects a tab by index. For the development capture hook. */
    public void selectTab(int index) {
        if (index >= 0 && index < tabs.getTabs().size()) tabs.getSelectionModel().select(index);
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
    /** The terminal of the selected tab, or null when there is none. */
    public TerminalView selectedTerminal() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab == null ? null : terminalOf(tab);
    }

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
    /** The menu bar as text, so a capture run can assert on what is in it. */
    public String menuReport() {
        StringBuilder out = new StringBuilder();
        for (Menu m : menuBar.getMenus()) {
            out.append(m.getText()).append('[');
            for (MenuItem item : m.getItems()) {
                out.append(item instanceof SeparatorMenuItem ? "-" : item.getText()).append('|');
            }
            out.append("] ");
        }
        return out.toString().trim();
    }

    public String layoutReport() {
        javafx.scene.Node header = tabs.lookup(".tab-header-area");
        return "menuBar h=" + (menuBar == null ? "?" : menuBar.getHeight())
                + " boundsH=" + (menuBar == null ? "?" : menuBar.getBoundsInParent().getHeight())
                + " managed=" + (menuBar != null && menuBar.isManaged())
                + " visible=" + (menuBar != null && menuBar.isVisible())
                + " | tabHeader h=" + (header == null ? "absent" : header.getBoundsInParent().getHeight())
                + " | tabs styleClass=" + tabs.getStyleClass()
                + " | tabPane y=" + tabs.getBoundsInParent().getMinY()
                + " | tabWidths=" + tabHeaderWidths()
                + " | glyphs " + glyphBoxes()
                + " | scrollBar " + (selectedTerminal() == null ? "none" : selectedTerminal().scrollBarReport());
    }

    /**
     * Where the tab-strip glyphs actually sit, in scene coordinates.
     *
     * <p>Reported rather than eyeballed: two icons being a couple of pixels out of line is exactly
     * the kind of thing that looks like nothing in a screenshot and wrong on a real screen.
     */
    private String glyphBoxes() {
        StringBuilder out = new StringBuilder();
        javafx.scene.Node close = tabs.lookup(".tab-close-button");
        javafx.scene.Node plus = newTabButton.lookup(".tab-strip-glyph");
        out.append("close=").append(box(close)).append(" plus=").append(box(plus));
        Tooltip tip = newTabButton.getTooltip();
        out.append(" plusTip=\"").append(tip == null ? "" : tip.getText()).append('"');
        return out.toString();
    }

    private static String box(javafx.scene.Node n) {
        if (n == null) return "absent";
        javafx.geometry.Bounds b = n.localToScene(n.getBoundsInLocal());
        return "%dx%d@cy%.1f"
                .formatted(Math.round(b.getWidth()), Math.round(b.getHeight()), b.getCenterY());
    }

    /** Actual rendered width of each tab header, to check the sizing rather than assume it. */
    private String tabHeaderWidths() {
        var headers = tabs.lookupAll(".tab");
        StringBuilder out = new StringBuilder("[");
        for (javafx.scene.Node n : headers) {
            out.append(Math.round(n.getBoundsInParent().getWidth())).append(' ');
        }
        return out.append("] stripW=").append(Math.round(tabs.getWidth())).toString();
    }

    private MenuBar menuBar;

    private final Button newTabButton = new Button();

    /**
     * Space kept clear at the right end of the strip for the new-tab button, and the per-tab
     * padding and close button that sit outside the width JavaFX lets us set.
     */
    /** Size a window opens at before one has ever been recorded. */
    private static final double DEFAULT_WIDTH = 900;

    private static final double DEFAULT_HEIGHT = 560;

    /** The window's size ignoring maximization, tracked live and written out on close. */
    private double restoredWidth;

    private double restoredHeight;

    private static final double NEW_TAB_RESERVED = 40;

    /**
     * Measured, not guessed: JavaFX renders a tab at the width set here plus about 17px of its own
     * padding. At 38 the strip left a visible gap at seven tabs — the arithmetic tiled correctly
     * against the wrong constant.
     */
    private static final double TAB_CHROME = 17;

    /** Sizes every tab so the strip fills the window, shrinking as tabs are added. */
    /**
     * Gives the new-tab button the height of the tab strip, so its glyph centres on the same line as
     * every tab's close button.
     *
     * <p>The button floats in a StackPane over the strip and anchors to the top, which left it eight
     * pixels high of the close buttons. Bound to the header rather than set to a number because the
     * strip's height follows the font size and the theme.
     */
    private void syncNewTabButtonHeight() {
        if (newTabButton.prefHeightProperty().isBound()) return;
        if (tabs.lookup(".tab-header-area") instanceof javafx.scene.layout.Region header) {
            newTabButton.prefHeightProperty().bind(header.heightProperty());
        }
    }

    private void applyTabWidths() {
        syncNewTabButtonHeight();
        double width = TabLayout.tabWidth(
                tabs.getWidth(), tabs.getTabs().size(), NEW_TAB_RESERVED, TAB_CHROME);
        // Both bounds, or JavaFX sizes each tab to its label and they no longer tile.
        tabs.setTabMinWidth(width);
        tabs.setTabMaxWidth(width);
    }

    /**
     * Remembers the window's size so the next launch opens at it.
     *
     * <p>Only the un-maximized size is recorded. A maximized window reports the screen's size, and
     * storing that would mean un-maximizing next time restores to full screen — the restore button
     * would appear to do nothing. Whether it *was* maximized is stored separately.
     *
     * <p>Saved when the window closes rather than as it is dragged: a write per resize event would
     * be hundreds of writes for one drag.
     */
    private void trackGeometry() {
        // The SCENE's size, not the stage's. The stage's height includes the title bar while the
        // scene's does not, and the size is restored by constructing a Scene — so saving the outer
        // height and restoring it as the inner one grows the window by a title bar on every single
        // launch. Measured: set to 700, saved as 728.
        Scene scene = stage.getScene();
        scene.widthProperty().addListener((o, was, now) -> {
            if (!stage.isMaximized()) restoredWidth = now.doubleValue();
        });
        scene.heightProperty().addListener((o, was, now) -> {
            if (!stage.isMaximized()) restoredHeight = now.doubleValue();
        });
        // Hiding, not hidden: WindowManager's own onHidden handler ends the app when the last
        // window goes, and the write has to have happened by then.
        stage.setOnHiding(e -> settings.setWindowGeometry(restoredWidth, restoredHeight, stage.isMaximized()));
    }

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

        List<MenuAction> viewItems = new java.util.ArrayList<>(List.of(
                register(MenuAction.of("Zoom In",
                        new KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN),
                        () -> zoom(1))),
                register(MenuAction.of("Zoom Out",
                        new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN),
                        () -> zoom(-1))),
                register(MenuAction.of("Actual Size",
                        new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN),
                        () -> settings.setFontSize(Settings.DEFAULT_FONT_SIZE)))));
        // Omitted entirely on macOS, where the menus live in the screen menu bar and there is
        // nothing in the window to hide. The Settings checkbox is disabled-with-a-reason instead,
        // because it has room to explain itself; a menu item has none, and one that does nothing
        // when clicked is worse than one that is not there. Skipping it also leaves Shift+Cmd+M
        // free rather than bound to a no-op.
        if (!SYSTEM_MENU_BAR) {
            viewItems.add(null);
            // Hiding it from the menu it lives in is only safe because the right-click menu
            // reaches Settings, which is how it comes back.
            viewItems.add(register(MenuAction.of("Hide Menu Bar",
                    new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN,
                            KeyCombination.SHIFT_DOWN),
                    () -> settings.setShowMenuBar(!settings.showMenuBar()))));
        }
        Menu view = menu("View", viewItems.toArray(MenuAction[]::new));

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
        terminal.setScrollBarEnabled(settings.showScrollBar());
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
