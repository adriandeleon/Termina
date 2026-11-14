package com.termina.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.termina.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two log windows (Pauta 6.0.1): the Debug Log's find bar (FIND-01, FIND-02, FIND-06, FIND-07),
 * the Message Log's filter (FIND-03), and a right-click menu on each (MENU-05) whose rows do what
 * the windows' commands do (KBD-01).
 */
@Tag("fx")
class LogWindowsFxTest {

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

    @Test
    void theDebugLogsFindBarSearchesAsItIsTypedStepsWithEnterAndClosesOnEscape() throws Exception {
        List<String> seen = onFx(() -> {
            DebugLog.clear();
            DebugLog.append("WARNING the first thing");
            DebugLog.append("INFO nothing to see");
            DebugLog.append("warning the second thing");
            DebugLog.append("SEVERE the third thing");
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 300, 200));
            owner.show();
            DebugLogWindow window = new DebugLogWindow(owner);
            window.show();
            List<String> out = new java.util.ArrayList<>();
            out.add(window.findReport());

            // The bar is a node of the window's own scene, opened by the command's method.
            window.find();
            out.add(window.findReport());
            window.searchFor("warning");
            out.add(window.findReport());
            window.pressInFindForTest(KeyCode.ENTER, false);
            out.add(window.findReport());
            window.pressInFindForTest(KeyCode.ENTER, false);
            out.add(window.findReport());
            window.pressInFindForTest(KeyCode.ENTER, true);
            out.add(window.findReport());
            // The command again, with the bar open: the next match (FIND-04).
            window.find();
            out.add(window.findReport());
            window.searchFor("no such text");
            out.add(window.findReport());
            window.searchFor("THIRD");
            out.add(window.findReport());
            window.searchFor("third");
            out.add(window.findReport());

            // Escape in the field closes the bar and not the window.
            window.pressInFindForTest(KeyCode.ESCAPE, false);
            out.add(window.findReport());
            out.add("showing=" + window.scene().getWindow().isShowing());
            out.add("bar in the window's scene="
                    + (window.scene().lookup(".search-bar") != null
                            && window.scene().lookup(".search-bar").getScene() == window.scene()));
            window.scene().getWindow().hide();
            owner.hide();
            return out;
        });
        String none = Messages.tr("search.none");
        assertEquals(
                List.of(
                        "closed count=<> selected=<> focus=text",
                        "open count=<> selected=<> focus=field",
                        "open count=<" + Messages.tr("search.count", 1, "2") + "> selected=<WARNING> focus=field",
                        "open count=<" + Messages.tr("search.count", 2, "2") + "> selected=<warning> focus=field",
                        "open count=<" + Messages.tr("search.count", 1, "2") + "> selected=<WARNING> focus=field",
                        "open count=<" + Messages.tr("search.count", 2, "2") + "> selected=<warning> focus=field",
                        "open count=<" + Messages.tr("search.count", 1, "2") + "> selected=<WARNING> focus=field",
                        "open count=<" + none + "> selected=<> focus=field",
                        "open count=<" + none + "> selected=<> focus=field",
                        "open count=<" + Messages.tr("search.count", 1, "1") + "> selected=<third> focus=field",
                        "closed count=<" + Messages.tr("search.count", 1, "1") + "> selected=<> focus=text",
                        "showing=true",
                        "bar in the window's scene=true"),
                seen);
    }

    @Test
    void theDebugLogsMenuAndCommandsCopyRefreshAndClear() throws Exception {
        List<Object> seen = onFx(() -> {
            DebugLog.clear();
            DebugLog.append("one line");
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 300, 200));
            owner.show();
            DebugLogWindow window = new DebugLogWindow(owner, () -> {});
            window.show();
            List<Object> out = new java.util.ArrayList<>();
            out.add(window.menuRowsForTest());
            DebugLog.append("a later line");
            window.refresh();
            out.add(window.textForTest().contains("a later line"));
            window.copy();
            out.add(Clipboard.getSystemClipboard().getString().contains("one line"));
            window.searchFor("later");
            window.copy();
            out.add(Clipboard.getSystemClipboard().getString());
            window.clear();
            out.add(window.textForTest());
            window.scene().getWindow().hide();
            owner.hide();
            return out;
        });
        assertEquals(
                List.of(
                        List.of(
                                Messages.tr("debuglog.find"),
                                Messages.tr("debuglog.copy"),
                                Messages.tr("debuglog.refresh"),
                                Messages.tr("debuglog.export"),
                                Messages.tr("debuglog.clear"),
                                Messages.tr("command.help.reportProblem")),
                        true,
                        true,
                        "later",
                        Messages.tr("debuglog.empty")),
                seen);
    }

    @Test
    void theMessageLogsFilterNarrowsTheListAndEscapePeelsOneLayer() throws Exception {
        MessageLog log = new MessageLog();
        List<String> seen = onFx(() -> {
            log.add(MessageLog.Severity.INFO, "Sync finished");
            log.add(MessageLog.Severity.ERROR, "Sync failed: no route to host");
            log.add(MessageLog.Severity.WARNING, "Update check timed out");
            Stage owner = new Stage();
            StackPane host = new StackPane();
            owner.setScene(new Scene(host, 900, 600));
            owner.show();
            MessageLogCard window = new MessageLogCard(log, new OverlayHost(host, () -> {}), () -> 0);
            window.show();
            List<String> out = new java.util.ArrayList<>();
            out.add(window.report());
            window.filterBy("sync");
            out.add(window.report());
            window.filterBy("SYNC host");
            out.add(window.report());
            window.filterBy("nothing like this");
            out.add(window.report());
            // A message said while the filter is on is narrowed like the rest.
            window.filterBy("sync");
            log.add(MessageLog.Severity.INFO, "Sync started");
            out.add(window.report());

            // Escape: a filter with something in it empties; an empty one gives the list back.
            window.card().lookup(".text-field").requestFocus();
            window.escapeInFilterForTest();
            out.add(window.report() + " showing=" + window.isShowing());
            window.escapeInFilterForTest();
            out.add(window.report() + " showing=" + window.isShowing());

            out.add(String.valueOf(window.menuRowsForTest()));
            window.copy();
            out.add(String.valueOf(
                    Clipboard.getSystemClipboard().getString().lines().count()));
            window.clear();
            out.add(window.report());
            window.hide();
            owner.hide();
            return out;
        });
        String empty = Messages.tr("messagelog.empty");
        String none = Messages.tr("search.none");
        assertEquals(
                List.of(
                        "filter=<> lines=[Update check timed out, Sync failed: no route to host, Sync finished] empty=<"
                                + empty + "> focus=list",
                        // What an empty list would say: with a filter typed, that nothing passes it.
                        "filter=<sync> lines=[Sync failed: no route to host, Sync finished] empty=<" + none
                                + "> focus=list",
                        "filter=<SYNC host> lines=[Sync failed: no route to host] empty=<" + none + "> focus=list",
                        "filter=<nothing like this> lines=[] empty=<" + none + "> focus=list",
                        "filter=<sync> lines=[Sync started, Sync failed: no route to host, Sync finished] empty=<"
                                + none + "> focus=list",
                        "filter=<> lines=[Sync started, Update check timed out, Sync failed: no route to host, Sync"
                                + " finished] empty=<" + empty + "> focus=filter showing=true",
                        "filter=<> lines=[Sync started, Update check timed out, Sync failed: no route to host, Sync"
                                + " finished] empty=<" + empty + "> focus=list showing=true",
                        String.valueOf(List.of(Messages.tr("debuglog.copy"), Messages.tr("debuglog.clear"))),
                        "4",
                        "filter=<> lines=[] empty=<" + empty + "> focus=list"),
                seen);
        assertEquals(0, log.entries().size());
        assertFalse(onFx(() -> Clipboard.getSystemClipboard().getString().isEmpty()));
        assertTrue(log.unreadErrors() == 0);
    }

    /**
     * MENU-10 (Pauta 6.0.1): a right-click selects first, so the menu that opens is the clicked
     * row's and Copy acts on that row, not on the one that was selected before.
     */
    @Test
    void aRightClickOnAnotherRowOfTheMessageLogSelectsItFirst() throws Exception {
        MessageLog log = new MessageLog();
        List<String> seen = onFx(() -> {
            log.add(MessageLog.Severity.INFO, "first said");
            log.add(MessageLog.Severity.INFO, "second said");
            log.add(MessageLog.Severity.INFO, "third said");
            Stage owner = new Stage();
            StackPane host = new StackPane();
            owner.setScene(new Scene(host, 900, 600));
            owner.show();
            MessageLogCard window = new MessageLogCard(log, new OverlayHost(host, () -> {}), () -> 0);
            window.show();
            host.applyCss();
            host.layout();
            @SuppressWarnings("unchecked")
            javafx.scene.control.ListView<MessageLog.Entry> list = (javafx.scene.control.ListView<MessageLog.Entry>)
                    window.card().lookup(".list-view");
            list.getSelectionModel().clearAndSelect(0);
            List<String> out = new java.util.ArrayList<>();
            out.add(list.getSelectionModel().getSelectedItem().text());
            // The row holding the oldest message, pressed with the secondary button.
            list.applyCss();
            list.layout();
            for (javafx.scene.Node node : list.lookupAll(".list-cell")) {
                if (node instanceof javafx.scene.control.ListCell<?> cell
                        && cell.getItem() instanceof MessageLog.Entry entry
                        && entry.text().equals("first said")) {
                    // In the scene's coordinates: the event's own are worked out from them
                    // for whatever it is fired at.
                    javafx.geometry.Point2D at = cell.localToScene(5, 5);
                    javafx.event.Event.fireEvent(
                            cell,
                            new javafx.scene.input.MouseEvent(
                                    javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                                    at.getX(),
                                    at.getY(),
                                    at.getX(),
                                    at.getY(),
                                    javafx.scene.input.MouseButton.SECONDARY,
                                    1,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    true,
                                    false,
                                    true,
                                    false,
                                    null));
                }
            }
            out.add(list.getSelectionModel().getSelectedItems().stream()
                    .map(MessageLog.Entry::text)
                    .toList()
                    .toString());
            window.copy();
            out.add(Clipboard.getSystemClipboard().getString().strip().replaceAll("^\\S+\\s+", ""));
            window.hide();
            owner.hide();
            return out;
        });
        assertEquals(List.of("third said", "[first said]", "first said"), seen);
    }

    /**
     * QOPEN-12 (Pauta 6.0.1): the log is a list in the overlay host like the picker, and moves and
     * cancels on the picker's keys, the chords bound to a row and a page and Ctrl+G, beside the
     * list's own arrows.
     */
    @Test
    void theMessageLogMovesAndCancelsOnThePickersKeys() throws Exception {
        java.util.Map<String, PaletteKeys.Action> moves =
                PaletteKeys.moves(id -> KeyChord.of(Keymap.lists().get(id), Keybindings.MAC));
        assertEquals(PaletteKeys.Action.DOWN, MessageLogCard.listAction("C-n", moves));
        assertEquals(PaletteKeys.Action.PAGE_UP, MessageLogCard.listAction("M-v", moves));
        assertEquals(PaletteKeys.Action.CANCEL, MessageLogCard.listAction("C-g", moves));
        // The list's own keys, Enter and Escape are not taken here.
        for (String own : List.of("down", "up", "pagedown", "home", "enter", "escape")) {
            assertEquals(PaletteKeys.Action.NONE, MessageLogCard.listAction(own, moves), own);
        }
        assertEquals(PaletteKeys.Action.NONE, MessageLogCard.listAction(null, moves));
        // Rebound, the old chord is no longer a movement key.
        assertEquals(
                PaletteKeys.Action.NONE,
                MessageLogCard.listAction("C-n", java.util.Map.of("C-j", PaletteKeys.Action.DOWN)));

        MessageLog log = new MessageLog();
        List<String> seen = onFx(() -> {
            for (String line : List.of("one", "two", "three", "four")) log.add(MessageLog.Severity.INFO, line);
            Stage owner = new Stage();
            StackPane host = new StackPane();
            owner.setScene(new Scene(host, 1200, 800));
            owner.show();
            MessageLogCard card = new MessageLogCard(log, new OverlayHost(host, () -> {}), () -> 0);
            card.show();
            host.applyCss();
            host.layout();
            @SuppressWarnings("unchecked")
            javafx.scene.control.ListView<MessageLog.Entry> list = (javafx.scene.control.ListView<MessageLog.Entry>)
                    card.card().lookup(".list-view");
            list.getSelectionModel().clearAndSelect(0);
            List<String> out = new java.util.ArrayList<>();
            javafx.event.Event.fireEvent(list, ctrl(KeyCode.N));
            javafx.event.Event.fireEvent(list, ctrl(KeyCode.N));
            out.add(String.valueOf(list.getSelectionModel().getSelectedIndex()));
            javafx.event.Event.fireEvent(list, ctrl(KeyCode.P));
            out.add(String.valueOf(list.getSelectionModel().getSelectedIndex()));
            javafx.event.Event.fireEvent(list, ctrl(KeyCode.G));
            out.add(String.valueOf(card.isShowing()));
            owner.close();
            return out;
        });
        assertEquals(List.of("2", "1", "false"), seen);
    }

    private static javafx.scene.input.KeyEvent ctrl(KeyCode code) {
        return new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, true, false, false);
    }

    /**
     * MLOG-08 (Pauta 6.0.1): the log is a card in its window's overlay host, at the bottom left
     * just above the status bar, 773 wide with a list 336 high, and it opens no window.
     */
    @Test
    void theMessageLogIsACardInItsWindowAtTheTemplatesSize() throws Exception {
        MessageLog log = new MessageLog();
        List<String> seen = onFx(() -> {
            log.add(MessageLog.Severity.ERROR, "Sync failed");
            Stage owner = new Stage();
            StackPane host = new StackPane();
            owner.setScene(new Scene(host, 1200, 800));
            owner.show();
            int windowsBefore = javafx.stage.Window.getWindows().size();
            boolean[] handedBack = {false};
            MessageLogCard card = new MessageLogCard(log, new OverlayHost(host, () -> handedBack[0] = true), () -> 24);
            card.show();
            host.applyCss();
            host.layout();
            List<String> out = new java.util.ArrayList<>();
            double[] size = card.sizeForTest();
            // Where it was laid out; its bounds in the parent would count what a child draws outside it.
            javafx.scene.layout.Region laid = (javafx.scene.layout.Region) card.card();
            javafx.geometry.Bounds at = new javafx.geometry.BoundingBox(
                    laid.getLayoutX(), laid.getLayoutY(), laid.getWidth(), laid.getHeight());
            out.add("windows opened " + (javafx.stage.Window.getWindows().size() - windowsBefore));
            out.add("in the host " + (card.card().getScene() == owner.getScene()) + " unread " + log.unreadErrors());
            out.add("card " + Math.round(size[0]) + " list " + Math.round(size[1]));
            out.add("left " + Math.round(at.getMinX()) + " above the bar by " + Math.round(800 - 24 - at.getMaxY()));

            // Escape in the list closes it and the keyboard is handed back.
            javafx.event.Event.fireEvent(
                    card.card().lookup(".list-view"),
                    new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            KeyCode.ESCAPE,
                            false,
                            false,
                            false,
                            false));
            out.add("after Escape showing " + card.isShowing() + " handed back " + handedBack[0]);

            // A press outside closes it; so do its Close button and the chord that opened it.
            card.show();
            javafx.scene.Node backdrop =
                    host.getChildren().get(host.getChildren().size() - 2);
            javafx.event.Event.fireEvent(backdrop, press());
            out.add("after a press outside showing " + card.isShowing());
            card.show();
            for (javafx.scene.Node node : card.card().lookupAll(".button")) {
                if (node instanceof javafx.scene.control.Button button
                        && Messages.tr("debuglog.close").equals(button.getText())) button.fire();
            }
            out.add("after Close showing " + card.isShowing());
            card.setChords(
                    id -> "view.messageLog".equals(id) ? javafx.scene.input.KeyCombination.valueOf("Alt+L") : null);
            card.show();
            javafx.event.Event.fireEvent(
                    card.card().lookup(".list-view"),
                    new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", KeyCode.L, false, false, true, false));
            out.add("after its own chord showing " + card.isShowing());
            card.show();
            card.show();
            out.add("shown twice showing " + card.isShowing());

            // In a window too narrow for it, as wide as the window lets it be.
            owner.setWidth(500);
            host.resize(500, 800);
            card.show();
            host.applyCss();
            host.layout();
            out.add("narrow " + (card.sizeForTest()[0] <= 500 - 16));
            card.hide();
            owner.hide();
            return out;
        });
        assertEquals(
                List.of(
                        "windows opened 0",
                        "in the host true unread 0",
                        "card 773 list 336",
                        "left 8 above the bar by 8",
                        "after Escape showing false handed back true",
                        "after a press outside showing false",
                        "after Close showing false",
                        "after its own chord showing false",
                        "shown twice showing false",
                        "narrow true"),
                seen);

        // The sizes follow the interface zoom, and the cap is the template's.
        assertEquals(773 * 1.5, MessageLogCard.sizeAt(1.5)[0]);
        assertEquals(336 * 1.5, MessageLogCard.sizeAt(1.5)[1]);
        assertEquals(200, MessageLog.MAX);
        MessageLog full = new MessageLog();
        for (int i = 0; i < 205; i++) full.add(MessageLog.Severity.INFO, "message " + i);
        assertEquals(200, full.entries().size());
        assertEquals(5, full.cut());
        assertEquals("message 204", full.entries().get(0).text());

        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/termina/ui/MessageLogCard.java"));
        assertFalse(source.contains("Stage"), "the log is never a window of its own");
    }

    @Test
    void escapeClosesTheLogExceptFromItsFilterAndSoDoesTheChordThatOpenedIt() {
        javafx.scene.input.KeyCombination opener = javafx.scene.input.KeyCombination.valueOf("Alt+L");
        assertTrue(MessageLogCard.closes(key(KeyCode.ESCAPE, false), false, null));
        assertFalse(MessageLogCard.closes(key(KeyCode.ESCAPE, false), true, null), "the filter peels its own layers");
        assertTrue(MessageLogCard.closes(key(KeyCode.L, true), false, opener));
        assertTrue(MessageLogCard.closes(key(KeyCode.L, true), true, opener));
        assertFalse(MessageLogCard.closes(key(KeyCode.L, true), false, null));
        assertFalse(MessageLogCard.closes(key(KeyCode.G, false), false, opener), "no fixed cancel chord");
    }

    private static javafx.scene.input.KeyEvent key(KeyCode code, boolean alt) {
        return new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, alt, false);
    }

    private static javafx.scene.input.MouseEvent press() {
        return new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                5,
                5,
                5,
                5,
                javafx.scene.input.MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                true,
                null);
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
        return result.get(20, TimeUnit.SECONDS);
    }
}
