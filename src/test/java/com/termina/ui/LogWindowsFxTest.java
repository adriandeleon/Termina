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
                                Messages.tr("command.reportProblem")),
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
            owner.setScene(new Scene(new StackPane(), 300, 200));
            owner.show();
            MessageLogWindow window = new MessageLogWindow(log);
            window.show(owner);
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
            window.scene().lookup(".text-field").requestFocus();
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
            window.scene().getWindow().hide();
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
