package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;

import com.termina.config.Settings;
import com.termina.i18n.Messages;
import com.termina.testing.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every item of every menu — the menu bar's, each tab's and the terminal's right-click menu — fired as
 * a click fires it, in a real window on real shells. {@link WindowCommandsFxTest} runs the commands;
 * this runs the items, which carry their own handlers (a tab's menu acts on its tab, not the
 * selected one) and are what a mouse reaches. Left out by label, for the same reasons as there:
 * what is modal, external, or ends the window.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class MenusFxTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
        Platform.setImplicitExit(false);
    }

    /** Items left out, by their catalogue key. */
    private static final List<String> SKIPPED_KEYS = List.of(
            "command.checkForUpdates",
            "command.help.documentation",
            "command.whatsNew",
            "command.exportConfiguration",
            "command.importConfiguration",
            "command.renameTab",
            "command.closeWindow",
            "command.app.quit",
            "menu.hideStatusBar",
            "command.showStatusBar",
            "menu.hideTabBar", // hides the strip the tabs' own menus are opened from
            "command.showTabBar",
            "command.openLink",
            "menu.copyLink");

    @Test
    void everyMenuItemRunsAgainstALiveWindow() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        settings.setConfirmClose(false);
        // Paste is an item: with several lines on the clipboard (a CI runner's) it would ask, modally.
        settings.setConfirmPaste(false);
        onFx(() -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString("echo pasted");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            return null;
        });
        Set<String> skipped = new java.util.HashSet<>();
        for (String key : SKIPPED_KEYS) skipped.add(Messages.tr(key));

        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window)); // so the first window's last tab never ends the app
        waitFor(() -> started(window), "the shell");
        onFx(() -> {
            window.openTab();
            window.openTab();
            return null;
        });

        List<String> failures = new ArrayList<>();
        int fired = 0;
        // The terminal's right-click menu, then each tab's, then the menu bar's, whose close items go last.
        TerminalView view = onFx(window::activeTerminal);
        fired += fireAll(onFx(() -> new ArrayList<>(view.contextMenuForTest().getItems())), skipped, failures);
        for (Tab tab : onFx(() -> new ArrayList<>(window.tabsForTest()))) {
            if (tab.getContextMenu() == null) continue;
            // MENU-11 (Pauta 6.0.1): a tab's menu is a shell until it is first asked for.
            assertFalse(TerminalWindow.tabMenuBuilt(tab), "a tab's menu was built before anyone asked for it");
            List<MenuItem> items = new ArrayList<>(onFx(() -> window.tabMenuItemsForTest(tab)));
            assertTrue(TerminalWindow.tabMenuBuilt(tab), "asking for it builds it");
            assertEquals(items, onFx(() -> window.tabMenuItemsForTest(tab)), "and builds it once");
            items.removeIf(item -> text(item) != null && text(item).startsWith(Messages.tr("command.closeTab")));
            fired += fireAll(items, skipped, failures);
        }
        List<MenuItem> bar = new ArrayList<>();
        for (Menu menu : onFx(() -> new ArrayList<>(window.menuBarForTest().getMenus()))) {
            onFx(() -> {
                menu.fire(); // a menu's onShowing greys what cannot run
                return null;
            });
            bar.addAll(flatten(menu.getItems()));
        }
        bar.sort(java.util.Comparator.comparing(item -> text(item) != null
                && text(item).startsWith(Messages.tr("command.closeTab").substring(0, 5))));
        fired += fireAll(bar, skipped, failures);

        assertEquals(List.of(), failures);
        assertTrue(fired > 40, "items fired: " + fired);
    }

    private static List<MenuItem> flatten(List<MenuItem> items) {
        List<MenuItem> out = new ArrayList<>();
        for (MenuItem item : items) {
            if (item instanceof Menu sub) out.addAll(flatten(sub.getItems()));
            else out.add(item);
        }
        return out;
    }

    private static int fireAll(List<MenuItem> items, Set<String> skipped, List<String> failures) throws Exception {
        int fired = 0;
        for (MenuItem item : items) {
            // The zoom row is a row of buttons, not an item to fire; the bar's command rows are
            // CustomMenuItems too (MENU-02), and fired like any other.
            if (item instanceof SeparatorMenuItem || item.isDisable()) continue;
            if (item instanceof CustomMenuItem && !MenuRows.isRow(item)) continue;
            String label = text(item);
            if (label == null || skipped.contains(label)) continue;
            String failure = onFx(() -> {
                try {
                    item.fire();
                    return null;
                } catch (RuntimeException e) {
                    return label + ": " + e;
                }
            });
            if (failure != null) failures.add(failure);
            onFx(() -> null);
            fired++;
        }
        return fired;
    }

    private static boolean started(TerminalWindow window) {
        try {
            return onFx(() -> {
                TerminalView view = window.activeTerminal();
                return view != null && view.isStarted();
            });
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Await.frame();
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }

    @Test
    void aMenuNamesAndGreysItsItemsAsTheRegistrySaysWhenItOpens() throws Exception {
        // MENU-01 (Pauta 2.0.2): the bar holds no rule of its own; opening a menu asks the registry.
        Settings settings = new Settings(dir.resolve("registry.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        settings.setConfirmClose(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));
        waitFor(() -> started(window), "the shell");

        assertTrue(onFx(() -> itemOpened(window, "reopenClosedTab").isDisable()), "nothing closed yet: greyed");
        // And says why, in the palette's words (KBD-04, Pauta 6.0.1).
        assertEquals(
                Messages.tr("palette.disabled.nothingToReopen"),
                onFx(() -> MenuReasons.of(itemOpened(window, "reopenClosedTab"))));
        onFx(() -> {
            window.openTab();
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("closeTab".equals(row.id())) row.action().run();
            }
            return null;
        });
        assertTrue(onFx(() -> !itemOpened(window, "reopenClosedTab").isDisable()), "a tab closed: available");
        assertEquals(null, onFx(() -> MenuReasons.of(itemOpened(window, "reopenClosedTab"))));

        String before = onFx(() -> MenuRows.titleOf(itemOpened(window, "pinTab")));
        onFx(() -> {
            itemOpened(window, "pinTab").fire();
            return null;
        });
        String after = onFx(() -> MenuRows.titleOf(itemOpened(window, "pinTab")));
        assertTrue(!before.equals(after), "Pin Tab says which way it will go: " + before + " then " + after);
        // The palette names it the same, from the same place.
        assertEquals(after, onFx(() -> {
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("pinTab".equals(row.id())) return row.label();
            }
            return null;
        }));

        // FIND-16, TBAR-03 (Pauta 6.0.1): the find bar's switch is out of the Tab order, so a
        // command flips it, opening the bar; and the bar's buttons are named for their commands,
        // with the chords in force, following a chord moved while the bar is open.
        TerminalView view = onFx(window::activeTerminal);
        assertEquals(List.of(), onFx(view::searchTooltipsForTest), "no bar yet");
        onFx(() -> {
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("findRegex".equals(row.id())) row.action().run();
            }
            return null;
        });
        assertTrue(onFx(view::searchIsRegex), "the command opened the bar with the switch on");
        List<String> tips = onFx(view::searchTooltipsForTest);
        assertEquals(
                List.of(
                        IconNames.withChord(Messages.tr("command.findNext"), Keymap.defaultFor("findNext")),
                        IconNames.withChord(Messages.tr("command.findPrevious"), Keymap.defaultFor("findPrevious")),
                        Messages.tr("command.findRegex")),
                tips);
        assertTrue(tips.get(0).contains("("), "Find Next has a chord to show: " + tips);
        onFx(() -> {
            settings.replaceKeyBindings(java.util.Map.of("findRegex", "Alt+Shift+R"));
            return null;
        });
        String moved = onFx(() -> view.searchTooltipsForTest().get(2));
        assertEquals(
                IconNames.withChord(
                        Messages.tr("command.findRegex"), javafx.scene.input.KeyCombination.valueOf("Alt+Shift+R")),
                moved);
        onFx(() -> {
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("findRegex".equals(row.id())) row.action().run();
            }
            return null;
        });
        assertFalse(onFx(view::searchIsRegex), "and off again");

        // MENU-07 (Pauta 6.0.1): a press on the tab strip closes the tab's right-click menu, which
        // JavaFX's own auto-hide leaves up because the strip is what it was opened on.
        java.util.Map<String, String> tabChords = new java.util.concurrent.ConcurrentHashMap<>();
        List<Boolean> menu = onFx(() -> {
            Tab tab = window.tabsForTest().get(0);
            javafx.scene.control.ContextMenu tabMenu = tab.getContextMenu();
            tabMenu.show(tab.getTabPane(), 40, 40);
            boolean shown = tabMenu.isShowing();
            // MENU-15 (Pauta 6.0.1): as it shows, each command row has its chord in force.
            for (javafx.scene.control.MenuItem row : tabMenu.getItems()) {
                String id = MenuChords.commandOf(row);
                if (id != null) tabChords.put(id, String.valueOf(row.getAccelerator()));
            }
            javafx.event.Event.fireEvent(
                    tab.getTabPane(),
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            200,
                            10,
                            200,
                            10,
                            javafx.scene.input.MouseButton.PRIMARY,
                            1,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            false,
                            false,
                            false,
                            false,
                            null));
            return List.of(shown, tabMenu.isShowing());
        });
        assertEquals(List.of(true, false), menu, "shown, then closed by the press");
        assertEquals(String.valueOf(Keymap.defaultFor("closeTab")), tabChords.get("closeTab"));
        assertEquals(String.valueOf(Keymap.defaultFor("newTab")), tabChords.get("newTab"));
        assertEquals("null", tabChords.get("renameTab"), "an unbound command's cell is empty");
        assertEquals(10, tabChords.size(), "every command row of the tab's menu: " + tabChords);
        // The terminal's menu: every row but the zoom row and the rules runs a command and says which.
        List<String> terminalRows = onFx(() -> {
            List<String> ids = new ArrayList<>();
            for (javafx.scene.control.MenuItem row : view.contextMenuForTest().getItems()) {
                if (row instanceof javafx.scene.control.SeparatorMenuItem
                        || row instanceof javafx.scene.control.CustomMenuItem) continue;
                ids.add(String.valueOf(MenuChords.commandOf(row)));
            }
            return ids;
        });
        assertTrue(!terminalRows.contains("null"), "a terminal menu row with no command: " + terminalRows);
        assertTrue(terminalRows.containsAll(List.of("copy", "paste", "newTab", "palette.show", "help.about")));
    }

    @Test
    void theBarIsTheModelRowForRow() throws Exception {
        // MENU-01's check: the bar's model lists ids only, and every row runs its id through the
        // registry. Here the bar as built is laid against MenuBarModel, row for row.
        Settings settings = new Settings(dir.resolve("model.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));

        List<String> mismatches = onFx(() -> {
            List<String> out = new ArrayList<>();
            List<MenuBarModel.Menu> model = MenuBarModel.menus();
            List<Menu> bar = window.menuBarForTest().getMenus();
            assertEquals(model.size(), bar.size());
            Set<String> commands = new java.util.HashSet<>();
            for (Keybindings.Command command : window.keyboardCommands()) commands.add(command.id());
            for (int m = 0; m < model.size(); m++) {
                MenuBarModel.Menu spec = model.get(m);
                List<MenuItem> items = bar.get(m).getItems();
                assertEquals(Messages.tr(spec.titleKey()), bar.get(m).getText());
                if (spec.rows().size() != items.size()) {
                    out.add(spec.titleKey() + ": " + spec.rows().size() + " rows, " + items.size() + " items");
                    continue;
                }
                for (int r = 0; r < items.size(); r++) {
                    String row = spec.rows().get(r);
                    MenuItem item = items.get(r);
                    boolean matches = switch (row) {
                        case MenuBarModel.SEPARATOR -> item instanceof SeparatorMenuItem;
                        case MenuBarModel.PROFILE_SUBMENU ->
                            item instanceof Menu submenu
                                    && MenuBarModel.submenuRows(row)
                                            .equals(submenu.getItems().stream()
                                                    .map(MenuItem::getUserData)
                                                    .toList())
                                    && commands.containsAll(MenuBarModel.submenuRows(row));
                        default -> row.equals(item.getUserData()) && commands.contains(row);
                    };
                    if (!matches) out.add(spec.titleKey() + " row " + r + ": " + row + " vs " + text(item));
                }
            }
            // And nothing registered that the model does not name.
            Set<String> named = new java.util.HashSet<>(MenuBarModel.commandIds(model));
            for (String id : commands) if (!named.contains(id)) out.add("registered, not in the model: " + id);
            return out;
        });
        assertEquals(List.of(), mismatches);
    }

    @Test
    void theLinkCommandsActOnTheLinkUnderThePointer() throws Exception {
        // KBD-01: the right-click menu's link rows are commands too, gated on there being a link.
        Settings settings = new Settings(dir.resolve("links.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));
        waitFor(() -> started(window), "the shell");
        TerminalView view = onFx(window::activeTerminal);
        assertTrue(unavailable(window, "copyLinkAddress"), "no pointer over the terminal: greyed");

        onFx(() -> {
            view.getSession().sendString("clear; printf 'https://example.com/termina-link\\n'\r");
            return null;
        });
        // Down the first column until the pointer is on the link: the row it is printed on depends
        // on the prompt, and the cell size on the font.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean found = false;
        while (!found && System.nanoTime() < deadline) {
            Await.frame();
            found = onFx(() -> {
                for (double y = 2; y < 200; y += 4) {
                    // The URL's row, not the prompt's: a path there is a link candidate too.
                    if (!view.hoverLinkForCapture(20, y).contains("termina-link")) continue;
                    // A fired event's coordinates are the scene's; the view's start below the menus.
                    javafx.geometry.Point2D scene = view.localToScene(20, y);
                    javafx.event.Event.fireEvent(view, moved(scene.getX(), scene.getY()));
                    return view.hasLinkUnderPointer();
                }
                return false;
            });
        }
        assertTrue(found, "a link under the pointer");
        assertTrue(!unavailable(window, "copyLinkAddress"), "available over the link");
        onFx(() -> {
            javafx.scene.input.Clipboard.getSystemClipboard().clear();
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if ("copyLinkAddress".equals(row.id())) row.action().run();
            }
            return null;
        });
        waitFor(
                () -> {
                    try {
                        return "https://example.com/termina-link"
                                .equals(onFx(() -> javafx.scene.input.Clipboard.getSystemClipboard()
                                        .getString()));
                    } catch (Exception e) {
                        return false;
                    }
                },
                "the link's address on the clipboard");

        onFx(() -> {
            javafx.event.Event.fireEvent(
                    view,
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_EXITED,
                            -5,
                            -5,
                            -5,
                            -5,
                            javafx.scene.input.MouseButton.NONE,
                            0,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            null));
            return null;
        });
        assertTrue(unavailable(window, "openLink"), "the pointer gone: greyed again");
    }

    private static javafx.scene.input.MouseEvent moved(double x, double y) {
        return new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_MOVED,
                x,
                y,
                x,
                y,
                javafx.scene.input.MouseButton.NONE,
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    /** Whether command {@code id} is greyed in the palette now. */
    private static boolean unavailable(TerminalWindow window, String id) throws Exception {
        return onFx(() -> window.runnablePaletteRowsForTest().stream().noneMatch(row -> id.equals(row.id())));
    }

    @Test
    void theBarsRowsDrawTheirChordsAndCarryNoAccelerator() throws Exception {
        // MENU-02 (Pauta 2.0.2): no KeyCombination on any row of the bar, the chord in force drawn by
        // the row, following a rebind, and one chord column per menu.
        Settings settings = new Settings(dir.resolve("rows.json"));
        settings.load();
        settings.setUpdateCheck(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));

        List<String> wrong = onFx(() -> {
            List<String> out = new ArrayList<>();
            for (Menu menu : window.menuBarForTest().getMenus()) {
                javafx.event.Event.fireEvent(menu, new javafx.event.Event(Menu.ON_SHOWING));
                for (MenuItem item : menu.getItems()) {
                    if (item instanceof Menu sub) {
                        javafx.event.Event.fireEvent(sub, new javafx.event.Event(Menu.ON_SHOWING));
                        // MENU-01 (Pauta 6.0.1): the submenu groups the numbered profile commands.
                        // A row is its command's: named for the profile it opens by the registry,
                        // wearing that profile's mark, and shown only while there is such a profile.
                        List<com.termina.shell.Profile> profiles =
                                windows.profiles().all();
                        for (int n = 0; n < sub.getItems().size(); n++) {
                            MenuItem inner = sub.getItems().get(n);
                            if (inner.getAccelerator() != null) out.add("accelerator on " + text(inner));
                            String id = TerminalWindow.PROFILE_CHORD_ID + (n + 1);
                            if (!id.equals(inner.getUserData()))
                                out.add("profile row " + n + " is " + inner.getUserData());
                            if (inner.isVisible() != (n < profiles.size()))
                                out.add(id + " visible=" + inner.isVisible());
                            if (n >= profiles.size()) continue;
                            if (!profiles.get(n).name().equals(text(inner))) out.add(id + " is called " + text(inner));
                            if (!TerminalWindow.SYSTEM_MENU_BAR && MenuRows.iconOf(inner) == null)
                                out.add(id + " has no mark");
                            javafx.scene.input.KeyCombination chord = window.chordOf(id);
                            if (!MenuRows.chordText(inner).equals(chord == null ? "" : chord.getDisplayText())) {
                                out.add(id + " shows \"" + MenuRows.chordText(inner) + "\"");
                            }
                        }
                        if (sub.getItems().size() != WindowRules.TAB_CHORD_COUNT)
                            out.add("profile rows: " + sub.getItems().size());
                    }
                    if (item.getAccelerator() != null) out.add("accelerator on " + text(item));
                    if (!(item.getUserData() instanceof String id)) continue;
                    // MENU-12 (Pauta 6.0.1): the bar's destructive row is drawn as one.
                    if (item.getStyleClass().contains("danger") != WindowRules.isDestructive(id)) {
                        out.add(id + " danger=" + item.getStyleClass().contains("danger"));
                    }
                    javafx.scene.input.KeyCombination chord = window.chordOf(id);
                    String shown = MenuRows.chordText(item);
                    if (!shown.equals(chord == null ? "" : chord.getDisplayText())) {
                        out.add(id + " shows \"" + shown + "\", is on " + chord);
                    }
                }
                java.util.Set<Double> widths = new java.util.HashSet<>();
                for (MenuItem item : menu.getItems()) {
                    if (item instanceof CustomMenuItem custom
                            && MenuRows.isRow(item)
                            && custom.getContent() instanceof javafx.scene.layout.HBox box) {
                        widths.add(
                                ((javafx.scene.control.Label) box.getChildren().get(1)).getPrefWidth());
                    }
                }
                if (widths.size() > 1) out.add(menu.getText() + " titles of " + widths.size() + " widths");
            }
            return out;
        });
        assertEquals(List.of(), wrong);

        onFx(() -> {
            settings.replaceKeyBindings(java.util.Map.of("newTab", "Shortcut+Shift+Y"));
            return null;
        });
        assertEquals(
                javafx.scene.input.KeyCombination.valueOf("Shortcut+Shift+Y").getDisplayText(),
                onFx(() -> MenuRows.chordText(itemOpened(window, "newTab"))),
                "the row follows a rebind");
    }

    /** What an item says: a row's title, or the text of anything else. */
    private static @org.jspecify.annotations.Nullable String text(MenuItem item) {
        return MenuRows.isRow(item) ? MenuRows.titleOf(item) : item.getText();
    }

    /**
     * KBD-04 (Pauta 6.0.1): a command that cannot run is listed greyed with its reason, in the bar,
     * a tab's menu and the terminal's, and none is taken out of its menu.
     */
    @Test
    void aGreyedRowOfAnyMenuSaysWhyAndNoneIsHidden() throws Exception {
        Settings settings = new Settings(dir.resolve("reasons.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setConfirmClose(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        waitFor(() -> started(window), "the shell");

        // The bar, every menu and submenu opened: one tab, so several rows cannot run.
        List<String> bar = onFx(() -> {
            List<String> greyed = new ArrayList<>();
            for (Menu menu : window.menuBarForTest().getMenus()) {
                javafx.event.Event.fireEvent(menu, new javafx.event.Event(Menu.ON_SHOWING));
                for (MenuItem item : menu.getItems()) {
                    List<MenuItem> rows = List.of(item);
                    if (item instanceof Menu sub) {
                        javafx.event.Event.fireEvent(sub, new javafx.event.Event(Menu.ON_SHOWING));
                        rows = sub.getItems();
                    }
                    for (MenuItem row : rows) {
                        if (row instanceof SeparatorMenuItem || !row.isDisable()) continue;
                        greyed.add(row.getUserData() + "=" + MenuReasons.of(row));
                    }
                }
            }
            return greyed;
        });
        assertTrue(bar.size() >= 5, "rows greyed in a window with one tab: " + bar);
        for (String row : bar) assertTrue(!row.endsWith("=null"), "greyed with no reason: " + row);
        assertTrue(bar.contains("moveTabLeft=" + Messages.tr("palette.disabled.firstTab")), bar.toString());
        assertTrue(bar.contains("closeOtherTabs=" + Messages.tr("palette.disabled.onlyTab")), bar.toString());

        // A tab's own menu, for the tab it was opened on.
        java.util.Map<String, String> tabMenu = onFx(() -> {
            java.util.Map<String, String> greyed = new java.util.TreeMap<>();
            for (MenuItem row : window.tabMenuItemsForTest(window.tabsForTest().getFirst())) {
                if (row.isDisable()) greyed.put(String.valueOf(MenuChords.commandOf(row)), MenuReasons.of(row));
            }
            return greyed;
        });
        assertEquals(
                java.util.Map.of(
                        "closeOtherTabs", Messages.tr("palette.disabled.onlyTab"),
                        "closeTabsToTheLeft", Messages.tr("palette.disabled.noTabsLeft"),
                        "closeTabsToTheRight", Messages.tr("palette.disabled.noTabsRight"),
                        "moveTabLeft", Messages.tr("palette.disabled.firstTab"),
                        "moveTabRight", Messages.tr("palette.disabled.lastTab")),
                tabMenu);

        // The terminal's, raised where there is no link and nothing is selected: the link rows
        // are in it, greyed, where they used to be left out.
        TerminalView view = onFx(window::activeTerminal);
        java.util.Map<String, String> terminal = onFx(() -> {
            javafx.geometry.Point2D at = view.localToScreen(40, 40);
            javafx.event.Event.fireEvent(
                    view,
                    new javafx.scene.input.ContextMenuEvent(
                            javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                            40,
                            40,
                            at.getX(),
                            at.getY(),
                            true,
                            null));
            java.util.Map<String, String> rows = new java.util.TreeMap<>();
            for (MenuItem row : view.contextMenuForTest().getItems()) {
                String id = MenuChords.commandOf(row);
                if (id == null) continue;
                rows.put(id, row.isVisible() + "/" + row.isDisable() + "/" + MenuReasons.of(row));
            }
            view.contextMenuForTest().hide();
            return rows;
        });
        String noLink = "true/true/" + Messages.tr("palette.disabled.noLinkUnderPointer");
        assertEquals(noLink, terminal.get("openLink"));
        assertEquals(noLink, terminal.get("copyLinkAddress"));
        assertEquals("true/true/" + Messages.tr("palette.disabled.noSelection"), terminal.get("copy"));
        assertEquals("true/false/null", terminal.get("selectAll"));
    }

    /** The bar's item for command {@code id}, after its menu has opened. */
    private static MenuItem itemOpened(TerminalWindow window, String id) {
        for (Menu menu : window.menuBarForTest().getMenus()) {
            for (MenuItem item : menu.getItems()) {
                if (id.equals(item.getUserData())) {
                    javafx.event.Event.fireEvent(menu, new javafx.event.Event(Menu.ON_SHOWING));
                    return item;
                }
            }
        }
        throw new AssertionError("no item for " + id);
    }
}
