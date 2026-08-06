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
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/** One window: a menu bar over a tab strip, each tab a terminal with its own shell. */
public final class TerminalWindow {

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");

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
                    terminalOf(removed).close();
                }
            }
            if (tabs.getTabs().isEmpty() && stage.isShowing()) stage.close();
            applyTabBarVisibility();
        });

        BorderPane root = new BorderPane(tabs);
        root.setTop(buildMenuBar());

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

        Tab tab = new Tab();
        tab.setContent(terminal);
        tab.setUserData(terminal);
        // The tab shows whatever the shell calls itself, which is what makes a row of tabs useful.
        tab.textProperty().bind(terminal.getDisplay().windowTitleProperty());

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

    public void closeCurrentTab() {
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        if (selected != null) tabs.getTabs().remove(selected);
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
        bar.setUseSystemMenuBar(MAC);

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
                        () -> settings.setFontSize(Settings.DEFAULT_FONT_SIZE))));

        Menu window = menu("Window",
                register(MenuAction.of("Next Tab", MenuAction.shiftChord(KeyCode.CLOSE_BRACKET),
                        () -> selectRelativeTab(1))),
                register(MenuAction.of("Previous Tab", MenuAction.shiftChord(KeyCode.OPEN_BRACKET),
                        () -> selectRelativeTab(-1))));

        bar.getMenus().addAll(file, edit, view, window);
        return bar;
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
