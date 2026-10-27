package com.termina.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.application.ColorScheme;
import javafx.application.ConditionalFeature;
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
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import com.termina.config.Settings;
import com.termina.process.RunningProgram;
import org.jspecify.annotations.Nullable;

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
    /**
     * Every command the user can rebind, on its <em>default</em> chord, in registration order — the
     * order the keyboard settings list them in. What the window actually answers to is {@link
     * #bindings}, derived from this and the stored key bindings by {@link #applyKeybindings}.
     */
    private final List<MenuAction> table = new ArrayList<>();

    /** The chords in force: {@link #table} with the user's key bindings applied, unbound commands left out. */
    private final List<MenuAction> bindings = new ArrayList<>();

    /** Command id to the chord it is on now, or to null when it has none. */
    private Map<String, KeyCombination> chords = Map.of();

    /** The menu items showing each command's chord, which follow it when it moves. */
    private final Map<String, List<MenuItem>> itemsById = new java.util.HashMap<>();

    private final javafx.beans.property.StringProperty newTabTipText = new javafx.beans.property.SimpleStringProperty();
    private final javafx.beans.property.StringProperty closeTabTipText =
            new javafx.beans.property.SimpleStringProperty();

    /** Everything the palette can run, in menu order. */
    private final List<MenuAction> commands = new ArrayList<>();

    private CommandPalette palette;

    // The actions below are made here, not in buildMenuBar where they are registered: the palette
    // and the constructor ask after them, and NullAway cannot see an assignment in a builder as
    // initialization.

    /** The palette's own entry: rebound live when the setting changes, and kept out of its own list. */
    private final MenuAction paletteAction =
            item("menu.commandPalette", MenuAction.DEFAULT_PALETTE_CHORD, this::showPalette);

    /**
     * Reopen Closed Tab, which the palette greys while there is nothing to reopen. Cmd+Z on macOS,
     * which is iTerm2's "Undo Close"; Ctrl+Shift+Z elsewhere, where the browsers' Ctrl+Shift+T is
     * already New Tab.
     */
    private final MenuAction reopenAction =
            item("menu.reopenClosedTab", MenuAction.appChord(KeyCode.Z), this::reopenClosedTab);

    /**
     * The commands that need the shell's prompt marks, greyed in the palette until there are some.
     * Terminal.app's Cmd+Up and Cmd+Down on macOS.
     */
    private final MenuAction previousPromptAction = item(
            "menu.previousPrompt",
            MenuAction.appChord(KeyCode.UP),
            () -> withActiveTerminal(terminal -> terminal.jumpToPrompt(true)));

    private final MenuAction nextPromptAction = item(
            "menu.nextPrompt",
            MenuAction.appChord(KeyCode.DOWN),
            () -> withActiveTerminal(terminal -> terminal.jumpToPrompt(false)));
    private final MenuAction selectOutputAction =
            item("menu.selectLastOutput", () -> withActiveTerminal(TerminalView::selectLastOutput));

    /**
     * Set when a scene binding has just run on KEY_PRESSED, so the KEY_TYPED that follows it is
     * dropped. The chord that opens the palette is the case that shows: on macOS Option+X also
     * composes "≈", which would otherwise land in the query field the chord has just focused.
     */
    private boolean swallowNextTyped;

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
                    for (TerminalView pane : groupOf(removed).panes()) pane.close();
                }
            }
            // Same reason: mid-reorder the list is briefly one short, and for a single-tab window
            // briefly empty, which would close the window out from under the drag.
            if (!reordering && tabs.getTabs().isEmpty() && stage.isShowing()) stage.close();
            applyTabBarVisibility();
        });

        // A pane dropped on the strip's empty part becomes a tab of its own. On the TabPane, so it
        // hears only what the headers' own handlers did not take.
        tabs.addEventHandler(javafx.scene.input.DragEvent.DRAG_OVER, e -> {
            if (!paneOverEmptyStrip(e)) return;
            e.acceptTransferModes(TransferMode.MOVE);
            e.consume();
        });
        tabs.addEventHandler(javafx.scene.input.DragEvent.DRAG_DROPPED, e -> {
            TerminalView pane = draggedPane;
            if (pane == null || !paneOverEmptyStrip(e)) return;
            draggedPane = null;
            movePaneToNewTab(pane);
            e.setDropCompleted(true);
            e.consume();
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
        Tooltip newTabTip = new Tooltip();
        newTabTip.textProperty().bind(newTabTipText);
        newTabTip.setShowDelay(Duration.millis(400));
        newTabButton.setTooltip(newTabTip);

        // The chevron beside it, which is how every terminal with more than one kind of shell offers
        // the others. Separate from the + rather than a long-press or a right-click on it: the whole
        // point is that the other shells are discoverable, and a gesture nobody performs is not.
        profileButton.setFocusTraversable(false);
        profileButton.getStyleClass().addAll("new-tab-button", "profile-button");
        javafx.scene.layout.Region chevron = new javafx.scene.layout.Region();
        chevron.getStyleClass().add("tab-strip-chevron");
        profileButton.setGraphic(chevron);
        profileButton.setOnAction(e -> showProfileMenu());
        Tooltip profileTip = new Tooltip(tr("tooltip.profiles"));
        profileTip.setShowDelay(Duration.millis(400));
        profileButton.setTooltip(profileTip);

        tabStripButtons.getStyleClass().add("tab-strip-buttons");
        tabStripButtons.setPickOnBounds(false);
        // Pinned to its preferred size, or the alignment below does nothing: a StackPane resizes a
        // child up to its maximum and an HBox reports an unbounded one, so the box stretched the
        // full width of the strip and packed both buttons against its left edge, on top of the
        // first tab. The lone + never showed this — a Button's maximum is its preferred width.
        // Width only. Constraining the height too left the box at the buttons' unbound preferred
        // height and the glyphs eight pixels above the close buttons they are meant to line up
        // with — the buttons take the strip's height from a binding installed after the first
        // layout, which a pinned box never grows to.
        tabStripButtons.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane.setAlignment(tabStripButtons, Pos.TOP_RIGHT);
        StackPane tabHost = new StackPane(tabs, tabStripButtons);

        tabs.widthProperty().addListener((o, was, now) -> applyTabWidths());
        tabs.getTabs().addListener((ListChangeListener<Tab>) c -> applyTabWidths());

        BorderPane root = new BorderPane(tabHost);
        menuBar = buildMenuBar();
        // Decided here and never again: a stage's style is fixed before it is first shown, so the
        // setting reaches this window only if it was on when the window was made. Settings says so
        // on its row rather than appearing to do nothing.
        boolean extendedWindows = Platform.isSupported(ConditionalFeature.EXTENDED_WINDOW);
        headerBar =
                usesHeaderBar(SYSTEM_MENU_BAR, settings.menuBarInTitleBar(), extendedWindows) ? buildHeaderBar() : null;
        if (headerBar != null && !stage.isShowing()) stage.initStyle(StageStyle.EXTENDED);
        root.setTop(headerBar != null ? headerBar : menuBar);
        // Full screen has no title bar, and the header bar is one: left in place it kept the title
        // and the window buttons over the terminal. The menus stay, as an ordinary row.
        if (headerBar != null) stage.fullScreenProperty().addListener((o, was, full) -> placeHeaderBar(root, full));
        applyMenuBarVisibility();

        // A StackPane over the whole window so the palette has somewhere to be. It is empty until
        // the palette opens, so it costs a node and nothing else.
        StackPane overlay = new StackPane(root);
        palette = new CommandPalette(overlay, this::focusActiveTerminal);
        palette.setOpener(chordOf(paletteAction.id()));

        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double width = WindowGeometry.fit(settings.windowWidth(), DEFAULT_WIDTH, screen.getWidth());
        double height = WindowGeometry.fit(settings.windowHeight(), DEFAULT_HEIGHT, screen.getHeight());
        restoredWidth = width;
        restoredHeight = height;
        Scene scene = new Scene(overlay, width, height);
        // The last position too, pulled back onto a screen that still exists — a monitor that has
        // gone leaves coordinates that would put the window where nobody can reach its title bar.
        // Another window cascading from this one overrides it afterwards (WindowManager).
        settings.windowX()
                .ifPresent(x -> settings.windowY().ifPresent(y -> {
                    // Against the screen the window was on, not the primary one: clamping a second
                    // monitor's coordinates to the primary drags every window back to it at launch.
                    var screens = javafx.stage.Screen.getScreensForRectangle(x, y, width, height);
                    javafx.geometry.Rectangle2D bounds =
                            screens.isEmpty() ? screen : screens.get(0).getVisualBounds();
                    double[] at = WindowPlacement.offsetWithin(x, y, 0, width, height, bounds);
                    stage.setX(at[0]);
                    stage.setY(at[1]);
                }));
        ThemeEntry theme = Themes.byId(settings.themeId());
        scene.setFill(theme.palette().background());
        var appCss = TerminalWindow.class.getResource("/com/termina/styles/app.css");
        if (appCss != null) scene.getStylesheets().add(appCss.toExternalForm());
        Fonts.installUiFont(scene);

        // A scene FILTER, not the menu's accelerators: filters run in the capturing phase, before
        // TerminalView's own filter turns Ctrl+<letter> into a control byte for the shell.
        // Accelerators fire only after an unconsumed bubble, which for those chords never happens.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        scene.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (swallowNextTyped) e.consume();
            swallowNextTyped = false;
        });
        // A chord that produces no KEY_TYPED at all must not leave the flag set for the next character.
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> swallowNextTyped = false);

        stage.setScene(scene);
        // JavaFX's default full-screen exit key is Escape, handled before the scene sees the key,
        // and it paints a "Press ESC to exit full screen" banner over the terminal. Escape belongs
        // to the shell — vim, less and fzf all live on it — so the toggle is the only way out, both
        // ways. Verified by driving it: F11 then an OS-level Escape left full screen until this.
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("");
        Icons.applyTo(stage);
        if (settings.windowMaximized()) stage.setMaximized(true);
        stage.setOpacity(WindowOpacity.clamp(settings.windowOpacity()));
        trackGeometry();
        stage.setOnCloseRequest(e -> {
            if (!confirmClose(new ArrayList<>(tabs.getTabs()))) e.consume();
        });
        // However the window went — its close button, Close Window, quitting — its tabs go through
        // the tab list, which is where disposal is. Closing the stage alone took nothing out of the
        // list, so a window closed with others still open left every one of its shells running,
        // with their pump threads, emulator threads and directory polls, until the app quit.
        stage.addEventHandler(
                javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> tabs.getTabs().clear());
        tabs.getSelectionModel().selectedItemProperty().addListener((o, old, tab) -> {
            bindTitleTo(tab);
            applyWindowTheme();
            TabMarkState marks = tab == null ? null : marksOf(tab);
            if (marks != null) marks.apply(TabMarks.onSelected());
            // A hidden tab defers its repaint until it is shown, and being selected makes it
            // visible without laying it out — so it has to be told. Every pane of it, not only the
            // active one: they were all hidden together.
            if (tab != null) groupOf(tab).panes().forEach(TerminalView::wake);
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
            // First, which pane the keyboard went to — a pane itself, or its find bar. Ahead of
            // the guard, so a click into another pane makes that pane the one it guards.
            TerminalView into = paneContaining(now);
            Tab selected = tabs.getSelectionModel().getSelectedItem();
            if (into != null && selected != null) groupOf(selected).setActive(into);
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

    /** The pane a node is in — the pane itself, or something inside it — or null. */
    private static @Nullable TerminalView paneContaining(javafx.scene.Node node) {
        for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
            if (n instanceof TerminalView view) return view;
        }
        return null;
    }

    /** Whether something on screen is entitled to the keyboard instead of the terminal. */
    private boolean overlayWantsKeys(@Nullable TerminalView terminal) {
        if (palette != null && palette.isShowing()) return true;
        // A context menu is a popup that dismisses when focus moves, so reclaiming under it would
        // make right-click unusable rather than merely rude. The profile dropdown is the same kind
        // of popup and would be dismissed on the way to being shown.
        if (terminal != null && terminal.isContextMenuShowing()) return true;
        // The find bar's field. Only while it has the keyboard: with the bar open and the terminal
        // clicked, the terminal is entitled to focus back like any other time.
        if (terminal != null && terminal.searchHasFocus()) return true;
        if (profileMenu.isShowing()) return true;
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
    private void bindTitleTo(@Nullable Tab tab) {
        stage.titleProperty().unbind();
        if (tab == null) {
            stage.setTitle(com.termina.AppInfo.NAME);
            return;
        }
        stage.titleProperty().bind(terminalOf(tab).getDisplay().windowTitleProperty());
    }

    /**
     * Whether the window draws its own title bar, with the menus in it.
     *
     * <p>That is JavaFX 27's extended stage style: the platform keeps its window buttons but gives
     * up the title bar, and a {@link HeaderBar} at the top of the scene takes its place. Three
     * things have to agree. The setting, obviously. The menus have to be in the window — under a
     * screen menu bar there is nothing to merge, and an extended window there would be a bare strip
     * under the traffic lights. And the platform has to support the style at all; JavaFX downgrades
     * an unsupported {@code EXTENDED} to {@code DECORATED} silently, which would leave the header
     * bar drawn <em>under</em> a native title bar as a second row.
     */
    static boolean usesHeaderBar(boolean systemMenuBar, boolean menuBarInTitleBar, boolean extendedWindowsSupported) {
        return !systemMenuBar && menuBarInTitleBar && extendedWindowsSupported;
    }

    /**
     * Whether to offer the title-bar option in Settings. The checkbox is disabled with the reason
     * on its row where it could do nothing, which is the same rule as the menu-bar toggle's.
     */
    static boolean offersHeaderBarOption(boolean systemMenuBar, boolean extendedWindowsSupported) {
        return !systemMenuBar && extendedWindowsSupported;
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

    /**
     * Takes the header bar out for full screen and puts it back after. The menu bar moves rather
     * than going with it, so a full-screen window looks like a decorated one in full screen: the
     * menus if they are on, and nothing above them.
     */
    private void placeHeaderBar(BorderPane root, boolean fullScreen) {
        HeaderBar header = headerBar;
        if (header == null) return;
        if (fullScreen) {
            header.setLeft(null);
            root.setTop(menuBar);
        } else {
            root.setTop(header);
            header.setLeft(menuBar);
        }
        HeaderBar.setSystemButtonHeight(stage, fullScreen ? 0 : HeaderBar.USE_DEFAULT_SIZE);
    }

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
        // The buttons live in the strip, so they go with it — and unmanaged as well as invisible,
        // for the same reason the menu bar had to be: a hidden-but-managed node still paints.
        tabStripButtons.setVisible(show);
        tabStripButtons.setManaged(show);
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
            Tooltip tip = new Tooltip();
            tip.textProperty().bind(closeTabTipText);
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
     * rather than as nothing to say. A WSL tab whose shell reports no directory is in that state
     * for good, so it is not an edge case.
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

    /** Opens a tab and starts the default profile's shell in it. */
    public void openTab() {
        openTab(windows.profiles().defaultProfile());
    }

    /**
     * Opens a tab running one profile.
     *
     * <p>The directory is inherited from the current tab unless the profile names one of its own —
     * the same rule a plain new tab has always followed, and the reason a profile's directory is
     * blank by default rather than set to home.
     */
    public void openTab(com.termina.shell.@Nullable Profile profile) {
        if (profile == null) {
            openTab(com.termina.pty.LaunchOptions.ofShell(settings.shell()).withWorkingDirectory(currentDirectory()));
            return;
        }
        openTab(profile.toLaunchOptions(currentDirectory()));
    }

    /**
     * Opens the profile whose place in the list is {@code number}, if there is one.
     *
     * <p>Bound to a chord for 1 through 9 and silently nothing beyond the end of the list, which is
     * the same thing the tab chords do — a chord that reports an error for a profile the user has
     * not created is noise.
     */
    private void openProfileByNumber(int number) {
        List<com.termina.shell.Profile> all = windows.profiles().all();
        if (number >= 1 && number <= all.size()) openTab(all.get(number - 1));
    }

    /**
     * The profile entries, rebuilt each time a menu is about to show.
     *
     * <p>Never cached. The list changes underneath a window in three ordinary ways — discovery
     * finishing after the menus were built, a profile added in the settings window, and a shell
     * installed while the application is running and found at the next launch — and a menu built
     * once would show the list as it was at construction in the first two of those.
     */
    private List<MenuItem> profileMenuItems() {
        List<MenuItem> items = new ArrayList<>();
        List<com.termina.shell.Profile> all = windows.profiles().all();
        String defaultId = windows.profiles().defaultProfileId();
        int number = 1;
        for (com.termina.shell.Profile profile : all) {
            MenuItem item = new MenuItem(profile.name());
            // The command as a tooltip has nowhere to live on a MenuItem, so the accelerator column
            // carries the chord and the name carries the rest. Nine of them get a chord; the tenth
            // profile onwards is menu-only, which is where every terminal draws this line too.
            if (number <= TAB_CHORD_COUNT) item.setAccelerator(chordOf(PROFILE_CHORD_ID + number));
            com.termina.shell.Profile opened = profile;
            item.setOnAction(e -> openTab(opened));
            // The default is marked rather than reordered: moving it to the top would change the
            // numbering of everything else, and the numbers are bound to chords.
            boolean isDefault = profile.id().equals(defaultId) || (defaultId.isBlank() && number == 1);
            item.setGraphic(profileGraphic(profile, isDefault));
            if (isDefault) item.getProperties().put(DEFAULT_PROFILE_ITEM, Boolean.TRUE);
            items.add(item);
            number++;
        }
        return items;
    }

    private static KeyCode digitKey(int number) {
        return KeyCode.valueOf("DIGIT" + number);
    }

    /**
     * A profile's mark, with a fixed slot in front of it for the default's tick.
     *
     * <p>The slot is there whether or not it holds anything, so every mark in the menu lines up.
     * Sizing it only on the one row that has a tick would step the other rows left by the width of
     * a checkmark, which reads as the list being misaligned rather than as one row being special.
     */
    private static javafx.scene.Node profileGraphic(com.termina.shell.Profile profile, boolean isDefault) {
        StackPane tick = new StackPane();
        tick.setMinWidth(TICK_SLOT_WIDTH);
        tick.setPrefWidth(TICK_SLOT_WIDTH);
        tick.setMaxWidth(TICK_SLOT_WIDTH);
        if (isDefault) tick.getChildren().add(MenuIcons.check());
        javafx.scene.layout.HBox graphic = new javafx.scene.layout.HBox(6, tick, ProfileIcons.forProfile(profile));
        graphic.setAlignment(Pos.CENTER_LEFT);
        return graphic;
    }

    private static final double TICK_SLOT_WIDTH = 14;

    /** Drops the profile list under the chevron beside the {@code +}. */
    private void showProfileMenu() {
        if (profileMenu.isShowing()) {
            profileMenu.hide();
            return;
        }
        profileMenu.getItems().setAll(profileMenuItems());
        profileMenu.getItems().add(new SeparatorMenuItem());
        MenuItem configure = new MenuItem(tr("menu.configureProfiles"), MenuIcons.settings());
        configure.setOnAction(e -> windows.showSettings(stage));
        profileMenu.getItems().add(configure);
        profileMenu.show(profileButton, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    /** Reused rather than rebuilt, so that clicking the chevron a second time closes it. */
    private final ContextMenu profileMenu = new ContextMenu();

    /** Marks the item standing for the default profile, so a capture run can say which it is. */
    private static final String DEFAULT_PROFILE_ITEM = "termina.defaultProfile";

    /**
     * Opens the profile dropdown and reports what is in it, for the capture hook.
     *
     * <p>The only way to see this list. It is assembled at show time from discovery, which on
     * Windows means whichever PowerShells and WSL distributions that machine has — so the entries
     * this feature exists for can only be checked on a machine that has them, by asking it.
     */
    public String profileMenuForCapture() {
        showProfileMenu();
        StringBuilder out = new StringBuilder();
        for (MenuItem item : profileMenu.getItems()) {
            if (item instanceof SeparatorMenuItem) {
                out.append("| ");
                continue;
            }
            out.append(item.getText());
            if (item.getProperties().containsKey(DEFAULT_PROFILE_ITEM)) out.append("*");
            if (item.getAccelerator() != null) {
                out.append(" [").append(item.getAccelerator().getDisplayText()).append(']');
            }
            out.append("  ");
        }
        StringBuilder commands = new StringBuilder();
        for (com.termina.shell.Profile profile : windows.profiles().all()) {
            commands.append("\n[capture]   ")
                    .append(profile.id())
                    .append(" (")
                    .append(profile.source())
                    .append(") ")
                    .append(profile.commandLine());
        }
        return out.toString().trim()
                + "\n[capture]   discovery finished=" + windows.profiles().discoveryFinished()
                + commands;
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
        if (terminal == null || !terminal.isStarted()) return "";
        // Where a shell's own report wins (Windows: see ProcessCwd), the directory the tab knows —
        // reported, or polled from the OS until one was — is the answer. Elsewhere the OS, asked now.
        String known = terminal.getDisplay().getCwd();
        if (com.termina.pty.ProcessCwd.reportedDirectoryWins() && known != null && !known.isBlank()) return known;
        return com.termina.pty.ProcessCwd.of(terminal.getSession().pid()).orElse(known);
    }

    /**
     * Opens a tab with explicit launch options.
     *
     * <p>Used for the first tab of the first window, which is the only one the command line applies
     * to. A second tab running `-e vim` again — or reopening in a directory the user has since
     * navigated away from — is not what anybody means by it.
     */
    public void openTab(com.termina.pty.LaunchOptions options) {
        openTab(options, tabs.getTabs().size(), "");
    }

    /**
     * @param index where in the strip the tab goes — the end for a new tab, its old place for a
     *     reopened one, right after its original for a duplicate
     * @param userTitle the name the user gave the tab this one stands in for, or ""
     */
    private void openTab(com.termina.pty.LaunchOptions options, int index, String userTitle) {
        Tab tab = new Tab();
        TerminalView terminal = createPane(options, tab);
        terminal.getDisplay().setUserTitle(userTitle);
        addTab(tab, new PaneGroup(terminal), index);

        try {
            terminal.start();
        } catch (IOException e) {
            // Report first and remove last. Removing first fired the list listener: an empty
            // window closed itself, the last window closing exited the toolkit, and the dialog then
            // opened on a dead owner — so a default profile whose command had gone away left nobody
            // a window to fix it from, and the only way back was editing the settings file.
            boolean fallBack = shouldFallBackToDefaultShell(tabs.getTabs().size(), !options.hasCommand());
            reportShellFailure(options, e, fallBack);
            if (fallBack) {
                openTab(com.termina.pty.LaunchOptions.ofShell(settings.shell())
                        .withWorkingDirectory(options.workingDirectory()));
            }
            tabs.getTabs().remove(tab);
            return;
        }
        endPaneWithItsShell(tab, terminal);
        Platform.runLater(terminal::requestFocus);
    }

    /**
     * Puts a tab holding {@code group} into the strip at {@code index} and selects it: its title,
     * marks, menu, close handling and drag. The panes' shells are not started here — a new tab's
     * are, a moved pane's already run.
     */
    private void addTab(Tab tab, PaneGroup group, int index) {
        tab.setContent(group);
        tab.setUserData(group);
        // The title goes on a Label graphic rather than on the Tab: a Tab is not a Node, so it has
        // nowhere to attach drag handlers. It binds to the *tab* title, which is the shell's own
        // title if it set one and otherwise just the directory's name — a path would not fit, and
        // JavaFX ellipsises from the end, keeping exactly the leading part every tab has in common.
        // Of the active pane, in a split tab, and rebound as the keyboard moves between panes.
        Label title = new Label();
        title.getStyleClass().add("tab-title");
        // What the label had to leave out. The tab shows a name; the hover shows the path it is a
        // name for — and when a program has set a title, the path is the only place left to say
        // where that program is running.
        javafx.beans.property.StringProperty directory = new javafx.beans.property.SimpleStringProperty("");
        installDirectoryTooltip(title, directory);
        followActivePane(tab, group, title, directory);
        // A dot after the title when something happened here while another tab was selected:
        // a bell, or output after a quiet spell. See TabMarks for the rule.
        TabMarkState marks = new TabMarkState(title);
        marks.setColours(Themes.byId(settings.themeId()).palette());
        tab.getProperties().put(TAB_MARKS, marks);
        tab.setGraphic(title);
        tab.setContextMenu(buildTabMenu(tab));
        installCloseTooltips();
        // The close button is JavaFX's own and does not come through closeTab(); consuming the
        // request is the only way to stop it.
        tab.setOnCloseRequest(e -> {
            if (!confirmClose(List.of(tab))) e.consume();
            else rememberClosed(tab);
        });
        tabs.getTabs().add(Math.max(0, Math.min(index, tabs.getTabs().size())), tab);
        tabs.getSelectionModel().select(tab);
        // After the tab is in the list: the header this attaches to is built by the skin, and does
        // not exist until the tab does.
        installTabDrag(0);
    }

    /**
     * A terminal wired to this window, for a new tab or a new pane in {@code tab}: the settings,
     * the menus' actions, the link opener, and the tab's marks — any pane's bell or output marks
     * the tab it is in.
     */
    private TerminalView createPane(com.termina.pty.LaunchOptions options, Tab tab) {
        TerminalView terminal = new TerminalView(settings.fontSize());
        // Options first: they name the profile, and the profile's font and theme are part of the
        // settings applied next.
        terminal.setSessionOptions(settings.scrollbackLines(), options);
        applySettingsTo(terminal);
        wirePane(terminal, tab);
        return terminal;
    }

    /**
     * Everything a pane is told about the tab and window it is in. Separate from {@link
     * #createPane} because a pane moved to another tab keeps its shell and needs only this again:
     * its bell and output must mark the tab it is in now, not the one it came from.
     */
    private void wirePane(TerminalView terminal, Tab tab) {
        terminal.setOnPaneDragStarted(pane -> draggedPane = pane);
        terminal.setOnOpenSettings(() -> windows.showSettings(stage));
        terminal.setPasteConfirmer(this::confirmPaste);
        terminal.setOnNewTab(this::openTab);
        terminal.setOnNewWindow(() -> windows.openWindow(this));
        terminal.setOnShowPalette(this::showPalette);
        terminal.setOnShowAbout(() -> windows.showAbout(stage));
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
        terminal.setOnBellRung(() -> {
            TabMarkState marks = marksOf(tab);
            if (marks != null) marks.apply(TabMarks.onBell(isSelected(tab), marks.mark));
        });
        terminal.setOnNotify((notification, post) -> {
            TabMarkState marks = marksOf(tab);
            if (marks != null) marks.apply(TabMarks.onNotify(isSelected(tab)));
            if (!post || !settings.notifications()) return;
            PaneGroup group = groupOf(tab);
            if (!TabMarks.postsToDesktop(stage.isFocused(), isSelected(tab), group.active() == terminal)) return;
            // Untitled, it is named after the tab, which is how the user knows which one spoke.
            String title = notification.title().isEmpty() ? label(tab) : notification.title();
            DesktopNotifier.post(title.isBlank() ? com.termina.AppInfo.NAME : title, notification.body());
        });
        terminal.setOnOutput(() -> {
            TabMarkState marks = marksOf(tab);
            if (marks == null) return;
            long now = System.currentTimeMillis();
            marks.apply(TabMarks.onOutput(isSelected(tab), marks.mark, now, marks.lastOutputMs, TabMarks.QUIET_MS));
            marks.lastOutputMs = now;
        });
    }

    /**
     * Keeps the tab's label, its hover and — while the tab is selected — the window title on the
     * active pane. A split tab is named after the pane being typed in, as iTerm2's are.
     */
    private void followActivePane(
            Tab tab, PaneGroup group, Label title, javafx.beans.property.StringProperty directory) {
        java.util.function.Consumer<TerminalView> follow = pane -> {
            if (pane == null) return;
            title.textProperty().bind(pane.getDisplay().tabTitleProperty());
            directory.bind(pane.getDisplay().tabTooltipProperty());
            if (isSelected(tab)) bindTitleTo(tab);
        };
        group.activeProperty().addListener((o, was, now) -> {
            follow.accept(now);
            if (isSelected(tab)) applyWindowTheme();
        });
        follow.accept(group.active());
    }

    /**
     * A shell exiting closes its own pane — the tab, if it was the last one. The other panes and
     * tabs are still alive. Not a deliberate close, so nothing is asked and nothing is remembered
     * for Reopen Closed Tab.
     */
    private void endPaneWithItsShell(Tab tab, TerminalView terminal) {
        terminal.setOnSessionEnded(() -> Platform.runLater(() -> removePane(tab, terminal)));
    }

    /** Takes a pane out and disposes of it; the last pane takes its tab with it. */
    private void removePane(Tab tab, TerminalView pane) {
        if (!tabs.getTabs().contains(tab)) return;
        PaneGroup group = groupOf(tab);
        if (!group.contains(pane)) return;
        if (!group.isSplit()) {
            tabs.getTabs().remove(tab);
            return;
        }
        TerminalView next = group.remove(pane);
        pane.close();
        if (next != null && isSelected(tab)) next.requestFocus();
    }

    // ---------------------------------------------------------------- panes

    /**
     * Splits the active pane, starting the new one like Duplicate Tab does: the same shell or
     * profile, in the directory the active pane is in now.
     *
     * @param orientation {@link javafx.geometry.Orientation#HORIZONTAL} for side by side
     */
    private void splitActivePane(javafx.geometry.Orientation orientation) {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab == null) return;
        PaneGroup group = groupOf(tab);
        TerminalView from = group.active();
        com.termina.pty.LaunchOptions options =
                ClosedTabs.inDirectory(from.launchOptions(), from.getDisplay().getCwd());
        TerminalView added = createPane(options, tab);
        group.split(from, orientation, added);
        // A new split has no skin until the next CSS pass, and without one its items are not in
        // the scene: the shell would start at a guessed 80x24 and the focus request would be
        // dropped. Styled and laid out now, so the pane starts at its real size and takes focus.
        group.applyCss();
        group.layout();
        try {
            added.start();
        } catch (IOException e) {
            reportShellFailure(options, e, false);
            group.remove(added);
            added.close();
            return;
        }
        endPaneWithItsShell(tab, added);
        group.setActive(added);
        // At once, not on a later pulse as a new tab's is: the pane is already in a showing scene,
        // and a deferred request could land after the user — or a second chord — moved on.
        added.requestFocus();
    }

    /** Moves the keyboard to the pane beside the active one, if there is one that way. */
    private void focusPane(PaneGroup.Direction direction) {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab == null) return;
        PaneGroup group = groupOf(tab);
        // The neighbours are measured on screen, and while one pane fills the tab the rest are not
        // on it: put them back first, laid out, then move.
        if (group.isMaximized()) {
            group.restore();
            group.applyCss();
            group.layout();
        }
        TerminalView next = group.neighbour(group.active(), direction);
        if (next == null) return;
        group.setActive(next);
        next.requestFocus();
    }

    /** Whether the selected tab is split — Close Tab then closes the active pane instead. */
    private boolean activeTabIsSplit() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab != null && groupOf(tab).isSplit();
    }

    /** The active pane, closed as Close Tab closes a tab: asking first if something is running. */
    private void closeActivePane() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab == null) return;
        TerminalView pane = groupOf(tab).active();
        if (!confirmCloseTerminals(List.of(pane), 1)) return;
        removePane(tab, pane);
    }

    /**
     * Shows the active pane on its own, filling the tab, or puts it back among the others. The
     * others keep running; see {@link PaneGroup#maximize}.
     */
    private void toggleMaximizePane() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab == null) return;
        PaneGroup group = groupOf(tab);
        if (group.isMaximized()) group.restore();
        else if (!group.maximize(group.active())) return;
        group.applyCss();
        group.layout();
        TerminalView active = group.active();
        if (active != null) active.requestFocus();
    }

    /** The tab a pane is in, or null when it is in none of this window's. */
    private @Nullable Tab tabOf(TerminalView pane) {
        for (Tab tab : tabs.getTabs()) {
            if (groupOf(tab).contains(pane)) return tab;
        }
        return null;
    }

    /**
     * Takes a pane out of its tab <em>without</em> disposing of it — it is moving, not closing. A tab
     * left with no panes goes, and disposes nothing doing so: the list listener closes the panes the
     * group still has, and it has none.
     */
    private void detachPane(Tab tab, TerminalView pane) {
        PaneGroup group = groupOf(tab);
        TerminalView next = group.remove(pane);
        if (group.panes().isEmpty()) tabs.getTabs().remove(tab);
        else if (next != null && isSelected(tab)) next.requestFocus();
    }

    /** Moves a pane of a split tab into a tab of its own, right after the one it was in. */
    private void movePaneToNewTab(TerminalView pane) {
        Tab from = tabOf(pane);
        // A pane alone in its tab already is one.
        if (from == null || !groupOf(from).isSplit()) return;
        int index = tabs.getTabs().indexOf(from) + 1;
        detachPane(from, pane);
        Tab tab = new Tab();
        wirePane(pane, tab);
        addTab(tab, new PaneGroup(pane), index);
        endPaneWithItsShell(tab, pane);
        Platform.runLater(pane::requestFocus);
    }

    /** Moves a pane into another tab, to the right of that tab's active pane. */
    private void movePaneToTab(TerminalView pane, Tab target) {
        Tab from = tabOf(pane);
        if (from == null || from == target || !tabs.getTabs().contains(target)) return;
        detachPane(from, pane);
        PaneGroup group = groupOf(target);
        wirePane(pane, target);
        group.split(group.active(), javafx.geometry.Orientation.HORIZONTAL, pane);
        endPaneWithItsShell(target, pane);
        tabs.getSelectionModel().select(target);
        group.setActive(pane);
        group.applyCss();
        group.layout();
        pane.requestFocus();
    }

    /** The pane being dragged, which the Dragboard cannot carry: a node is not serialisable. */
    private @Nullable TerminalView draggedPane;

    /** Whether a drag over the strip carries a pane, and is over the strip's empty part. */
    private boolean paneOverEmptyStrip(javafx.scene.input.DragEvent e) {
        if (draggedPane == null || !e.getDragboard().hasContent(PaneDrag.FORMAT)) return false;
        Tab from = tabOf(draggedPane);
        if (from == null || !groupOf(from).isSplit()) return false;
        javafx.scene.Node strip = tabs.lookup(".tab-header-area");
        if (strip == null || !strip.isVisible()) return false;
        return strip.localToScene(strip.getLayoutBounds()).contains(e.getSceneX(), e.getSceneY());
    }

    /** The selected tab's panes as a tree, for the capture hook. */
    public String paneReport() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab == null ? "panes=none" : "panes=" + groupOf(tab).describe();
    }

    /** Splits the active pane — for the capture hook. */
    public void splitForCapture(boolean right) {
        splitActivePane(right ? javafx.geometry.Orientation.HORIZONTAL : javafx.geometry.Orientation.VERTICAL);
    }

    /** Moves to a neighbouring pane, by direction name — for the capture hook. */
    public void focusPaneForCapture(String direction) {
        focusPane(PaneGroup.Direction.valueOf(direction));
    }

    /** Opens the Nth profile in a new tab, as its chord would — for the capture hook. */
    public void openProfileForCapture(int number) {
        openProfileByNumber(number);
    }

    /** Each pane's profile, font and background, tab by tab — for the capture hook. */
    public String paneStyleReport() {
        List<String> out = new ArrayList<>();
        for (Tab tab : tabs.getTabs()) {
            for (TerminalView pane : groupOf(tab).panes()) {
                out.add(pane.launchOptions().profileId() + ":" + themeOf(pane).id() + ":"
                        + pane.fontReportForCapture());
            }
        }
        Object fill = stage.getScene() == null ? null : stage.getScene().getFill();
        return out + " sceneFill=" + fill;
    }

    /** Maximizes or restores the active pane — for the capture hook. */
    public void toggleMaximizePaneForCapture() {
        toggleMaximizePane();
    }

    /** Moves the active pane to a new tab, or to the tab at {@code index} — for the capture hook. */
    public void moveActivePaneForCapture(int index) {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab == null) return;
        TerminalView pane = groupOf(tab).active();
        if (index < 0) movePaneToNewTab(pane);
        else if (index < tabs.getTabs().size())
            movePaneToTab(pane, tabs.getTabs().get(index));
    }

    /** Closes the whole selected tab, split or not, as its close button would — for the capture hook. */
    public void closeSelectedTabForCapture() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab != null) closeTab(tab);
    }

    /** Every tab's panes, for the capture hook: {@code [H[0*, 1], 0*]}. */
    public String allPanesReport() {
        List<String> out = new ArrayList<>();
        for (Tab tab : tabs.getTabs()) out.add(groupOf(tab).describe());
        return out.toString();
    }

    /** Closes the active pane as the chord would — for the capture hook. */
    public void closeActivePaneForCapture() {
        closeCurrentTab();
    }

    /** Identifies our own drags, so a drop from another application is not mistaken for one. */
    private static final DataFormat TAB_DRAG = new DataFormat("application/x-termina-tab");

    /** The tab being dragged. The Dragboard can only carry serialisable data, and a Tab is not. */
    private @Nullable Tab draggedTab;

    /**
     * Drag-to-reorder.
     *
     * <p>JavaFX has no tab reordering of its own, so this is the whole gesture: the label is the
     * drag source, the drop side is decided by which half of the target it lands on, and an accent
     * edge shows where it would go.
     */
    /**
     * Installs the reorder gesture on a tab's whole header.
     *
     * <p><b>Not on the title label</b>, which is where this started and why the feature was
     * effectively missing: measured on a three-tab window, each header was 286px wide and the label
     * 13px. The gesture was there, and you had to catch two characters of text to find it — which
     * from the outside is indistinguishable from a terminal that cannot reorder tabs at all.
     *
     * <p>The header is JavaFX's own node rather than one of ours, so it is looked up from the skin
     * once the skin exists, on the retry the close-button tooltips already use.
     */
    private void installTabDrag(int attempt) {
        int found = 0;
        for (Tab tab : tabs.getTabs()) {
            javafx.scene.Node header = headerOf(tab);
            if (header == null) continue;
            found++;
            if (header.getProperties().putIfAbsent(TAB_DRAG_INSTALLED, Boolean.TRUE) != null) continue;
            installTabDrag(tab, header);
        }
        // Same reasoning as the tooltips: a missing header means the skin is still building it, but
        // only while the strip is shown. Hidden for a single tab there is no header and none coming.
        boolean stripShown = shouldShowTabBar(tabs.getTabs().size(), settings.hideTabBarWhenSingle());
        if (stripShown && found < tabs.getTabs().size() && attempt < CLOSE_TIP_ATTEMPTS) {
            Platform.runLater(() -> installTabDrag(attempt + 1));
        }
    }

    /**
     * The header node JavaFX built for a tab, found from our own title graphic upwards.
     *
     * <p>Not by position in {@code lookupAll(".tab")}: that returns an unordered set, so pairing by
     * index would sometimes hand the gesture the wrong tab and reorder something the user was not
     * dragging — rarely enough to look random.
     */
    private static javafx.scene.Node headerOf(Tab tab) {
        javafx.scene.Node node = tab.getGraphic();
        while (node != null && !node.getStyleClass().contains("tab")) {
            node = node.getParent();
        }
        return node;
    }

    private void installTabDrag(Tab tab, javafx.scene.Node header) {
        // Double-click renames, which is what every tabbed application teaches the hand. On the
        // header rather than the label, for the reason the drag gesture moved there.
        header.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2) renameTab(tab);
        });
        header.setOnDragDetected(e -> {
            if (tabs.getTabs().size() < 2) return;
            draggedTab = tab;
            var board = header.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            // The payload is a marker, not the data: identifying our drag is all it is for.
            content.put(TAB_DRAG, "tab");
            board.setContent(content);
            // The whole tab, not just its text — what is being moved is what should follow the
            // pointer.
            board.setDragView(header.snapshot(null, null));
            header.getStyleClass().add("tab-dragging");
            e.consume();
        });

        header.setOnDragOver(e -> {
            // A pane dropped on a tab joins it; dropped on its own tab it is already there.
            if (draggedPane != null && e.getDragboard().hasContent(PaneDrag.FORMAT)) {
                if (!groupOf(tab).contains(draggedPane)) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    if (!header.getStyleClass().contains("tab-drop-into"))
                        header.getStyleClass().add("tab-drop-into");
                }
                e.consume();
                return;
            }
            if (draggedTab == null || e.getGestureSource() == header) return;
            if (!e.getDragboard().hasContent(TAB_DRAG)) return;
            e.acceptTransferModes(TransferMode.MOVE);
            markDropSide(header, e.getX() > header.getBoundsInLocal().getWidth() / 2);
            e.consume();
        });

        header.setOnDragExited(e -> clearDropMarks(header));

        header.setOnDragDropped(e -> {
            clearDropMarks(header);
            if (draggedPane != null && e.getDragboard().hasContent(PaneDrag.FORMAT)) {
                TerminalView pane = draggedPane;
                draggedPane = null;
                movePaneToTab(pane, tab);
                e.setDropCompleted(true);
                e.consume();
                return;
            }
            if (draggedTab == null || !e.getDragboard().hasContent(TAB_DRAG)) return;
            int from = tabs.getTabs().indexOf(draggedTab);
            int over = tabs.getTabs().indexOf(tab);
            // Either can have closed mid-drag; a negative index would move the wrong tab.
            if (from < 0 || over < 0) return;
            boolean after = e.getX() > header.getBoundsInLocal().getWidth() / 2;
            moveTab(
                    from,
                    TabReorder.insertIndex(from, over, after, tabs.getTabs().size()));
            e.setDropCompleted(true);
            e.consume();
        });

        header.setOnDragDone(e -> {
            header.getStyleClass().remove("tab-dragging");
            draggedTab = null;
            for (javafx.scene.Node other : tabs.lookupAll(".tab")) clearDropMarks(other);
        });
    }

    private static void markDropSide(javafx.scene.Node header, boolean after) {
        header.getStyleClass().removeAll("tab-drop-before", "tab-drop-after");
        header.getStyleClass().add(after ? "tab-drop-after" : "tab-drop-before");
    }

    private static void clearDropMarks(javafx.scene.Node header) {
        header.getStyleClass().removeAll("tab-drop-before", "tab-drop-after", "tab-drop-into");
    }

    /** Marks a header as already carrying the gesture, so a retry does not install it twice. */
    private static final String TAB_DRAG_INSTALLED = "termina.tabDragInstalled";

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

        Menu newTabAs = new Menu(tr("menu.newTabProfile"), MenuIcons.newTab());
        newTabAs.getItems().add(new MenuItem(""));
        newTabAs.setOnShowing(e -> newTabAs.getItems().setAll(profileMenuItems()));

        ContextMenu menu = new ContextMenu(
                tabItem(tr("menu.newTab"), MenuIcons.newTab(), this::openTab),
                newTabAs,
                tabItem(tr("menu.duplicateTab"), MenuIcons.duplicate(), () -> duplicateTab(tab)),
                new SeparatorMenuItem(),
                tabItem(tr("menu.renameTab"), MenuIcons.rename(), () -> renameTab(tab)),
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
        if (!confirmClose(List.of(tab))) return;
        rememberClosed(tab);
        tabs.getTabs().remove(tab);
    }

    /** One question for the whole set: closing eight tabs should not ask eight times. */
    private void closeTabs(List<Tab> doomed) {
        if (doomed.isEmpty() || !confirmClose(doomed)) return;
        // Remembered in strip order, so reopening brings the rightmost back first and each lands
        // in a strip that already holds the ones to its left.
        for (Tab tab : doomed) rememberClosed(tab);
        for (Tab tab : doomed) tabs.getTabs().remove(tab);
    }

    // ---------------------------------------------------------------- tab marks

    private static final String TAB_MARKS = "termina.tabMarks";

    /**
     * A tab's mark: the dot after its title, and what it currently means.
     *
     * <p>Attached to the label when there is something to say and detached otherwise, never merely
     * hidden: {@code LabeledSkinBase} ignores a graphic that is unmanaged when it is set and does
     * not revisit it, so a dot created hidden and shown later was laid out by nothing, joined no
     * scene, and painted nowhere — measured as a mark the report called visible and the screenshot
     * did not show. Coloured in code from the terminal palette for the same reason: a stylesheet
     * never reaches a node that is not in the scene at the moment it is styled.
     */
    private static final class TabMarkState {
        final Label title;
        final javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(3);
        TabMarks.Mark mark = TabMarks.Mark.NONE;
        javafx.scene.paint.Color activity = javafx.scene.paint.Color.GRAY;
        javafx.scene.paint.Color bell = javafx.scene.paint.Color.GRAY;
        javafx.scene.paint.Color notify = javafx.scene.paint.Color.GRAY;

        /** Opening the tab counts as output: its first prompt is not news. */
        long lastOutputMs = System.currentTimeMillis();

        TabMarkState(Label title) {
            this.title = title;
            title.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            title.setGraphicTextGap(6);
        }

        /** Magenta for a notification, yellow for a bell, cyan for activity, in the theme's own rendering of each. */
        void setColours(TerminalPalette palette) {
            activity = palette.ansi()[6];
            bell = palette.ansi()[3];
            notify = palette.ansi()[5];
            paint();
        }

        void apply(TabMarks.Mark next) {
            if (next == mark) return;
            mark = next;
            paint();
            title.setGraphic(next == TabMarks.Mark.NONE ? null : dot);
        }

        private void paint() {
            dot.setFill(
                    switch (mark) {
                        case NOTIFY -> notify;
                        case BELL -> bell;
                        default -> activity;
                    });
        }
    }

    private static @Nullable TabMarkState marksOf(Tab tab) {
        return (TabMarkState) tab.getProperties().get(TAB_MARKS);
    }

    private boolean isSelected(Tab tab) {
        return tabs.getSelectionModel().getSelectedItem() == tab;
    }

    /** Each tab's mark, in strip order — for the capture hook. */
    public String tabMarkReport() {
        List<String> marks = new ArrayList<>();
        for (Tab tab : tabs.getTabs()) {
            TabMarkState state = marksOf(tab);
            marks.add(
                    state == null
                            ? "?"
                            : state.mark.name().toLowerCase(java.util.Locale.ROOT)
                                    + (state.title.getGraphic() == state.dot ? "(" + state.dot.getFill() + ")" : ""));
        }
        return "marks=" + marks;
    }

    /** Rings the bell in a tab by index, as a program would — for the capture hook. */
    public void ringBellInTabForCapture(int index) {
        if (index < 0 || index >= tabs.getTabs().size()) return;
        terminalOf(tabs.getTabs().get(index)).getSession().send(new byte[] {7});
    }

    /** Runs a command in a tab by index, without selecting it — for the capture hook. */
    public void typeInTabForCapture(int index, String text) {
        if (index < 0 || index >= tabs.getTabs().size()) return;
        terminalOf(tabs.getTabs().get(index)).getSession().sendString(text);
    }

    // ---------------------------------------------------------------- shortcut reference

    /**
     * The menu each command is listed under, by id: its own menu, or for one with no item — the
     * zoom row's, the tab and profile chords — the menu it belongs to. The reference and the
     * keyboard settings both group by it.
     */
    private final Map<String, String> bindingGroups = new java.util.HashMap<>();

    private MenuAction registerIn(String group, MenuAction action) {
        bindingGroups.putIfAbsent(action.id(), group);
        return register(action);
    }

    private @Nullable ShortcutsWindow shortcutsWindow;

    private void showShortcuts() {
        shortcutsWindow().show(stage, shortcuts());
    }

    /** The shortcut reference, made on first use. */
    private ShortcutsWindow shortcutsWindow() {
        ShortcutsWindow window = shortcutsWindow;
        if (window == null) shortcutsWindow = window = new ShortcutsWindow();
        return window;
    }

    /** Opens the reference, filters it, prints its rows and returns its scene — for the capture hook. */
    public javafx.scene.Scene showShortcutsForCapture(String query) {
        showShortcuts();
        ShortcutsWindow window = shortcutsWindow();
        if (query != null && !query.isBlank()) window.setQueryForCapture(query);
        System.out.println("[capture] shortcuts " + String.join(" | ", window.rows()));
        return window.scene();
    }

    /**
     * Every shortcut this window answers to, built from what it actually has.
     *
     * <p>The menus first, so the list reads in menu order and says what the menus say; then the
     * bindings that have no item of their own — the zoom row's, the tab and profile chords — under
     * the menu they belong to; then the keys the terminal view handles by itself, which no menu
     * lists because they are not commands.
     */
    List<ShortcutReference.Shortcut> shortcuts() {
        List<ShortcutReference.Shortcut> out = new ArrayList<>();
        java.util.Set<KeyCombination> listed = new java.util.HashSet<>();
        for (Menu menu : menuBar.getMenus()) {
            for (MenuItem item : menu.getItems()) {
                if (item.getAccelerator() == null || item.getText() == null) continue;
                out.add(new ShortcutReference.Shortcut(
                        menu.getText(), item.getText(), item.getAccelerator().getDisplayText()));
                listed.add(item.getAccelerator());
            }
        }
        for (MenuAction binding : bindings) {
            KeyCombination chord = binding.accelerator();
            if (chord == null || !listed.add(chord)) continue;
            out.add(new ShortcutReference.Shortcut(
                    bindingGroups.getOrDefault(binding.id(), tr("menu.window")),
                    binding.label(),
                    chord.getDisplayText()));
        }
        String terminal = tr("shortcuts.group.terminal");
        out.add(new ShortcutReference.Shortcut(terminal, tr("shortcuts.pageUp"), shifted(KeyCode.PAGE_UP)));
        out.add(new ShortcutReference.Shortcut(terminal, tr("shortcuts.pageDown"), shifted(KeyCode.PAGE_DOWN)));
        out.add(new ShortcutReference.Shortcut(terminal, tr("shortcuts.top"), shifted(KeyCode.HOME)));
        out.add(new ShortcutReference.Shortcut(terminal, tr("shortcuts.bottom"), shifted(KeyCode.END)));
        out.add(new ShortcutReference.Shortcut(terminal, tr("shortcuts.pasteInsert"), shifted(KeyCode.INSERT)));
        out.add(new ShortcutReference.Shortcut(
                terminal,
                tr("shortcuts.copyInsert"),
                new KeyCodeCombination(KeyCode.INSERT, KeyCombination.CONTROL_DOWN).getDisplayText()));
        out.add(new ShortcutReference.Shortcut(
                terminal,
                tr(PrimarySelection.exists() ? "shortcuts.pasteSelection" : "shortcuts.pasteClipboard"),
                tr("shortcuts.middleClick")));
        out.add(new ShortcutReference.Shortcut(
                terminal, tr("shortcuts.openLink"), tr("shortcuts.modifierClick", MAC ? "Cmd" : "Ctrl")));
        out.add(new ShortcutReference.Shortcut(terminal, tr("shortcuts.selectionBypass"), tr("shortcuts.shiftDrag")));
        out.add(new ShortcutReference.Shortcut(terminal, tr("shortcuts.renameTab"), tr("shortcuts.doubleClick")));
        return out;
    }

    private static String shifted(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.SHIFT_DOWN).getDisplayText();
    }

    // ---------------------------------------------------------------- rename, reopen, duplicate

    /**
     * The tabs this window has closed on purpose. A shell that exited on its own is not here: the
     * user ended it from inside, and bringing it back unasked would be a tab nobody closed.
     */
    private final ClosedTabs closedTabs = new ClosedTabs();

    private void rememberClosed(Tab tab) {
        PaneGroup group = groupOf(tab);
        if (group.panes().isEmpty()) return;
        closedTabs.remember(
                new ClosedTabs.Entry(group.layout(this::leafOf), tabs.getTabs().indexOf(tab)));
    }

    /** A pane as Reopen Closed Tab will start it again: its shell, where it is now, its name. */
    private PaneLayout.Leaf leafOf(TerminalView pane) {
        FxTerminalDisplay display = pane.getDisplay();
        return new PaneLayout.Leaf(
                ClosedTabs.inDirectory(pane.launchOptions(), display.getCwd()), display.getUserTitle());
    }

    /**
     * Brings back the most recently closed tab: each pane's shell in its last directory, the panes
     * arranged as they were with the same one active, and the tab in its old place.
     */
    public void reopenClosedTab() {
        closedTabs.pop().ifPresent(this::reopen);
    }

    private void reopen(ClosedTabs.Entry entry) {
        int index = ClosedTabs.reopenIndex(entry.index(), tabs.getTabs().size());
        if (entry.layout().root() instanceof PaneLayout.Leaf leaf) {
            openTab(leaf.options(), index, leaf.userTitle());
            return;
        }
        Tab tab = new Tab();
        PaneGroup group = PaneGroup.of(entry.layout(), leaf -> {
            TerminalView pane = createPane(leaf.options(), tab);
            pane.getDisplay().setUserTitle(leaf.userTitle());
            return pane;
        });
        addTab(tab, group, index);
        // Splits made in this pulse have no skin and their panes are not in the scene yet; laid out
        // first, every shell starts at its real size. See splitActivePane.
        tabs.applyCss();
        tabs.layout();
        for (TerminalView pane : new ArrayList<>(group.panes())) {
            try {
                pane.start();
            } catch (IOException e) {
                reportShellFailure(pane.launchOptions(), e, false);
                group.remove(pane);
                pane.close();
                if (group.panes().isEmpty()) {
                    tabs.getTabs().remove(tab);
                    return;
                }
                continue;
            }
            endPaneWithItsShell(tab, pane);
        }
        TerminalView active = group.active();
        if (active != null) Platform.runLater(active::requestFocus);
    }

    /** Opens a second tab like this one — same shell, where it is now — right after it. */
    private void duplicateTab(Tab tab) {
        TerminalView terminal = terminalOf(tab);
        if (terminal == null) return;
        FxTerminalDisplay display = terminal.getDisplay();
        openTab(
                ClosedTabs.inDirectory(terminal.launchOptions(), display.getCwd()),
                tabs.getTabs().indexOf(tab) + 1,
                display.getUserTitle());
    }

    /** Asks for a name and sets it; an empty answer clears the name, cancelling changes nothing. */
    private void renameTab(Tab tab) {
        TerminalView terminal = terminalOf(tab);
        if (terminal == null) return;
        FxTerminalDisplay display = terminal.getDisplay();
        renamer.apply(display.getUserTitle(), display.automaticTabTitle()).ifPresent(display::setUserTitle);
    }

    /** Supplies the name. Replaced by the capture hook, which cannot type into a modal dialog. */
    private java.util.function.BiFunction<String, String, java.util.Optional<String>> renamer = this::askForTabName;

    private java.util.Optional<String> askForTabName(String current, String automatic) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(current);
        dialog.initOwner(stage);
        dialog.setTitle(com.termina.AppInfo.NAME);
        dialog.setHeaderText(tr("dialog.renameTab"));
        dialog.setContentText(tr("dialog.renameTabDetail"));
        dialog.setGraphic(null);
        // The automatic title as the placeholder, so an empty field shows what clearing gives.
        dialog.getEditor().setPromptText(automatic);
        javafx.scene.Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (ok instanceof Button button) button.setText(tr("dialog.rename"));
        return dialog.showAndWait().map(String::trim);
    }

    private void withActiveTab(java.util.function.Consumer<Tab> action) {
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        if (selected != null) action.accept(selected);
    }

    /** Names the active tab without the dialog — for the capture hook. */
    public void renameActiveTabForCapture(String name) {
        renamer = (current, automatic) -> java.util.Optional.ofNullable(name);
        withActiveTab(this::renameTab);
    }

    /** Duplicates the active tab — for the capture hook. */
    public void duplicateActiveTabForCapture() {
        withActiveTab(this::duplicateTab);
    }

    /** How many closed tabs could be reopened — for the capture hook. */
    public int closedTabCount() {
        return closedTabs.size();
    }

    /**
     * Asks before ending programs, and only then.
     *
     * <p>A tab always has a shell, so the question is whether the shell has children — an idle
     * prompt has none. Asking on every close regardless would be the kind of confirmation people
     * learn to dismiss without reading, which is worse than not asking.
     */
    private boolean confirmClose(List<Tab> doomed) {
        List<TerminalView> panes = new ArrayList<>();
        for (Tab tab : doomed) panes.addAll(groupOf(tab).panes());
        return confirmCloseTerminals(panes, doomed.size());
    }

    /** The same question for a set of panes, which is what closing one pane of a split asks. */
    private boolean confirmCloseTerminals(List<TerminalView> doomed, int tabCount) {
        if (!settings.confirmClose()) return true;
        List<String> running = new ArrayList<>();
        for (TerminalView terminal : doomed) {
            if (terminal == null || !terminal.isStarted()) continue;
            running.addAll(RunningProgram.in(terminal.getSession().pid()));
        }
        if (running.isEmpty()) return true;
        return closeConfirmer.test(running, tabCount);
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

    /**
     * Asks before a paste that contains a line break, when the setting says to.
     *
     * <p>At a prompt every line of a paste runs the moment it lands, which is how a copied snippet
     * with a stray newline becomes an executed command. Paste is the default button all the same:
     * most multi-line pastes are meant, and the dialog's job is to show the text, not to stall it.
     * Replaced by the capture hook, which cannot click a modal dialog.
     */
    private boolean confirmPaste(String text) {
        if (!settings.confirmPaste()) return true;
        return pasteConfirmer.test(text);
    }

    private java.util.function.Predicate<String> pasteConfirmer = this::askBeforePasting;

    /** @param answer what the paste prompt should return, for the capture hook */
    public void setPasteAnswerForCapture(boolean answer) {
        pasteConfirmer = text -> {
            System.out.println("[capture] paste prompt for " + lineCount(text) + " lines -> " + answer);
            return answer;
        };
    }

    /** Lines a paste holds, as the dialog counts them: a trailing break still ends a line. */
    static int lineCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) count++;
        }
        return count;
    }

    private boolean askBeforePasting(String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(com.termina.AppInfo.NAME);
        alert.setHeaderText(tr("dialog.pasteMultiline", lineCount(text)));
        alert.setContentText(tr("dialog.pasteMultilineDetail"));
        // The text itself, so the decision is made on what will actually be sent rather than on
        // a count. Monospace and bounded: a thousand-line paste must not become a screen-high box.
        javafx.scene.control.TextArea preview = new javafx.scene.control.TextArea(text);
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.getStyleClass().add("paste-preview");
        preview.setPrefRowCount(Math.min(12, Math.max(3, lineCount(text))));
        alert.getDialogPane().setExpandableContent(preview);
        alert.getDialogPane().setExpanded(true);
        ButtonType paste = new ButtonType(tr("dialog.paste"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, paste);
        return alert.showAndWait().orElse(cancel) == paste;
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
    public javafx.scene.@Nullable Scene showTabMenuForCapture(int index, double screenX, double screenY) {
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

    /**
     * Close Tab's chord. In a split tab it closes the active pane, as iTerm2's and Windows
     * Terminal's do — the pane is what the user is in, and the menu item says so (see the File
     * menu's showing handler). The tab's own close button and menu still close every pane.
     */
    public void closeCurrentTab() {
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        if (groupOf(selected).isSplit()) closeActivePane();
        else closeTab(selected);
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

    /** A tab's active pane: what its title, Copy, Find and a new tab's directory all go by. */
    private static TerminalView terminalOf(Tab tab) {
        return groupOf(tab).active();
    }

    private static PaneGroup groupOf(Tab tab) {
        return (PaneGroup) tab.getUserData();
    }

    /** Every live terminal in this window. */
    /** The terminal of the selected tab, or null when there is none. */
    public @Nullable TerminalView selectedTerminal() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab == null ? null : terminalOf(tab);
    }

    /** Every pane of every tab — what a settings change reaches, and what the window owns. */
    public List<TerminalView> terminals() {
        List<TerminalView> views = new ArrayList<>();
        for (Tab tab : tabs.getTabs()) views.addAll(groupOf(tab).panes());
        return views;
    }

    public @Nullable TerminalView activeTerminal() {
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

    /**
     * How much of each tab header the drag source actually covers.
     *
     * <p>Reports the header's width and whether the gesture is armed on it. The two failure modes —
     * no gesture at all, and a gesture on something far smaller than the tab — look identical from
     * the outside, and the second is what this feature shipped as.
     */
    public String tabDragReport() {
        StringBuilder out = new StringBuilder("tabDrag ");
        for (javafx.scene.Node header : tabs.lookupAll(".tab")) {
            out.append(String.format(
                    "[header=%.0f armed=%s] ",
                    header.getBoundsInLocal().getWidth(), header.getOnDragDetected() != null));
        }
        return out.toString().trim();
    }

    /** The space the platform reserves for its window buttons, which the header bar pads for. */
    private String insetReport() {
        javafx.geometry.Dimension2D left = HeaderBar.getLeftSystemInset(stage);
        javafx.geometry.Dimension2D right = HeaderBar.getRightSystemInset(stage);
        return Math.round(left.getWidth()) + "x" + Math.round(left.getHeight())
                + "/" + Math.round(right.getWidth()) + "x" + Math.round(right.getHeight())
                + " minH=" + Math.round(HeaderBar.getSystemMinHeight(stage));
    }

    public String layoutReport() {
        javafx.scene.Node header = tabs.lookup(".tab-header-area");
        javafx.geometry.Rectangle2D screenBounds =
                javafx.stage.Screen.getPrimary().getBounds();
        return "fullScreen=" + stage.isFullScreen()
                + " style=" + stage.getStyle()
                + " headerBar h=" + (headerBar == null ? "absent" : headerBar.getHeight())
                + " systemInsets=" + (headerBar == null ? "-" : insetReport())
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

    /** The window's own title bar, holding the menu bar; null when the platform draws one. */
    private final @Nullable HeaderBar headerBar;

    private final Button newTabButton = new Button();

    /** Opens the profile menu — the other shells this machine has. */
    private final Button profileButton = new Button();

    /**
     * The two strip buttons as one node.
     *
     * <p>Boxed rather than positioned individually because they are shown, hidden and height-bound
     * together, and because the width the tab arithmetic has to keep clear is the width of both.
     */
    private final javafx.scene.layout.HBox tabStripButtons = new javafx.scene.layout.HBox(newTabButton, profileButton);

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

    /** The window's position ignoring maximization; NaN until the stage has reported one. */
    private double restoredX = Double.NaN;

    private double restoredY = Double.NaN;

    /**
     * Width kept clear at the right end of the strip for the two buttons that float over it.
     *
     * <p>Raised from 40 when the profile chevron joined the {@code +}. It is a reservation rather
     * than a measurement: the tabs are sized before the buttons are laid out, so reading their
     * width here would be reading it a frame late, on the frame that matters.
     */
    private static final double NEW_TAB_RESERVED = 62;

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
            profileButton.prefHeightProperty().bind(header.heightProperty());
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
        // The position as well, on the same terms: a maximized window reports the screen's corner.
        stage.xProperty().addListener((o, was, now) -> {
            if (!stage.isMaximized()) restoredX = now.doubleValue();
        });
        stage.yProperty().addListener((o, was, now) -> {
            if (!stage.isMaximized()) restoredY = now.doubleValue();
        });
        // Hiding, not hidden: WindowManager's own onHidden handler ends the app when the last
        // window goes, and the write has to have happened by then.
        stage.setOnHiding(e ->
                settings.setWindowGeometry(restoredX, restoredY, restoredWidth, restoredHeight, stage.isMaximized()));
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

    /**
     * The row that replaces the native title bar: menus at the left, the title in the middle, and
     * the window buttons wherever the platform puts them — the bar pads for them itself.
     *
     * <p>The bar's own background is the drag handle and its content is not, which is what makes
     * the menus clickable. The title is the exception: a title bar you cannot drag by its title is
     * a trap, so that one label is marked draggable.
     */
    private HeaderBar buildHeaderBar() {
        HeaderBar bar = new HeaderBar();
        // The control ships with no style class of its own; without this the CSS below matches
        // nothing and the menu bar's own background paints as a lighter block inside the row.
        bar.getStyleClass().add("header-bar");
        bar.setLeft(menuBar);
        Label title = new Label();
        title.getStyleClass().add("header-title");
        title.textProperty().bind(stage.titleProperty());
        HeaderBar.setDragType(title, HeaderDragType.DRAGGABLE);
        bar.setCenter(title);
        return bar;
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
                register(item("menu.newTab", MenuAction.appChord(KeyCode.T), this::openTab)),
                register(item("menu.newWindow", MenuAction.appChord(KeyCode.N), () -> windows.openWindow(this))),
                null,
                register(item("menu.closeTab", MenuAction.appChord(KeyCode.W), this::closeCurrentTab)),
                register(item("menu.closeWindow", MenuAction.closeWindowChord(), this::close)),
                register(reopenAction),
                null,
                register(item(
                        "menu.settings",
                        new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN),
                        () -> windows.showSettings(stage))),
                // No chord by default: an occasional command, and every free chord is one the shell
                // might want. The Keyboard page can give it one.
                register(item("menu.syncNow", () -> windows.syncSettingsNow(this::report))));

        // Added by hand rather than through menu(): a submenu is a Menu, not a MenuAction, and its
        // contents are not known until it is opened. Its entries reach the palette through
        // showPalette instead, by name, which is more use there than "New Tab with Profile 3".
        Menu newTabAs = new Menu(tr("menu.newTabProfile"), MenuIcons.newTab());
        // A Menu with no items renders as a dead entry with no arrow, and the items are only known
        // at show time — so it is seeded with one that is replaced before it is ever drawn.
        newTabAs.getItems().add(new MenuItem(""));
        newTabAs.setOnShowing(e -> newTabAs.getItems().setAll(profileMenuItems()));
        file.getItems().add(1, newTabAs);
        // Greyed when there is nothing to bring back — an item that does nothing when clicked is
        // worse than one that says it cannot.
        file.setOnShowing(e -> {
            for (MenuItem item : file.getItems()) {
                if (tr("menu.reopenClosedTab").equals(item.getText())) item.setDisable(closedTabs.isEmpty());
                // Says what its chord will do: in a split tab that is the active pane, not the tab.
                if (tr("menu.closeTab").equals(item.getText())
                        || tr("menu.closePane").equals(item.getText())) {
                    item.setText(activeTabIsSplit() ? tr("menu.closePane") : tr("menu.closeTab"));
                }
            }
        });

        // The chords the submenu advertises. They have to be registered here because the menu's own
        // accelerators never fire in this window — TerminalView consumes Ctrl+<key> first, which is
        // the whole reason bindings are a scene filter. A submenu item showing a chord that does
        // nothing would be worse than showing none.
        for (int number = 1; number <= TAB_CHORD_COUNT; number++) {
            int chosen = number;
            registerIn(
                    tr("menu.file"),
                    MenuAction.of(
                                    tr("menu.newTabProfileNumber", number),
                                    MenuAction.shiftChord(digitKey(number)),
                                    () -> openProfileByNumber(chosen))
                            .withId(PROFILE_CHORD_ID + number));
        }

        Menu edit = menu(
                tr("menu.edit"),
                register(item(
                        "menu.copy",
                        MenuAction.appChord(KeyCode.C),
                        () -> withActiveTerminal(TerminalView::copySelection))),
                register(item(
                        "menu.paste", MenuAction.appChord(KeyCode.V), () -> withActiveTerminal(TerminalView::paste))),
                register(item(
                        "menu.selectAll",
                        MenuAction.appChord(KeyCode.A),
                        () -> withActiveTerminal(TerminalView::selectAll))),
                null,
                register(item(
                        "menu.clearScrollback",
                        MenuAction.appChord(KeyCode.K),
                        () -> withActiveTerminal(TerminalView::clearScrollback))),
                null,
                register(item(
                        "menu.find",
                        MenuAction.appChord(KeyCode.F),
                        () -> withActiveTerminal(TerminalView::showSearch))),
                // GNOME Terminal's pair off macOS, Ctrl+Shift+G and H; the system's Cmd+G and
                // Cmd+Shift+G on it, where Cmd+H would hide the application.
                register(item(
                        "menu.findNext",
                        MenuAction.appChord(KeyCode.G),
                        () -> withActiveTerminal(terminal -> terminal.findNext(true)))),
                register(item(
                        "menu.findPrevious",
                        MAC ? MenuAction.shiftChord(KeyCode.G) : MenuAction.appChord(KeyCode.H),
                        () -> withActiveTerminal(terminal -> terminal.findNext(false)))),
                null,
                register(previousPromptAction),
                register(nextPromptAction),
                selectOutputAction);
        edit.setOnShowing(e -> {
            boolean marked = promptMarksAvailable();
            for (MenuItem item : edit.getItems()) {
                String text = item.getText();
                if (tr("menu.previousPrompt").equals(text)
                        || tr("menu.nextPrompt").equals(text)
                        || tr("menu.selectLastOutput").equals(text)) {
                    item.setDisable(!marked);
                }
            }
        });

        // The four view actions, built once and shown one of two ways. Where the row can be drawn
        // the View menu shows it instead of four items, and these keep their chords and their place
        // in the palette through command(), which is what records a command with no item of its own.
        // Under a system menu bar they are the items — see showsZoomRow.
        // EQUALS, not PLUS: on every common layout + is Shift+=, so the key JavaFX reports for
        // Ctrl and that key is EQUALS, and a menu saying "Ctrl++" named a chord no keyboard
        // produces. PLUS and the numeric keypad's ADD stay accepted in onKeyPressed.
        MenuAction zoomIn = item(
                "menu.zoomIn", new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.SHORTCUT_DOWN), () -> zoom(1));
        MenuAction zoomOut = item(
                "menu.zoomOut", new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN), () -> zoom(-1));
        MenuAction actualSize = item(
                "menu.actualSize",
                new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN),
                this::resetZoom);
        MenuAction fullScreen = item("menu.fullScreen", fullScreenChord(), this::toggleFullScreen);
        List<MenuAction> viewActions = List.of(zoomIn, zoomOut, actualSize, fullScreen);
        // Only the row's actions need recording here. As items they reach the palette the ordinary
        // way, by being in a menu, and recording them twice would list each of them twice.
        if (showsZoomRow(SYSTEM_MENU_BAR)) {
            viewActions.forEach(action -> {
                bindingGroups.put(action.id(), tr("menu.view"));
                command(action);
            });
        } else {
            viewActions.forEach(this::register);
        }

        List<@Nullable MenuAction> viewItems = new java.util.ArrayList<>();
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
            menuBarToggle = register(item(
                    "menu.hideMenuBar",
                    new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                    () -> settings.setShowMenuBar(!settings.showMenuBar())));
            viewItems.add(menuBarToggle);
        }
        // An ordinary binding since the keyboard settings arrived; keys.commandPalette is its line,
        // as it was when this was the only chord that could be changed. showPalette keeps it out of
        // its own list — a palette that offers to open the palette is noise in every search.
        viewItems.add(null);
        register(paletteAction);
        viewItems.add(paletteAction);
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
                register(
                        item("menu.nextTab", MenuAction.shiftChord(KeyCode.CLOSE_BRACKET), () -> selectRelativeTab(1))),
                register(item(
                        "menu.previousTab", MenuAction.shiftChord(KeyCode.OPEN_BRACKET), () -> selectRelativeTab(-1))),
                null,
                // Dragging is the usual way, but a tab strip that can only be reordered by mouse is
                // unreachable from the keyboard entirely.
                register(item(
                        "menu.moveTabLeft",
                        new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                        () -> moveSelectedTab(-1))),
                register(item(
                        "menu.moveTabRight",
                        new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                        () -> moveSelectedTab(1))),
                null,
                // No chords: neither has one anywhere else, and a tab's own menu is where the hand
                // goes. They are here for the palette, and so the keyboard can reach them at all.
                item("menu.renameTab", () -> withActiveTab(this::renameTab)),
                item("menu.duplicateTab", () -> withActiveTab(this::duplicateTab)),
                null,
                // iTerm2's Cmd+D and Cmd+Shift+D on macOS; Terminator's Ctrl+Shift+E and O
                // elsewhere, where Ctrl+Shift+D would be the pair's odd one out.
                register(item(
                        "menu.splitRight",
                        MAC
                                ? new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN)
                                : MenuAction.appChord(KeyCode.E),
                        () -> splitActivePane(javafx.geometry.Orientation.HORIZONTAL))),
                register(item(
                        "menu.splitDown",
                        MAC ? MenuAction.shiftChord(KeyCode.D) : MenuAction.appChord(KeyCode.O),
                        () -> splitActivePane(javafx.geometry.Orientation.VERTICAL))),
                // iTerm2's Cmd+Shift+Enter, and the same with Ctrl elsewhere — Ctrl+Shift+Z, tmux's
                // idea, is too close to an editor's redo to take from a program.
                register(item("menu.maximizePane", MenuAction.shiftChord(KeyCode.ENTER), this::toggleMaximizePane)),
                register(item("menu.movePaneToNewTab", () -> {
                    Tab tab = tabs.getSelectionModel().getSelectedItem();
                    if (tab != null) movePaneToNewTab(groupOf(tab).active());
                })));

        // Moving between panes: chords and palette entries, no menu items — four of them would be
        // most of the Window menu. iTerm2's Cmd+Option+arrows on macOS; the same with Shift added
        // elsewhere, where Ctrl+Alt+arrows switch workspaces in GNOME and Ctrl+Shift+arrows are
        // already Move Tab and the prompt jumps.
        for (PaneGroup.Direction direction : PaneGroup.Direction.values()) {
            KeyCode arrow = KeyCode.valueOf(direction.name());
            KeyCombination chord = MAC
                    ? new KeyCodeCombination(arrow, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN)
                    : new KeyCodeCombination(
                            arrow, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN, KeyCombination.SHIFT_DOWN);
            String key = "menu.focusPane" + direction.name().charAt(0)
                    + direction.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            MenuAction action = item(key, chord, () -> focusPane(direction));
            bindingGroups.put(action.id(), tr("menu.window"));
            command(action);
        }

        // After the Window menu's own items, so the keyboard settings list them last. Chords only: no menu items — nine
        // rows would be most of the Window menu, and all but the
        // first few dead whenever fewer tabs are open — and no palette entries here either. The
        // palette lists the tabs that exist, by title, when it opens (showPalette); nine numbered
        // rows that were mostly dead drowned the entries people actually searched for.
        for (int number = 1; number <= TAB_CHORD_COUNT; number++) {
            int selected = number;
            KeyCode digit = KeyCode.valueOf("DIGIT" + number);
            String title = number == TAB_CHORD_COUNT ? tr("menu.goToLastTab") : tr("menu.goToTab", number);
            registerIn(
                    tr("menu.window"),
                    MenuAction.of(title, MenuAction.tabChord(digit), () -> selectTabByNumber(selected))
                            .withId(TAB_CHORD_ID + number));
        }

        // Help is built by hand rather than through menu(), because its update entry is a MenuItem
        // this class relabels later. Its two actions are added to the palette explicitly.
        MenuAction about = registerIn(
                tr("menu.help"),
                MenuAction.of(tr("menu.about", com.termina.AppInfo.NAME), () -> windows.showAbout(stage))
                        .withId("about"));
        MenuAction checkForUpdates = registerIn(
                tr("menu.help"), item("menu.checkForUpdates", () -> windows.checkForUpdatesNow(this::report)));
        updateItem = menuItem(checkForUpdates);
        Menu help = new Menu(tr("menu.help"));
        // Cmd+/ on macOS (Cmd+Shift+/ is the system's own Help search); Ctrl+Shift+/ elsewhere,
        // which is the "?" key under the usual modifier.
        MenuAction shortcutsRef = registerIn(
                tr("menu.help"),
                item("menu.keyboardShortcuts", MenuAction.appChord(KeyCode.SLASH), this::showShortcuts));
        commands.add(shortcutsRef.withKeywords(tr("menu.help")));
        commands.add(about.withKeywords(tr("menu.help")));
        commands.add(checkForUpdates.withKeywords(tr("menu.help")));
        help.getItems().addAll(menuItem(shortcutsRef), new SeparatorMenuItem(), menuItem(about), updateItem);

        // The Help menu is where an available update shows up. Termina has no status bar, which is
        // where Editora puts its badge, and a banner over the terminal would cost a row of the
        // thing the user is actually looking at.
        windows.addUpdateListener(this::refreshUpdateItem);
        refreshUpdateItem();

        // Last, once every command is in the table: a stored chord is placed against all of them.
        applyKeybindings();

        bar.getMenus().addAll(file, edit, view, window, help);
        return bar;
    }

    /**
     * A menu action whose label and one-line description both come from the catalogue, as
     * {@code key} and {@code key.desc}. The description is what the palette shows under the list
     * for the highlighted row.
     */
    private static MenuAction item(String key, @Nullable KeyCombination chord, Runnable action) {
        return MenuAction.of(tr(key), chord, action)
                .describedAs(description(key))
                .withId(idOf(key));
    }

    /** A command's id is its catalogue key without the menu's prefix: {@code menu.newTab} is {@code newTab}. */
    static String idOf(String key) {
        return key.startsWith("menu.") ? key.substring("menu.".length()) : key;
    }

    private static MenuAction item(String key, Runnable action) {
        return item(key, null, action);
    }

    /** The catalogue's description for a menu key, or empty when it has none. */
    static String description(String key) {
        String desc = tr(key + ".desc");
        return desc.equals(key + ".desc") ? "" : desc;
    }

    /** The id prefix of the nine profile chords: {@code newTabProfile1} opens the first profile. */
    static final String PROFILE_CHORD_ID = "newTabProfile";

    /** The id prefix of the nine tab chords: {@code goToTab9} is the last tab. */
    static final String TAB_CHORD_ID = "goToTab";

    /**
     * Puts every command on the chord the user's key bindings give it: the filter's table, each
     * menu item's accelerator, the palette's chip and the tab strip's tooltips. Live, from {@link
     * #applySettings}, so a rebind in the settings window is the next keystroke's behaviour.
     */
    private void applyKeybindings() {
        chords = Keybindings.resolve(defaultChords(), settings.keyBindings(), MAC);
        bindings.clear();
        for (MenuAction action : table) {
            KeyCombination chord = chords.get(action.id());
            if (chord != null) bindings.add(action.withAccelerator(chord));
        }
        itemsById.forEach((id, items) -> {
            KeyCombination chord = chords.get(id);
            for (MenuItem item : items) {
                if (!java.util.Objects.equals(item.getAccelerator(), chord)) item.setAccelerator(chord);
            }
        });
        if (palette != null) palette.setOpener(chordOf(paletteAction.id()));
        newTabTipText.set(withChord("menu.newTab", "tooltip.newTab", chordOf("newTab")));
        closeTabTipText.set(withChord("menu.closeTab", "tooltip.closeTab", chordOf("closeTab")));
    }

    /** "New Tab (Ctrl+Shift+T)", or "New Tab" once the user has taken its chord away. */
    private static String withChord(String labelKey, String withChordKey, @Nullable KeyCombination chord) {
        return chord == null ? tr(labelKey) : tr(withChordKey, chord.getDisplayText());
    }

    /** Every command's default chord, by id, in table order. */
    private Map<String, KeyCombination> defaultChords() {
        Map<String, KeyCombination> defaults = new java.util.LinkedHashMap<>();
        for (MenuAction action : table) defaults.put(action.id(), action.accelerator());
        return defaults;
    }

    /** The chord command {@code id} is on now, or null when it has none. */
    @Nullable
    KeyCombination chordOf(@Nullable String id) {
        return id == null ? null : chords.get(id);
    }

    /** {@code action} on the chord it is on now, for a list that shows it — the palette's. */
    private MenuAction bound(MenuAction action) {
        return action.id() != null && chords.containsKey(action.id())
                ? action.withAccelerator(chordOf(action.id()))
                : action;
    }

    /**
     * The commands the keyboard settings list, in table order, each under its menu. The same in
     * every window — the table is built from constants — so the first window answers for all.
     */
    List<Keybindings.Command> keyboardCommands() {
        List<Keybindings.Command> out = new ArrayList<>();
        for (MenuAction action : table) {
            out.add(new Keybindings.Command(
                    action.requireId(),
                    bindingGroups.getOrDefault(action.id(), tr("menu.window")),
                    action.label(),
                    action.accelerator()));
        }
        return out;
    }

    private javafx.scene.control.@Nullable MenuItem updateItem;

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
     * Says that opening a link failed, and what to do about it.
     *
     * <p>Naming only the command was not enough to act on: the overwhelmingly common cause is a
     * command that names nothing on this machine — an application's display name typed into a field
     * that wants a program — and "could not run Editora" reads as the app being broken rather than
     * as the setting being wrong. The two messages differ because the fixes do.
     */
    private void reportOpenFailed(String command, boolean missing) {
        String message = missing ? tr("status.openNotFound", command) : tr("status.openFailed", command);
        javafx.application.Platform.runLater(() -> report(message));
    }

    /** The live {@link #report} dialog, so a follow-up message replaces it instead of stacking. */
    private @Nullable Alert reportAlert;

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

    /** The View menu's hide/show entry, relabelled for the palette to say which one it is. */
    private @Nullable MenuAction menuBarToggle;

    /**
     * The number whose chord selects a tab, or 0 when none does — the inverse of {@link
     * #tabIndexFor}. The last tab answers to 9 whatever its index; a tab past the eighth that is
     * not last has no chord.
     */
    static int tabNumberFor(int index, int tabCount) {
        if (index < 0 || index >= tabCount) return 0;
        if (index == tabCount - 1) return TAB_CHORD_COUNT;
        return index < TAB_CHORD_COUNT - 1 ? index + 1 : 0;
    }

    private @Nullable KeyCombination tabChordFor(int index, int tabCount) {
        int number = tabNumberFor(index, tabCount);
        return number == 0 ? null : chordOf(TAB_CHORD_ID + number);
    }

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
        commands.add(action.withKeywords(bindingGroups.getOrDefault(action.id(), "")));
    }

    /**
     * Enters a command in the table, on its default chord. Idempotent by id, because a menu's
     * actions arrive here twice — once as they are built, once from {@link #menu}.
     */
    private MenuAction register(MenuAction action) {
        String id = action.requireId();
        if (table.stream().anyMatch(a -> id.equals(a.id()))) return action;
        table.add(action);
        // Loud, because the quiet form already shipped: a second binding on a chord is never
        // reached, while its menu item and palette row go on advertising it. The user's own
        // bindings cannot do this — Keybindings.resolve places them — but two defaults can.
        List<KeyCombination> defaults = table.stream()
                .map(MenuAction::accelerator)
                .map(chord -> chord == null ? null : Keybindings.normalize(chord, MAC))
                .toList();
        List<KeyCombination> duplicates = MenuAction.duplicateAccelerators(defaults);
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("chord bound twice: " + duplicates + " (" + action.label() + ")");
        }
        return action;
    }

    /** The menu item for {@code action}, remembered so its accelerator can follow the command. */
    private MenuItem menuItem(MenuAction action) {
        MenuItem item = action.toMenuItem();
        if (action.id() != null)
            itemsById.computeIfAbsent(action.id(), id -> new ArrayList<>()).add(item);
        return item;
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
    private Menu menu(String title, @Nullable MenuAction... actions) {
        Menu menu = new Menu(title);
        for (MenuAction action : actions) {
            menu.getItems().add(action == null ? new SeparatorMenuItem() : menuItem(action));
            // Findable by the menu's name too: "file new" reaches New Tab.
            if (action != null) {
                commands.add(action.withKeywords(title));
                bindingGroups.putIfAbsent(action.id(), title);
                register(action);
            }
        }
        return menu;
    }

    private void onKeyPressed(KeyEvent e) {
        // While the palette is open it owns the keyboard. This filter is on the scene, so without
        // this it would still be matching chords against keystrokes meant for the query field.
        // Not consumed: the palette's own filter is downstream and still needs them.
        if (palette != null && palette.isShowing()) return;

        boolean inTextField = stage.getScene() != null
                && stage.getScene().getFocusOwner() instanceof javafx.scene.control.TextInputControl;
        for (MenuAction binding : bindings) {
            if (binding.matches(e)) {
                // Not consumed, so the field gets it: Cmd+C in the find bar copies the query.
                if (inTextField && yieldsToTextField(binding.id())) return;
                binding.action().run();
                swallowNextTyped = true;
                e.consume();
                return;
            }
        }
        // The bound chord is Ctrl+=; a layout with a real + key, and the keypad's +, zoom too —
        // while Zoom In is still there, since a user who moved it did not ask for a second chord.
        if (e.isShortcutDown()
                && (e.getCode() == KeyCode.PLUS || e.getCode() == KeyCode.ADD)
                && Keybindings.same(chordOf("zoomIn"), defaultChords().get("zoomIn"), MAC)) {
            zoom(1);
            e.consume();
        }
    }

    /**
     * Whether a command steps aside while a text field in the window has the keyboard.
     *
     * <p>The commands whose default chords are a text field's own editing keys on macOS — Cmd+C,
     * Cmd+V, Cmd+A, and Cmd+Z for Reopen Closed Tab — and the same commands elsewhere, where
     * Ctrl+Shift+Z is a field's redo. In the find bar they act on the query; acting on the terminal
     * behind it instead would copy a selection nobody can see and paste into the shell.
     */
    static boolean yieldsToTextField(@Nullable String id) {
        return switch (id == null ? "" : id) {
            case "copy", "paste", "selectAll", "reopenClosedTab" -> true;
            default -> false;
        };
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
        applyTabBarVisibility();
        applyMenuBarVisibility();
        // Live, and on the stage rather than the scene: a decorated window's native background is
        // opaque, so scene-level transparency needs StageStyle.TRANSPARENT — which takes the title
        // bar with it and can only be chosen before the window is shown. Stage opacity keeps both
        // the decorations and the ability to change your mind without a restart. The cost is that
        // it fades the glyphs along with the background, which the floor in WindowOpacity accounts
        // for.
        stage.setOpacity(WindowOpacity.clamp(settings.windowOpacity()));
        applyKeybindings();
        for (TerminalView terminal : terminals()) applySettingsTo(terminal);
        applyWindowTheme();
    }

    /**
     * The colours the window itself takes from a theme — the scene behind the panes, the window
     * buttons, each tab's marks — from the theme of the pane being worked in, since with per-profile
     * themes a window can hold several. Re-applied as the active pane or tab changes. The control
     * stylesheet is the application's and stays with the global theme.
     */
    private void applyWindowTheme() {
        TerminalView active = activeTerminal();
        ThemeEntry theme = active == null ? Themes.byId(settings.themeId()) : themeOf(active);
        if (stage.getScene() != null) stage.getScene().setFill(theme.palette().background());
        // The window buttons are drawn by JavaFX where the platform does not provide them, and
        // they follow the scene's colour scheme unless told otherwise. The scene's is the OS's,
        // not ours — a dark theme on a light desktop would get dark glyphs on a dark header.
        if (headerBar != null)
            HeaderBar.setSystemColorScheme(stage, theme.isDark() ? ColorScheme.DARK : ColorScheme.LIGHT);
        for (Tab tab : tabs.getTabs()) {
            TabMarkState marks = marksOf(tab);
            TerminalView pane = terminalOf(tab);
            if (marks != null) marks.setColours((pane == null ? theme : themeOf(pane)).palette());
        }
    }

    /** The theme a pane draws in: its profile's, if it names one that exists, else the global one. */
    private ThemeEntry themeOf(TerminalView terminal) {
        return themeFor(settings.profileStyle(terminal.launchOptions().profileId()), settings.themeId());
    }

    /** Pure: a profile's theme id if it names a theme, otherwise the global one. */
    static ThemeEntry themeFor(com.termina.config.Settings.ProfileStyle style, String globalThemeId) {
        if (!style.themeId().isEmpty()) {
            ThemeEntry own = Themes.byId(style.themeId());
            if (own.id().equals(style.themeId())) return own;
        }
        return Themes.byId(globalThemeId);
    }

    private void applySettingsTo(TerminalView terminal) {
        com.termina.config.Settings.ProfileStyle style =
                settings.profileStyle(terminal.launchOptions().profileId());
        terminal.setPalette(themeOf(terminal).palette());
        terminal.setFontFamily(style.fontFamily().isEmpty() ? settings.fontFamily() : style.fontFamily());
        terminal.setFontSize(settings.effectiveFontSize(style));
        terminal.setPreferredCursor(settings.cursorShape());
        terminal.setAltIsMeta(settings.altIsMeta());
        terminal.setBellEnabled(settings.bell());
        terminal.setClipboardWrites(settings.clipboardWrite());
        terminal.setCopyOnSelect(settings.copyOnSelect());
        terminal.setTypeAheadAllowed(settings.typeAhead());
        terminal.setScrollBarEnabled(settings.showScrollBar());
    }

    /**
     * Opens the palette over this window.
     *
     * <p>The theme entries are appended rather than living in a menu: a menu listing every theme is
     * a submenu nobody opens, while a palette is exactly where "make this one darker" belongs.
     */
    private void showPalette() {
        List<MenuAction> all = new ArrayList<>();
        for (MenuAction command : commands) {
            if (!command.sameActionAs(paletteAction)) all.add(bound(command));
        }
        // The View menu's item can only say "Hide", since the menu is inside the bar; the palette
        // is reachable either way, so its row has to name the move that is actually available.
        if (menuBarToggle != null) {
            for (int at = 0; at < all.size(); at++) {
                if (!all.get(at).sameActionAs(menuBarToggle)) continue;
                String key = settings.showMenuBar() ? "menu.hideMenuBar" : "menu.showMenuBar";
                all.set(
                        at,
                        new MenuAction(
                                tr(key),
                                chordOf(menuBarToggle.id()),
                                menuBarToggle.action(),
                                description(key),
                                all.get(at).keywords(),
                                menuBarToggle.id()));
            }
        }
        // Close Tab closes the active pane of a split tab, and the row says so.
        if (activeTabIsSplit()) {
            for (int at = 0; at < all.size(); at++) {
                MenuAction row = all.get(at);
                if (!"closeTab".equals(row.id())) continue;
                all.set(
                        at,
                        new MenuAction(
                                tr("menu.closePane"),
                                row.accelerator(),
                                row.action(),
                                description("menu.closePane"),
                                row.keywords(),
                                row.id()));
            }
        }
        // The tabs that exist, by title, each with the chord that reaches it — rather than nine
        // numbered rows of which most were dead.
        List<Tab> open = tabs.getTabs();
        for (int index = 0; index < open.size(); index++) {
            int selected = index;
            all.add(MenuAction.of(
                            tr("palette.goToTab", index + 1, label(open.get(index))),
                            tabChordFor(index, open.size()),
                            () -> tabs.getSelectionModel().select(selected))
                    .describedAs(tr("palette.goToTab.desc"))
                    .withKeywords(tr("palette.group.tabs")));
        }
        // A split tab's active pane can go to any other tab, by name, for the reason the tab rows
        // are by name.
        Tab current = tabs.getSelectionModel().getSelectedItem();
        if (current != null && groupOf(current).isSplit()) {
            TerminalView pane = groupOf(current).active();
            for (int index = 0; index < open.size(); index++) {
                Tab target = open.get(index);
                if (target == current) continue;
                all.add(MenuAction.of(
                                tr("palette.movePaneToTab", index + 1, label(target)),
                                () -> movePaneToTab(pane, target))
                        .describedAs(tr("palette.movePaneToTab.desc"))
                        .withKeywords(tr("palette.group.tabs")));
            }
        }
        // By name rather than by number. The submenu's chords are "profile 3"; here there is room to
        // say "Ubuntu (WSL)", which is the only form anybody would search for.
        for (com.termina.shell.Profile profile : windows.profiles().all()) {
            all.add(MenuAction.of(tr("palette.newTabProfile", profile.name()), () -> openTab(profile))
                    .describedAs(tr("palette.newTabProfile.desc"))
                    .withKeywords(tr("palette.group.profiles")));
        }
        for (ThemeEntry theme : Themes.all()) {
            // Setting it is enough: Settings.onChange re-applies to every window already.
            all.add(MenuAction.of(tr("palette.theme", theme.displayName()), () -> settings.setThemeId(theme.id()))
                    .describedAs(tr("palette.theme.desc"))
                    .withKeywords(tr("palette.group.themes")));
        }
        // Reopen Closed Tab is listed greyed, not dropped, while there is nothing to reopen: a
        // command that is simply missing reads as the palette not knowing it. The prompt commands
        // likewise, until the shell has marked a prompt — with the reason, since the fix is in the
        // shell's configuration and nothing on screen would suggest it.
        boolean marked = promptMarksAvailable();
        palette.show(
                all,
                action -> !(action.sameActionAs(reopenAction) && closedTabs.isEmpty())
                        && !(needsPromptMarks(action) && !marked),
                action -> {
                    if (action.sameActionAs(reopenAction)) return tr("palette.disabled.nothingToReopen");
                    if (needsPromptMarks(action)) return tr("palette.disabled.noPromptMarks");
                    return null;
                });
    }

    private boolean needsPromptMarks(MenuAction action) {
        return action.sameActionAs(previousPromptAction)
                || action.sameActionAs(nextPromptAction)
                || action.sameActionAs(selectOutputAction);
    }

    private boolean promptMarksAvailable() {
        TerminalView terminal = activeTerminal();
        return terminal != null && terminal.hasPromptMarks();
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
        javafx.scene.Node target = switch (what) {
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

    /**
     * Fires chords at the open palette by {@link KeyChord} token ({@code C-n,C-n,C-p}), reporting
     * the highlighted row after each with its matched characters bracketed. This is how the Emacs
     * navigation is verified: reasoning about which filter sees Ctrl+N first is not evidence.
     */
    public String firePaletteKeysForCapture(String tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            // Laid out first, as it would be by the time a hand reaches the next key: the capture
            // opens the palette and fires at it within one pulse, and a ListView asked to scroll
            // before it has a size scrolls to the wrong place.
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            boolean fired = palette.fireKeyForCapture(t);
            if (!out.isEmpty()) out.append(" | ");
            out.append(t).append(" -> ").append(fired ? palette.selectedForCapture() : "?");
        }
        return out.toString();
    }

    /**
     * Whether a tab that failed to start should be replaced by one running the default shell.
     *
     * <p>Only when the window would otherwise be left empty — an empty window closes itself, and
     * the last one closing quits — and only when what failed was not the default shell already,
     * since retrying the same command would fail the same way and loop.
     *
     * @param tabCount tabs in the window, the failed one still among them
     * @param failedWasDefaultShell whether the failed launch was already the plain shell
     */
    static boolean shouldFallBackToDefaultShell(int tabCount, boolean failedWasDefaultShell) {
        return tabCount <= 1 && !failedWasDefaultShell;
    }

    /** The command a launch would run, for the message: the profile's own, or the shell's. */
    private static String commandLineOf(com.termina.pty.LaunchOptions options) {
        List<String> command =
                options.hasCommand() ? options.command() : com.termina.pty.ShellLauncher.shellCommand(options.shell());
        return com.termina.cli.Argv.join(command);
    }

    private void reportShellFailure(com.termina.pty.LaunchOptions options, IOException e, boolean fellBack) {
        // Logged as well as shown. The dialog says one sentence and is then gone; this is the part
        // that survives to be pasted into a bug report, and it carries the stack trace.
        System.getLogger(TerminalWindow.class.getName())
                .log(System.Logger.Level.ERROR, "could not start " + commandLineOf(options), e);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle(com.termina.AppInfo.NAME);
        alert.setHeaderText(tr("dialog.shellFailed"));
        // The command, not only pty4j's "Exec_tty error:Unknown reason": the overwhelmingly common
        // cause is a path that names nothing, and the message has to show the path to be acted on.
        String detail = tr("dialog.shellFailedDetail", commandLineOf(options), String.valueOf(e.getMessage()));
        if (fellBack) detail = detail + "\n\n" + tr("dialog.shellFailedFallback");
        alert.setContentText(detail);
        // Shown, not waited for: the fallback tab opens underneath it, so the window is usable the
        // moment it appears and the dialog is an explanation rather than a gate.
        alert.show();
    }
}
