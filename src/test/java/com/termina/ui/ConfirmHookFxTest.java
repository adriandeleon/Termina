package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.stage.Window;

import com.termina.config.Settings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CONF-12 (Pauta 6.0.1): a hook answers a question in a test, and no dialog is built or shown.
 * The Settings window's Reset Key Bindings is asked twice through it, refused and then allowed.
 */
@Tag("fx")
class ConfirmHookFxTest {

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
    void aHookAnswersTheQuestionAndNoDialogOpens() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.replaceKeyBindings(Map.of("file.newTab", "Ctrl+Alt+T"));
        java.util.Set<Window> before = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        onFx(() -> before.addAll(Window.getWindows()));
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        List<Questions.Question> asked = new ArrayList<>();
        try {
            // Refused: the bindings stay.
            int windowsOpen = onFx(() -> {
                windows.showSettings(window.stage());
                Confirm.answerWith(question -> {
                    asked.add(question);
                    return false;
                });
                int open = Window.getWindows().size();
                windows.runSettingsAction(window.stage(), SettingsActions.Action.RESET_SHORTCUTS);
                assertEquals(open, Window.getWindows().size(), "no dialog was shown");
                return open;
            });
            assertEquals(List.of(Questions.resetAllKeys()), asked);
            assertEquals(Map.of("file.newTab", "Ctrl+Alt+T"), settings.keyBindings());

            // Allowed: they are reset, and still nothing opened.
            onFx(() -> {
                Confirm.answerWith(question -> {
                    asked.add(question);
                    return true;
                });
                windows.runSettingsAction(window.stage(), SettingsActions.Action.RESET_SHORTCUTS);
                assertEquals(windowsOpen, Window.getWindows().size(), "no dialog was shown");
                return null;
            });
            assertEquals(2, asked.size());
            assertEquals(Map.of(), settings.keyBindings());
        } finally {
            onFx(() -> {
                Confirm.answerWith(null);
                for (Window open : List.copyOf(Window.getWindows())) {
                    if (before.contains(open) || open == window.stage()) continue;
                    if (open.isShowing() && open instanceof javafx.stage.Stage) open.hide();
                }
                return null;
            });
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
}
