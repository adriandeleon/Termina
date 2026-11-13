package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.termina.config.Settings;
import com.termina.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The windows beside the terminal — Message Log, Debug Log, Report a Problem, About, the shortcut
 * reference — each opened, typed into, and every button and link pressed. Links go to a recorder,
 * not a browser; Export, which opens a file chooser, is left out.
 */
@Tag("fx")
class SecondaryWindowsFxTest {

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

    @Test
    void everyButtonAndLinkOfTheWindowsBesideTheTerminal() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        List<String> links = new CopyOnWriteArrayList<>();
        Set<String> skipped = Set.of(Messages.tr("debuglog.export"));

        int pressed = onFx(() -> {
            // Only the windows this test opens: other tests' windows share the toolkit.
            java.util.Set<Window> before = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            before.addAll(Window.getWindows());
            WindowManager windows = new WindowManager(settings);
            windows.setLinkOpener(links::add);
            windows.messages().add(MessageLog.Severity.ERROR, "something failed");
            windows.messages().add(MessageLog.Severity.WARNING, "something to look at");
            DebugLog.append("a line for the debug log");
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 400, 300));
            owner.show();
            // Not the Message Log: it is a card in a terminal's window now (LogWindowsFxTest).
            windows.showDebugLog(owner);
            windows.showReportProblem(owner);
            windows.showAbout(owner);
            windows.showPauta(owner);
            ShortcutsWindow shortcuts = new ShortcutsWindow();
            shortcuts.show(owner, List.of());
            shortcuts.setQueryForCapture("tab");

            int count = 0;
            for (Window window : List.copyOf(Window.getWindows())) {
                if (before.contains(window) || window == owner || !(window instanceof Stage)) continue;
                if (!window.isShowing() || window.getScene() == null) continue;
                if (window.getScene().getRoot() instanceof DialogPane) continue;
                Node root = window.getScene().getRoot();
                for (TextInputControl field : find(root, TextInputControl.class)) {
                    if (field.isEditable()) field.appendText(" edited");
                }
                for (ListView<?> list : find(root, ListView.class)) {
                    list.getSelectionModel().selectAll();
                    list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, true, false, true));
                }
                for (ButtonBase button : find(root, ButtonBase.class)) {
                    // An icon-only button, the find bar's, has no text; Set.of refuses to be asked about null.
                    String label = button.getText() == null ? "" : button.getText();
                    if (button.isDisabled() || skipped.contains(label)) continue;
                    if (!(button instanceof Button) && !(button instanceof Hyperlink)) continue;
                    DialogAnswers.next(false, null);
                    button.fire();
                    count++;
                }
                // Escape closes a window of this kind; whatever is left, the test closes.
                window.getScene()
                        .getRoot()
                        .fireEvent(
                                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                if (window.isShowing()) window.hide();
            }
            // About's conformance link opens the Pauta window again, after the sweep had closed it.
            for (Window left : List.copyOf(Window.getWindows())) {
                if (!before.contains(left) && left != owner && left.isShowing()) left.hide();
            }
            owner.hide();
            return count;
        });
        assertTrue(pressed >= 8, "buttons and links pressed: " + pressed);
        assertTrue(!links.isEmpty(), "a link went to the opener, not a browser");
        // ABOUT-02 (Pauta 6.0.1): the home page, and the source and releases, with no update to show.
        assertTrue(links.contains(com.termina.AppInfo.HOMEPAGE), links::toString);
        assertTrue(links.contains(com.termina.AppInfo.RELEASES_PAGE), links::toString);
        // PWIN-07: the Pauta window's two links, and About's edition link, to this edition's rules.
        assertTrue(links.contains(com.termina.AppInfo.pautaUrl()), links::toString);
        assertTrue(links.contains(PautaWindow.DOCS_PAGE), links::toString);
    }

    @Test
    void settingsOpensTheApplicationsDebugLogWithReportAProblemOnIt() throws Exception {
        // DIAG-03 (Pauta 6.0.1): the Advanced page's button once built a log window of its own,
        // with four of the five actions.
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        List<List<String>> logs = onFx(() -> {
            java.util.Set<Window> before = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            before.addAll(Window.getWindows());
            WindowManager windows = new WindowManager(settings);
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 400, 300));
            owner.show();
            windows.showSettings(owner);
            for (Node page : windows.settingsWindowForTest().pagesForTest()) {
                for (Button button : find(page, Button.class)) {
                    if (Messages.tr("settings.debugLog.button").equals(button.getText())) button.fire();
                }
            }
            // The command's way in, after the button's: the same window, not a second one.
            windows.showDebugLog(owner);
            List<List<String>> found = new ArrayList<>();
            for (Window window : List.copyOf(Window.getWindows())) {
                if (before.contains(window) || !(window instanceof Stage stage)) continue;
                if (stage.isShowing() && Messages.tr("debuglog.title").equals(stage.getTitle())) {
                    List<String> buttons = new ArrayList<>();
                    for (Button button : find(stage.getScene().getRoot(), Button.class)) buttons.add(button.getText());
                    found.add(buttons);
                }
            }
            for (Window window : List.copyOf(Window.getWindows())) {
                if (!before.contains(window) && window.isShowing()) window.hide();
            }
            return found;
        });
        assertEquals(1, logs.size(), "one Debug Log window: " + logs);
        assertTrue(
                logs.get(0)
                        .containsAll(List.of(
                                Messages.tr("debuglog.refresh"),
                                Messages.tr("debuglog.copy"),
                                Messages.tr("debuglog.clear"),
                                Messages.tr("debuglog.export"),
                                Messages.tr("command.reportProblem"))),
                logs::toString);
    }

    private static <T extends Node> List<T> find(Node node, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(node)) out.add(type.cast(node));
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            out.addAll(find(scroll.getContent(), type));
        }
        if (node instanceof Parent parent) {
            for (Node child : new ArrayList<>(parent.getChildrenUnmodifiable())) out.addAll(find(child, type));
        }
        return out;
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
}
