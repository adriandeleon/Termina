package com.termina.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import com.termina.config.Settings;
import com.termina.process.RunningProgram;

import static com.termina.i18n.Messages.tr;

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
    private static final boolean SYSTEM_MENU_BAR = MAC && !Boolean.getBoolean("termina.forceInWindowMenuBar");

    private final WindowManager windows;
    private final Settings settings;
    private final Stage stage;
    private final TabPane tabs = new TabPane();
    private final List<MenuAction> bindings = new ArrayList<>();

    /** Everything the palette can run, in menu order. */
    private final List<MenuAction> commands = new ArrayList<>();

    private CommandPalette palette;

    TerminalWindow(WindowManager windows, Settings settings, Stage stage) {
        this.windows = windows;
        this.settings = settings;
        this.stage = stage;

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        // The scene hands initial focus to the first focus-traversable node it can find, and a
        // TabPane is one by default. That is a race against the requestFocus below, and on a cold
        // first launch — where the JVM has classes to load and a shell to start before the tab
        // exists — the TabPane wins it and every keystroke goes to the tab strip, in a window that
        // looks completely ready. Opening a second tab appeared to "fix" it only because by then
        // the tab strip already had focus to give away.
        //
        // Nothing in the chrome should be a traversal target anyway: Tab belongs to the shell (the
        // view consumes it), switching tabs has its own chords, and the new-tab button is already
        // excluded above. That leaves the terminal as the only candidate, which removes the race
        // rather than trying to win it. A click on a tab header still focuses the TabPane —
        // requestFocus ignores this flag — and the selection listener hands it straight back.
        tabs.setFocusTraversable(false);
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
        Tooltip newTabTip =
                new Tooltip(tr("tooltip.newTab", MenuAction.appChord(KeyCode.T).getDisplayText()));
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

        // A StackPane over the whole window so the palette has somewhere to be. It is empty until
        // the palette opens, so it costs a node and nothing else.
        StackPane overlay = new StackPane(root);
        palette = new CommandPalette(overlay, this::focusActiveTerminal);

        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double width = WindowGeometry.fit(settings.windowWidth(), DEFAULT_WIDTH, screen.getWidth());
        double height = WindowGeometry.fit(settings.windowHeight(), DEFAULT_HEIGHT, screen.getHeight());
        restoredWidth = width;
        restoredHeight = height;
        Scene scene = new Scene(overlay, width, height);
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
        stage.setOpacity(WindowOpacity.clamp(settings.windowOpacity()));
        trackGeometry();
        stage.setOnCloseRequest(e -> {
            if (!confirmClose(new ArrayList<>(tabs.getTabs()))) e.consume();
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((o, old, tab) -> {
            bindTitleTo(tab);
            // Focus has to follow the tab or the newly shown terminal silently swallows typing.
            if (tab != null) Platform.runLater(() -> terminalOf(tab).requestFocus());
        });
        bindTitleTo(null);
        installFocusGuard(scene);
    }

    /** True while a reclaim is already queued, so a failed one cannot spin. */
    private boolean reclaimPending;

    /**
     * Hands focus back to the terminal whenever nothing else has a claim on it.
     *
     * <p>The one-path-at-a-time approach does not converge here. The tab strip was the path that
     * shipped, but a click on the *already selected* tab header takes focus without changing the
     * selection, so the listener above never runs; the same is true of the strip's empty space, a
     * drag that reorders nothing, and anything else in the chrome that calls {@code requestFocus}.
     * They are indistinguishable to the user — a window that looks ready and swallows typing — and
     * the report that prompted this could not be reproduced, which is exactly the shape of a set of
     * rare paths rather than one common one.
     */
    private void installFocusGuard(Scene scene) {
        scene.focusOwnerProperty().addListener((o, was, now) -> {
            TerminalView terminal = activeTerminal();
            boolean ownerIsTerminal = terminal != null && now == terminal;
            if (!FocusGuard.shouldReclaim(terminal != null, ownerIsTerminal, overlayWantsKeys(terminal))) return;
            // Deferred, because this runs inside the focus change that provoked it, and queued at
            // most once: if the terminal cannot take focus, retrying on our own signal would spin.
            if (reclaimPending) return;
            reclaimPending = true;
            Platform.runLater(() -> {
                reclaimPending = false;
                focusActiveTerminal();
            });
        });
    }

    /** Whether something on screen is entitled to the keyboard instead of the terminal. */
    private boolean overlayWantsKeys(TerminalView terminal) {
        if (palette != null && palette.isShowing()) return true;
        // A context menu is a popup that dismisses when focus moves, so reclaiming under it would
        // make right-click unusable rather than merely rude.
        if (terminal != null && terminal.isContextMenuShowing()) return true;
        for (Menu menu : menuBar.getMenus()) {
            if (menu.isShowing()) return true;
        }
        return false;
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
     * Whether the View menu shows zoom as one row of buttons rather than as plain items.
     *
     * <p>False under a system menu bar, and that is not a matter of taste. The row is a {@link
     * javafx.scene.control.CustomMenuItem}, and macOS's menus are drawn by AppKit, which cannot
     * render a JavaFX node — so {@code MenuBarSkin} refuses {@code useSystemMenuBar} for the
     * <em>whole</em> bar if any menu anywhere in it holds one, printing "MenuBar ignored property
     * useSystemMenuBar because menus contain CustomMenuItem" to stderr and falling back to the
     * in-window bar. Which this window collapses to zero height on macOS, on the assumption that
     * the screen bar has the menus. The two together left a Mac with no menus at all: nothing in
     * the screen bar, nothing in the window, and the only ways to any of it were the chords, the
     * palette and the right-click menu.
     *
     * <p>The row survives everywhere it can actually be drawn. The right-click menu keeps it on
     * every platform — it is a window popup JavaFX draws itself, not a screen menu.
     */
    static boolean showsZoomRow(boolean systemMenuBar) {
        return !systemMenuBar;
    }

    /**
     * Whether to offer showing and hiding the menu bar at all.
     *
     * <p>Two menus ask this — the View menu, for its Hide item, and the right-click menu, for its
     * checkbox — and they must agree: an app that offers the toggle in one place and not the other
     * reads as a bug in whichever place you looked first. Under a system menu bar there is nothing
     * in the window to hide, and an entry that does nothing when clicked is worse than no entry.
     */
    static boolean offersMenuBarToggle(boolean systemMenuBar) {
        return !systemMenuBar;
    }

    /**
     * Whether JavaFX will really hand these menus to the screen bar, rather than what we asked for.
     *
     * <p>{@code MenuBarSkin} silently declines {@code useSystemMenuBar} — a warning on stderr, no
     * API — when any menu in the bar holds a {@link javafx.scene.control.CustomMenuItem}, because
     * AppKit draws those menus and cannot render a JavaFX node. Asked rather than assumed because
     * the answer decides whether the in-window bar is collapsed: collapsing it after JavaFX has
     * declined leaves the window with no menus at all, which is the bug this came from. A rogue
     * custom item should cost a Mac its screen menus, not all of them.
     *
     * <p>Separators are the one custom item JavaFX itself allows, and {@link
     * javafx.scene.control.SeparatorMenuItem} extends CustomMenuItem, so it has to be excluded here
     * too — every menu below has one.
     */
    static boolean menusFitASystemMenuBar(List<? extends MenuItem> items) {
        for (MenuItem item : items) {
            if (item instanceof Menu submenu) {
                if (!menusFitASystemMenuBar(submenu.getItems())) return false;
            } else if (item instanceof javafx.scene.control.CustomMenuItem && !(item instanceof SeparatorMenuItem)) {
                return false;
            }
        }
        return true;
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
        boolean systemBar = SYSTEM_MENU_BAR && menusFitASystemMenuBar(menuBar.getMenus());
        boolean occupies = menuBarOccupiesSpace(systemBar, settings.showMenuBar());
        if (systemBar) {
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
        // The strip appearing is what creates the close buttons, so this is where to catch them.
        if (show) installCloseTooltips();
    }

    private static final String HIDE_TAB_BAR = "hide-tab-bar";

    /** Marks a close button whose tooltip is already on, so a retry cannot install a second. */
    private static final String CLOSE_TIP_INSTALLED = "termina.closeTooltip";

    /** Pulses to wait for the skin to build the headers before giving up on them. */
    private static final int CLOSE_TIP_ATTEMPTS = 20;

    /**
     * Puts a tooltip on each tab's close button.
     *
     * <p>The button belongs to the TabPane's skin, not to us — there is no {@code Tab} API for it.
     * The obvious route, {@code tab.getStyleableNode()}, returns <b>null</b> here, so the buttons
     * are found by looking up the style class on the pane itself; they are indistinguishable for
     * this purpose anyway, since every one of them says the same thing. They are {@code StackPane}s
     * rather than controls, so the tooltip is installed on the node rather than set on it.
     *
     * <p>Retried because the skin does not build a header in the pulse the tab is added, and
     * because a new tab brings a new button with it.
     */
    private void installCloseTooltips() {
        installCloseTooltips(0);
    }

    private void installCloseTooltips(int attempt) {
        java.util.Set<javafx.scene.Node> buttons = tabs.lookupAll(".tab-close-button");
        for (javafx.scene.Node close : buttons) {
            if (close.getProperties().putIfAbsent(CLOSE_TIP_INSTALLED, Boolean.TRUE) != null) continue;
            Tooltip tip = new Tooltip(
                    tr("tooltip.closeTab", MenuAction.appChord(KeyCode.W).getDisplayText()));
            tip.setShowDelay(Duration.millis(400));
            Tooltip.install(close, tip);
        }
        // Fewer buttons than tabs means a header is still to be built — but only while the strip
        // is shown. Hidden for a single tab, no header exists and none is coming, so retrying to
        // the budget every time would be twenty pulses spent on a button nobody can hover.
        boolean stripShown = shouldShowTabBar(tabs.getTabs().size(), settings.hideTabBarWhenSingle());
        if (stripShown && buttons.size() < tabs.getTabs().size() && attempt < CLOSE_TIP_ATTEMPTS) {
            Platform.runLater(() -> installCloseTooltips(attempt + 1));
        }
    }

    /**
     * Shows a tab's directory on hover, and stops showing it when there is none.
     *
     * <p>Attached and detached rather than left in place with empty text: a Tooltip bound to "" is
     * still a tooltip, and hovering produces a small empty box — which reads as something broken
     * rather than as nothing to say. Windows has no {@code ProcessCwd}, so that is the state there
     * for every tab, not an edge case.
     */
    private void installDirectoryTooltip(Label label, javafx.beans.property.StringProperty directory) {
        Tooltip tip = new Tooltip();
        tip.setShowDelay(Duration.millis(400));
        tip.textProperty().bind(directory);
        directory.addListener((o, was, now) -> applyDirectoryTooltip(label, tip, now));
        applyDirectoryTooltip(label, tip, directory.get());
    }

    private static void applyDirectoryTooltip(Label label, Tooltip tip, String directory) {
        label.setTooltip(directory == null || directory.isBlank() ? null : tip);
    }

    /** The active tab's hover text, or "" — for the capture hook. */
    public String tabTooltipReport() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab == null || !(tab.getGraphic() instanceof Label label)) return "";
        Tooltip tip = label.getTooltip();
        return tip == null ? "" : tip.getText();
    }

    /** How many close buttons carry the tooltip — for the capture hook. */
    public String closeTooltipReport() {
        int withTip = 0;
        for (javafx.scene.Node close : tabs.lookupAll(".tab-close-button")) {
            if (close.getProperties().containsKey(CLOSE_TIP_INSTALLED)) withTip++;
        }
        return "closeTooltips=" + withTip + "/" + tabs.getTabs().size();
    }

    // ---------------------------------------------------------------- tabs

    /** Opens a tab and starts a shell in it. */
    public void openTab() {
        openTab(com.termina.pty.LaunchOptions.ofShell(settings.shell()).withWorkingDirectory(currentDirectory()));
    }

    /**
     * Where a new tab should start: wherever the current one is.
     *
     * <p>Starting every tab in the home directory means retyping the `cd` that got you here, which
     * is why every other terminal inherits instead. Read from the shell process rather than from a
     * shell that would have to be asked — the same read the tab titles use.
     *
     * <p>Blank when there is no tab to inherit from, or on a platform that cannot say, and
     * {@link com.termina.pty.ShellLauncher#workingDirectory} falls back to home.
     */
    private String currentDirectory() {
        TerminalView terminal = activeTerminal();
        if (terminal == null || terminal.getSession() == null) return "";
        return com.termina.pty.ProcessCwd.of(terminal.getSession().pid()).orElse("");
    }

    /**
     * Opens a tab with explicit launch options.
     *
     * <p>Used for the first tab of the first window, which is the only one the command line applies
     * to. A second tab running `-e vim` again — or reopening in a directory the user has since
     * navigated away from — is not what anybody means by it.
     */
    public void openTab(com.termina.pty.LaunchOptions options) {
        TerminalView terminal = new TerminalView(settings.fontSize());
        applySettingsTo(terminal);
        terminal.setSessionOptions(settings.scrollbackLines(), options);
        terminal.setOnOpenSettings(() -> windows.showSettings(stage));
        terminal.setOnNewTab(this::openTab);
        terminal.setOnNewWindow(windows::openWindow);
        // The same three actions the View menu runs, not copies of them: zoom lives in the settings,
        // which every tab and window shares, so a view cannot do this for itself.
        terminal.setZoomActions(zoomActions());
        // The command is read at each click rather than captured, so changing it in the settings
        // applies to the next link without every open tab having to be re-wired.
        terminal.setLinkActions(new LinkOpener(windows::openLink, settings::linkOpenCommand, this::reportOpenFailed));
        // Left unwired under a system menu bar, which is how the item stays out of the menu there.
        if (offersMenuBarToggle(SYSTEM_MENU_BAR)) {
            terminal.setMenuBarToggle(settings::showMenuBar, () -> settings.setShowMenuBar(!settings.showMenuBar()));
        }

        Tab tab = new Tab();
        tab.setContent(terminal);
        tab.setUserData(terminal);
        // The title goes on a Label graphic rather than on the Tab: a Tab is not a Node, so it has
        // nowhere to attach drag handlers. It binds to the *tab* title, which is the shell's own
        // title if it set one and otherwise just the directory's name — a path would not fit, and
        // JavaFX ellipsises from the end, keeping exactly the leading part every tab has in common.
        Label title = new Label();
        title.getStyleClass().add("tab-title");
        title.textProperty().bind(terminal.getDisplay().tabTitleProperty());
        // What the label had to leave out. The tab shows a name; the hover shows the path it is a
        // name for — and when a program has set a title, the path is the only place left to say
        // where that program is running.
        installDirectoryTooltip(title, terminal.getDisplay().tabTooltipProperty());
        tab.setGraphic(title);
        tab.setContextMenu(buildTabMenu(tab));
        installCloseTooltips();
        // The close button is JavaFX's own and does not come through closeTab(); consuming the
        // request is the only way to stop it.
        tab.setOnCloseRequest(e -> {
            if (!confirmClose(List.of(tab))) e.consume();
        });
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
            moveTab(
                    from,
                    TabReorder.insertIndex(from, over, after, tabs.getTabs().size()));
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
        MenuItem closeOthers = tabItem(tr("menu.closeOtherTabs"), MenuIcons.closeOthers(), () -> closeOtherTabs(tab));
        MenuItem closeRight =
                tabItem(tr("menu.closeTabsToTheRight"), MenuIcons.closeRight(), () -> closeTabsToTheRight(tab));
        MenuItem moveLeft = tabItem(tr("menu.moveTabLeft"), MenuIcons.arrowLeft(), () -> moveTabBy(tab, -1));
        MenuItem moveRight = tabItem(tr("menu.moveTabRight"), MenuIcons.arrowRight(), () -> moveTabBy(tab, 1));

        ContextMenu menu = new ContextMenu(
                tabItem(tr("menu.newTab"), MenuIcons.newTab(), this::openTab),
                new SeparatorMenuItem(),
                tabItem(tr("menu.closeTab"), MenuIcons.close(), () -> closeTab(tab)),
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
        List<Tab> doomed = new ArrayList<>(tabs.getTabs());
        doomed.remove(keep);
        closeTabs(doomed);
    }

    private void closeTabsToTheRight(Tab from) {
        int index = tabs.getTabs().indexOf(from);
        if (index < 0) return;
        closeTabs(
                new ArrayList<>(tabs.getTabs().subList(index + 1, tabs.getTabs().size())));
    }

    /**
     * Closes a tab, having asked first if something is running in it.
     *
     * <p>Every deliberate close goes through here — the X, the menu item, the chord, both bulk
     * items — because a guard on one of them is a guard the other four walk around. The two
     * removals that do <em>not</em> are the ones where nothing is being ended: a reorder, which is
     * a remove and an add, and a shell that exited on its own.
     */
    private void closeTab(Tab tab) {
        if (confirmClose(List.of(tab))) tabs.getTabs().remove(tab);
    }

    /** One question for the whole set: closing eight tabs should not ask eight times. */
    private void closeTabs(List<Tab> doomed) {
        if (doomed.isEmpty() || !confirmClose(doomed)) return;
        for (Tab tab : doomed) tabs.getTabs().remove(tab);
    }

    /**
     * Asks before ending programs, and only then.
     *
     * <p>A tab always has a shell, so the question is whether the shell has children — an idle
     * prompt has none. Asking on every close regardless would be the kind of confirmation people
     * learn to dismiss without reading, which is worse than not asking.
     */
    private boolean confirmClose(List<Tab> doomed) {
        if (!settings.confirmClose()) return true;
        List<String> running = new ArrayList<>();
        for (Tab tab : doomed) {
            TerminalView terminal = terminalOf(tab);
            if (terminal == null || terminal.getSession() == null) continue;
            running.addAll(RunningProgram.in(terminal.getSession().pid()));
        }
        if (running.isEmpty()) return true;
        return closeConfirmer.test(running, doomed.size());
    }

    /**
     * Answers the close prompt. Replaced by the capture hook, which cannot click a modal dialog —
     * and a run that opened one would block the FX thread until the harness timed out, which is
     * indistinguishable from a hang.
     */
    private java.util.function.BiPredicate<List<String>, Integer> closeConfirmer = this::askBeforeClosing;

    /** @param answer what the prompt should return, for the capture hook */
    public void setCloseAnswerForCapture(boolean answer) {
        closeConfirmer = (running, tabCount) -> {
            System.out.println("[capture] close prompt for " + running + " -> " + (answer ? "close" : "cancel"));
            return answer;
        };
    }

    private boolean askBeforeClosing(List<String> running, int tabCount) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(com.termina.AppInfo.NAME);
        alert.setHeaderText(tabCount == 1 ? tr("dialog.closeRunning") : tr("dialog.closeRunningTabs"));
        alert.setContentText(runningSummary(running));
        ButtonType close = new ButtonType(tr("dialog.closeAnyway"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, close);
        // Cancel is the default, so dismissing the dialog by any route keeps the program alive.
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(close)).setDefaultButton(false);
        return alert.showAndWait().orElse(cancel) == close;
    }

    /** Names a few, then counts: a dialog listing forty processes is not telling anyone anything. */
    private static String runningSummary(List<String> running) {
        if (running.size() <= RunningProgram.MAX_NAMED) {
            return tr("dialog.closeRunningDetail", String.join(", ", running));
        }
        List<String> named = running.subList(0, RunningProgram.MAX_NAMED);
        return tr("dialog.closeRunningMore", String.join(", ", named), running.size() - RunningProgram.MAX_NAMED);
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
        if (index >= 0 && index < tabs.getTabs().size())
            tabs.getSelectionModel().select(index);
    }

    public void closeCurrentTab() {
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        if (selected != null) closeTab(selected);
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

    /**
     * A menu item as the report names it.
     *
     * <p>A {@link javafx.scene.control.CustomMenuItem} is a row of controls with no text of its
     * own, and printing its null was a report that said less the more the menu did.
     */
    private static String labelOf(MenuItem item) {
        if (item instanceof SeparatorMenuItem) return "-";
        if (item.getText() != null) return item.getText();
        if (item instanceof javafx.scene.control.CustomMenuItem custom && custom.getContent() != null) {
            return "<" + String.join(" ", custom.getContent().getStyleClass()) + ">";
        }
        return "<custom>";
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

    /**
     * Who owns keyboard focus, and whether that is the terminal.
     *
     * <p>Worth reporting because nothing else in the capture harness can see it: input is driven
     * either straight into the PTY or as events fired at the view, both of which bypass focus
     * routing entirely. A window where every keystroke goes somewhere else looks identical to a
     * working one in a screenshot and in every other line of this report.
     */
    public String focusReport() {
        javafx.scene.Scene scene = stage.getScene();
        javafx.scene.Node owner = scene == null ? null : scene.getFocusOwner();
        TerminalView active = activeTerminal();
        return "focusOwner=" + (owner == null ? "none" : owner.getClass().getSimpleName())
                + " isTerminal=" + (owner != null && owner == active)
                + " terminalFocused=" + (active != null && active.isFocused())
                + " stageFocused=" + stage.isFocused();
    }

    /** Heights of the chrome above the terminal, for diagnosing stray bands. */
    /** The menu bar as text, so a capture run can assert on what is in it. */
    public String menuReport() {
        StringBuilder out = new StringBuilder();
        for (Menu m : menuBar.getMenus()) {
            out.append(m.getText()).append('[');
            for (MenuItem item : m.getItems()) {
                out.append(labelOf(item)).append('|');
            }
            out.append("] ");
        }
        return out.toString().trim();
    }

    public String layoutReport() {
        javafx.scene.Node header = tabs.lookup(".tab-header-area");
        javafx.geometry.Rectangle2D screenBounds =
                javafx.stage.Screen.getPrimary().getBounds();
        return "fullScreen=" + stage.isFullScreen()
                + " stage=" + Math.round(stage.getX()) + "," + Math.round(stage.getY())
                + " " + Math.round(stage.getWidth()) + "x" + Math.round(stage.getHeight())
                + " screen=" + Math.round(screenBounds.getWidth()) + "x" + Math.round(screenBounds.getHeight())
                + " scene=" + Math.round(stage.getScene().getWidth()) + "x"
                + Math.round(stage.getScene().getHeight())
                + " screens=" + javafx.stage.Screen.getScreens().size()
                + " menuBar h=" + (menuBar == null ? "?" : menuBar.getHeight())
                + " boundsH="
                + (menuBar == null ? "?" : menuBar.getBoundsInParent().getHeight())
                + " managed=" + (menuBar != null && menuBar.isManaged())
                + " visible=" + (menuBar != null && menuBar.isVisible())
                + " | tabHeader h="
                + (header == null ? "absent" : header.getBoundsInParent().getHeight())
                + " | tabs styleClass=" + tabs.getStyleClass()
                + " | tabPane y=" + tabs.getBoundsInParent().getMinY()
                + " | tabWidths=" + tabHeaderWidths()
                + " | glyphs " + glyphBoxes()
                + " | scrollBar "
                + (selectedTerminal() == null ? "none" : selectedTerminal().scrollBarReport());
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
        out.append(" opacity=").append(stage.getOpacity());
        return out.toString();
    }

    private static String box(javafx.scene.Node n) {
        if (n == null) return "absent";
        javafx.geometry.Bounds b = n.localToScene(n.getBoundsInLocal());
        return "%dx%d@cy%.1f".formatted(Math.round(b.getWidth()), Math.round(b.getHeight()), b.getCenterY());
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
        double width = TabLayout.tabWidth(tabs.getWidth(), tabs.getTabs().size(), NEW_TAB_RESERVED, TAB_CHROME);
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

    /**
     * Closes the window at the user's request, asking first if anything is running.
     *
     * <p>Not {@link #closeForShutdown()}: that runs from {@code App.stop()}, after the decision to
     * quit has already been taken and while the toolkit is stopping. A modal dialog there is asking
     * a question nobody can act on, in a place where {@code showAndWait} may never return.
     */
    public void close() {
        if (confirmClose(new ArrayList<>(tabs.getTabs()))) stage.close();
    }

    /** Teardown. Ends everything without asking, because the answer is no longer available. */
    public void closeForShutdown() {
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

        Menu file = menu(
                tr("menu.file"),
                register(MenuAction.of(tr("menu.newTab"), MenuAction.appChord(KeyCode.T), this::openTab)),
                register(MenuAction.of(tr("menu.newWindow"), MenuAction.appChord(KeyCode.N), windows::openWindow)),
                null,
                register(MenuAction.of(tr("menu.closeTab"), MenuAction.appChord(KeyCode.W), this::closeCurrentTab)),
                register(MenuAction.of(tr("menu.closeWindow"), MenuAction.shiftChord(KeyCode.W), this::close)),
                null,
                register(MenuAction.of(
                        tr("menu.settings"),
                        new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN),
                        () -> windows.showSettings(stage))));

        Menu edit = menu(
                tr("menu.edit"),
                register(MenuAction.of(
                        tr("menu.copy"),
                        MenuAction.appChord(KeyCode.C),
                        () -> withActiveTerminal(TerminalView::copySelection))),
                register(MenuAction.of(
                        tr("menu.paste"),
                        MenuAction.appChord(KeyCode.V),
                        () -> withActiveTerminal(TerminalView::paste))),
                register(MenuAction.of(
                        tr("menu.selectAll"),
                        MenuAction.appChord(KeyCode.A),
                        () -> withActiveTerminal(TerminalView::selectAll))),
                null,
                register(MenuAction.of(
                        tr("menu.clearScrollback"),
                        MenuAction.appChord(KeyCode.K),
                        () -> withActiveTerminal(TerminalView::clearScrollback))));

        // The four view actions, built once and shown one of two ways. Where the row can be drawn
        // the View menu shows it instead of four items, and these keep their chords and their place
        // in the palette through command(), which is what records a command with no item of its own.
        // Under a system menu bar they are the items — see showsZoomRow.
        MenuAction zoomIn = MenuAction.of(
                tr("menu.zoomIn"), new KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN), () -> zoom(1));
        MenuAction zoomOut = MenuAction.of(
                tr("menu.zoomOut"),
                new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN),
                () -> zoom(-1));
        MenuAction actualSize = MenuAction.of(
                tr("menu.actualSize"),
                new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN),
                this::resetZoom);
        MenuAction fullScreen = MenuAction.of(tr("menu.fullScreen"), fullScreenChord(), this::toggleFullScreen);
        List<MenuAction> viewActions = List.of(zoomIn, zoomOut, actualSize, fullScreen);
        // Only the row's actions need recording here. As items they reach the palette the ordinary
        // way, by being in a menu, and recording them twice would list each of them twice.
        if (showsZoomRow(SYSTEM_MENU_BAR)) viewActions.forEach(this::command);
        else viewActions.forEach(this::register);

        // Chords and palette entries, but no menu items: nine rows would be most of the Window
        // menu, and all but the first few dead whenever fewer tabs are open.
        for (int number = 1; number <= TAB_CHORD_COUNT; number++) {
            int selected = number;
            KeyCode digit = KeyCode.valueOf("DIGIT" + number);
            String title = number == TAB_CHORD_COUNT ? tr("menu.goToLastTab") : tr("menu.goToTab", number);
            command(MenuAction.of(title, MenuAction.tabChord(digit), () -> selectTabByNumber(selected)));
        }

        List<MenuAction> viewItems = new java.util.ArrayList<>();
        if (!showsZoomRow(SYSTEM_MENU_BAR)) {
            // Full Screen last and on its own, which is where macOS's own View menus put it.
            viewItems.add(zoomIn);
            viewItems.add(zoomOut);
            viewItems.add(actualSize);
            viewItems.add(null);
            viewItems.add(fullScreen);
        }
        // Omitted entirely on macOS, where the menus live in the screen menu bar and there is
        // nothing in the window to hide. The Settings checkbox is disabled-with-a-reason instead,
        // because it has room to explain itself; a menu item has none, and one that does nothing
        // when clicked is worse than one that is not there. Skipping it also leaves Shift+Cmd+M
        // free rather than bound to a no-op.
        if (offersMenuBarToggle(SYSTEM_MENU_BAR)) {
            viewItems.add(null);
            // "Hide", not a checkbox: this menu is inside the bar, so it can only ever be
            // opened while the bar is showing, and a checkbox you can only see when it is
            // ticked says nothing. The way back is the right-click menu, which is reachable
            // either way and therefore does carry a checkbox.
            viewItems.add(register(MenuAction.of(
                    tr("menu.hideMenuBar"),
                    new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                    () -> settings.setShowMenuBar(!settings.showMenuBar()))));
        }
        // Added to the list by hand rather than through menu(): a palette that offers to open the
        // palette is noise in every search.
        viewItems.add(null);
        viewItems.add(register(MenuAction.of(
                tr("menu.commandPalette"),
                new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                this::showPalette)));
        Menu view = menu(tr("menu.view"), viewItems.toArray(MenuAction[]::new));
        if (showsZoomRow(SYSTEM_MENU_BAR)) {
            ZoomMenuRow zoomRow = new ZoomMenuRow(zoomActions());
            view.getItems().add(0, zoomRow.item());
            // The level can have moved since the row was last shown — from a chord, the other menu,
            // or another window entirely.
            view.setOnShowing(e -> zoomRow.refresh());
        }
        commands.remove(viewItems.get(viewItems.size() - 1));

        Menu window = menu(
                tr("menu.window"),
                register(MenuAction.of(
                        tr("menu.nextTab"), MenuAction.shiftChord(KeyCode.CLOSE_BRACKET), () -> selectRelativeTab(1))),
                register(MenuAction.of(
                        tr("menu.previousTab"),
                        MenuAction.shiftChord(KeyCode.OPEN_BRACKET),
                        () -> selectRelativeTab(-1))),
                null,
                // Dragging is the usual way, but a tab strip that can only be reordered by mouse is
                // unreachable from the keyboard entirely.
                register(MenuAction.of(
                        tr("menu.moveTabLeft"),
                        new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                        () -> moveSelectedTab(-1))),
                register(MenuAction.of(
                        tr("menu.moveTabRight"),
                        new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                        () -> moveSelectedTab(1))));

        // Help is built by hand rather than through menu(), because its update entry is a MenuItem
        // this class relabels later. Its two actions are added to the palette explicitly.
        MenuAction about = MenuAction.of(tr("menu.about", com.termina.AppInfo.NAME), () -> windows.showAbout(stage));
        MenuAction checkForUpdates =
                MenuAction.of(tr("menu.checkForUpdates"), () -> windows.checkForUpdatesNow(this::report));
        updateItem = checkForUpdates.toMenuItem();
        Menu help = new Menu(tr("menu.help"));
        commands.add(about);
        commands.add(checkForUpdates);
        help.getItems().addAll(about.toMenuItem(), updateItem);

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
            updateItem.setText(tr("menu.checkForUpdates"));
            updateItem.setOnAction(e -> windows.checkForUpdatesNow(this::report));
        } else {
            updateItem.setText(tr("menu.updateAvailable", update.version()));
            updateItem.setOnAction(e -> windows.openReleasePage());
        }
    }

    /**
     * Shows a transient message. With no status bar, an information-only alert is the honest
     * option — it is the only surface that cannot be missed, and this only ever runs in response to
     * the user picking the menu item.
     */
    private void report(String message) {
        // One dialog, reused, because reporting is a sequence rather than an event: the update
        // check says "checking…" and then says how it went. Opening a second alert for the second
        // message left the first sitting underneath it — the user dismissed the result and was
        // handed a stale "Checking for updates…" to dismiss as well, in the wrong order, looking
        // like the check had started over.
        if (reportAlert != null && reportAlert.isShowing()) {
            reportAlert.setContentText(message);
            // The replacement text is a different length, and a DialogPane sized for the old one
            // clips or strands it.
            reportAlert.getDialogPane().getScene().getWindow().sizeToScene();
            return;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle(com.termina.AppInfo.NAME);
        alert.setHeaderText(null);
        alert.setContentText(message);
        // Dismissing it mid-sequence is allowed: the next message opens a fresh one rather than
        // updating a dialog that is no longer on screen.
        alert.setOnHidden(e -> {
            if (reportAlert == alert) reportAlert = null;
        });
        reportAlert = alert;
        alert.show();
    }

    /**
     * Says that opening a link failed, which is otherwise invisible.
     *
     * <p>The usual cause is a configured command that is not on PATH — a typo in a setting made
     * once and not looked at since. Without this the click simply does nothing, which reads as the
     * link not having been a link.
     */
    private void reportOpenFailed(String command) {
        javafx.application.Platform.runLater(() -> report(tr("status.openFailed", command)));
    }

    /** The live {@link #report} dialog, so a follow-up message replaces it instead of stacking. */
    private Alert reportAlert;

    /** Drives {@link #report} from the development capture hook. */
    public void reportForCapture(String message) {
        report(message);
    }

    /** Records the binding for the scene filter and hands the action back for the menu. */
    /**
     * The tab a number selects, or -1 when there is none.
     *
     * <p>The last digit means the <em>last</em> tab rather than the ninth, which is the convention
     * every browser and terminal follows: with four tabs open, Alt+9 goes to the fourth. A number
     * past the end selects nothing rather than the nearest, because silently landing somewhere else
     * is worse than not moving.
     */
    static int tabIndexFor(int number, int tabCount) {
        if (tabCount <= 0 || number < 1 || number > TAB_CHORD_COUNT) return -1;
        if (number == TAB_CHORD_COUNT) return tabCount - 1;
        return number <= tabCount ? number - 1 : -1;
    }

    /** 1 through 9, the range every terminal binds. */
    static final int TAB_CHORD_COUNT = 9;

    private void selectTabByNumber(int number) {
        int index = tabIndexFor(number, tabs.getTabs().size());
        if (index >= 0) tabs.getSelectionModel().select(index);
    }

    /**
     * Records a command that has no menu item of its own.
     *
     * <p>Everything else reaches the palette by being in a menu, which is what keeps the two from
     * drifting. The zoom actions are the exception: they are shown as a row of buttons rather than
     * as items, and losing their chords and their palette entries along with their items would be
     * a regression dressed as a redesign.
     */
    private void command(MenuAction action) {
        register(action);
        commands.add(action);
    }

    private MenuAction register(MenuAction action) {
        if (action.accelerator() != null) bindings.add(action);
        return action;
    }

    /** A null entry becomes a separator. */
    /**
     * Builds a menu, and records its actions as the palette's command list.
     *
     * <p>Recorded here rather than declared separately so the two cannot drift: a command is in the
     * palette because it is in a menu. The tab context menu deliberately does not come through
     * here — its items act on the tab that was right-clicked, which is not a thing the palette can
     * supply.
     */
    private Menu menu(String title, MenuAction... actions) {
        Menu menu = new Menu(title);
        for (MenuAction action : actions) {
            menu.getItems().add(action == null ? new SeparatorMenuItem() : action.toMenuItem());
            if (action != null) commands.add(action);
        }
        return menu;
    }

    private void onKeyPressed(KeyEvent e) {
        // While the palette is open it owns the keyboard. This filter is on the scene, so without
        // this it would still be matching chords against keystrokes meant for the query field.
        // Not consumed: the palette's own filter is downstream and still needs them.
        if (palette != null && palette.isShowing()) return;

        for (MenuAction binding : bindings) {
            if (binding.matches(e)) {
                binding.action().run();
                e.consume();
                return;
            }
        }
        // Zoom-in reaches here as EQUALS on most layouts, since + is the shifted key.
        if (e.isShortcutDown() && !e.isShiftDown() && (e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.ADD)) {
            zoom(1);
            e.consume();
        }
    }

    /** Zoom writes through the settings so it persists and shows up in the settings window. */
    /**
     * Steps the zoom, which is no longer the font size.
     *
     * <p>It used to add a pixel to the configured size, which meant zooming quietly rewrote the
     * preference and there was no level to show in the zoom row — and no meaning for resetting one.
     */
    private void zoom(double delta) {
        double current = settings.fontZoom();
        settings.setFontZoom(delta > 0 ? ZoomLevels.in(current) : ZoomLevels.out(current));
    }

    private void resetZoom() {
        settings.setFontZoom(ZoomLevels.DEFAULT);
    }

    private void toggleFullScreen() {
        stage.setFullScreen(!stage.isFullScreen());
    }

    /** macOS has its own convention and gives F11 to the desktop. */
    private static KeyCombination fullScreenChord() {
        return MAC
                ? new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN, KeyCombination.META_DOWN)
                : new KeyCodeCombination(KeyCode.F11);
    }

    /** Shared by the View menu's row and every tab's right-click row. */
    private ZoomMenuRow.Actions zoomActions() {
        return new ZoomMenuRow.Actions() {
            @Override
            public void zoomIn() {
                zoom(1);
            }

            @Override
            public void zoomOut() {
                zoom(-1);
            }

            @Override
            public void reset() {
                resetZoom();
            }

            @Override
            public void toggleFullScreen() {
                TerminalWindow.this.toggleFullScreen();
            }

            @Override
            public int percent() {
                return ZoomLevels.percent(settings.fontZoom());
            }
        };
    }

    // ---------------------------------------------------------------- settings

    /** Re-applies everything that can change while a session is running, to every tab. */
    public void applySettings() {
        Theme theme = Theme.byId(settings.themeId(), Theme.EDITORA_DARK);
        if (stage.getScene() != null) stage.getScene().setFill(theme.palette().background());
        applyTabBarVisibility();
        applyMenuBarVisibility();
        // Live, and on the stage rather than the scene: a decorated window's native background is
        // opaque, so scene-level transparency needs StageStyle.TRANSPARENT — which takes the title
        // bar with it and can only be chosen before the window is shown. Stage opacity keeps both
        // the decorations and the ability to change your mind without a restart. The cost is that
        // it fades the glyphs along with the background, which the floor in WindowOpacity accounts
        // for.
        stage.setOpacity(WindowOpacity.clamp(settings.windowOpacity()));
        for (TerminalView terminal : terminals()) applySettingsTo(terminal);
    }

    private void applySettingsTo(TerminalView terminal) {
        Theme theme = Theme.byId(settings.themeId(), Theme.EDITORA_DARK);
        terminal.setPalette(theme.palette());
        terminal.setFontFamily(settings.fontFamily());
        terminal.setFontSize(settings.effectiveFontSize());
        terminal.setPreferredCursor(settings.cursorShape());
        terminal.setAltIsMeta(settings.altIsMeta());
        terminal.setBellEnabled(settings.bell());
        terminal.setScrollBarEnabled(settings.showScrollBar());
    }

    /**
     * Opens the palette over this window.
     *
     * <p>The theme entries are appended rather than living in a menu: a menu listing every theme is
     * a submenu nobody opens, while a palette is exactly where "make this one darker" belongs.
     */
    private void showPalette() {
        List<MenuAction> all = new ArrayList<>(commands);
        for (Theme theme : Theme.values()) {
            // Setting it is enough: Settings.onChange re-applies to every window already.
            all.add(MenuAction.of(tr("palette.theme", theme.displayName()), () -> settings.setThemeId(theme.id())));
        }
        palette.show(all);
    }

    private void focusActiveTerminal() {
        TerminalView terminal = activeTerminal();
        if (terminal != null) terminal.requestFocus();
    }

    /**
     * Gives focus to a named piece of chrome, for the capture hook.
     *
     * <p>This is how the focus guard is tested at all: the paths that take focus in the wild are
     * clicks on chrome that are awkward to synthesise and, in the case that prompted the guard,
     * could not be reproduced by hand. Taking focus directly is the same end state by a route that
     * can be asked for on the command line.
     */
    public boolean stealFocusForCapture(String what) {
        javafx.scene.Node target =
                switch (what) {
                    case "tabs" -> tabs;
                    case "newtab" -> newTabButton;
                    case "menubar" -> menuBar;
                    default -> null;
                };
        if (target == null) return false;
        target.requestFocus();
        return true;
    }

    /** Public so a capture run can open the palette without a keystroke. */
    public String showPaletteForCapture(String query) {
        showPalette();
        if (query != null && !query.isEmpty()) palette.setQueryForCapture(query);
        return String.join(" | ", palette.visibleCommands());
    }

    private void reportShellFailure(IOException e) {
        // Logged as well as shown. The dialog says one sentence and is then gone; this is the part
        // that survives to be pasted into a bug report, and it carries the stack trace.
        System.getLogger(TerminalWindow.class.getName()).log(System.Logger.Level.ERROR, "could not start a shell", e);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle(com.termina.AppInfo.NAME);
        alert.setHeaderText(tr("dialog.shellFailed"));
        alert.setContentText(String.valueOf(e.getMessage()));
        alert.showAndWait();
    }
}
