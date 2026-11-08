package com.termina;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.termina.config.Settings;
import com.termina.ui.TerminalView;
import com.termina.ui.TerminalWindow;
import com.termina.ui.WindowManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The development capture harness, run as a test: each case is one capture run with a group of
 * options, against a real window on a real shell, and passes when the run completes and writes its
 * picture. The harness is how every visual change here is checked ("Verify by driving it"), so a
 * broken option is a broken tool; and what it drives — selection, search, panes, menus, the palette —
 * is the real UI, reached the way a hand reaches it.
 *
 * <p>Left out: what goes to the network (the update check), a browser (opening a link), the operating
 * system's input (native chords), or needs a file prepared (theme and configuration imports); and
 * closing and reopening whole windows, which {@code WindowLifecycleTest} covers. Not on Windows,
 * whose shell path has never run.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class DevCaptureFxTest {

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

    static Map<String, Map<String, String>> runs() {
        Map<String, Map<String, String>> runs = new LinkedHashMap<>();
        runs.put(
                "text, search and prompt marks",
                Map.of(
                        "termina.captureCommand", "printf 'error one\\nwarning\\nerror two\\n'",
                        "termina.captureSearch", "error",
                        "termina.captureSearchRegex", "true",
                        "termina.captureSearchSteps", "2",
                        "termina.captureMarks", "true",
                        "termina.captureJumpPrompt", "prev,next",
                        "termina.captureSelectLastOutput", "true",
                        "termina.captureScreenText", "true",
                        "termina.captureSelectionAtEnd", "true",
                        "termina.capturePredict", "git status"));
        runs.put(
                "the mouse",
                Map.of(
                        "termina.captureCommand", "seq 1 200",
                        "termina.captureDrag", "0,35,240,75",
                        "termina.captureDragShift", "true",
                        "termina.captureClick", "60,55,2",
                        "termina.captureScroll", "400,200,-320",
                        "termina.captureMiddleClick", "200,100",
                        "termina.captureScrollBarTo", "120",
                        "termina.captureLinkAt", "20,20;200,40"));
        runs.put(
                "keys",
                Map.of(
                        "termina.captureKeystrokes", "C-a,S-enter,escape,key:SHIFT,S-f10,escape",
                        "termina.captureTypeAtFocus", "hello",
                        "termina.captureChordAtFocus", "C-S-a",
                        "termina.captureChord", "T",
                        "termina.capturePressKey", "TAB,true",
                        "termina.captureKeyProbe", "true",
                        "termina.captureStealFocus", "tabs"));
        runs.put(
                "tabs, panes and windows",
                Map.ofEntries(
                        Map.entry("termina.captureTabs", "2"),
                        Map.entry("termina.captureWindows", "1"),
                        Map.entry("termina.capturePanes", "right,down,left,max,max,move,close"),
                        Map.entry("termina.captureCloseAnswer", "close"),
                        Map.entry("termina.captureSelectTab", "1"),
                        Map.entry("termina.captureDuplicateTab", "true"),
                        Map.entry("termina.captureMoveTab", "0,2"),
                        Map.entry("termina.captureReopenTab", "true"),
                        Map.entry("termina.captureBellIn", "1"),
                        Map.entry("termina.captureTypeIn", "1:ls"),
                        Map.entry("termina.captureTabMenu", "1"),
                        Map.entry("termina.captureTabMenuEscape", "true")));
        runs.put(
                "menus, palette and themes",
                Map.ofEntries(
                        Map.entry("termina.captureMenu", "300,300,false"),
                        Map.entry("termina.captureMenuItem", "Zoom In"),
                        Map.entry("termina.captureTheme", "editora-light"),
                        Map.entry("termina.capturePalette", "reo"),
                        Map.entry("termina.capturePaletteKeys", "C-n,C-p,down,up"),
                        Map.entry("termina.captureProfiles", "true"),
                        Map.entry("termina.captureLogProbe", "true"),
                        Map.entry("termina.captureResizeTo", "900,600")));
        runs.put(
                "the settings window",
                Map.of("termina.captureSettings", "font", "termina.captureSettingsKeys", "newTab:C-S-y"));
        runs.put("the shortcut reference", Map.of("termina.captureShortcuts", "true"));
        runs.put("the about window", Map.of("termina.captureAbout", "true"));
        runs.put(
                "closing tabs",
                Map.of(
                        "termina.captureTabs", "2",
                        "termina.captureCloseAnswer", "close",
                        "termina.captureCloseTabs", "1"));
        return runs;
    }

    static java.util.stream.Stream<String> names() {
        return runs().keySet().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    void aCaptureRunCompletesAndWritesItsPicture(String name) throws Exception {
        Map<String, String> options = runs().get(name);
        Path picture = dir.resolve("capture.png");
        Map<String, String> set = new LinkedHashMap<>(options);
        set.put("termina.capture", picture.toString());
        set.put("termina.captureSettleMs", "700");
        set.put("termina.captureAfterCommandMs", "700");
        set.forEach(System::setProperty);
        try {
            Settings settings = new Settings(dir.resolve("settings.json"));
            settings.load();
            settings.setUpdateCheck(false);
            settings.setShowStatusBar(true); // a report is then a line, not a modal dialog
            WindowManager windows = onFx(() -> new WindowManager(settings));
            TerminalWindow window = onFx(() -> windows.openWindow(null));
            // Kept open throughout, so a run that closes its windows never ends the application.
            TerminalWindow keeper = onFx(() -> windows.openWindow(null));
            waitFor(() -> started(window), "the shell");

            CountDownLatch done = new CountDownLatch(1);
            onFx(() -> {
                DevCapture.schedule(windows, window, settings, done::countDown);
                return null;
            });
            assertTrue(done.await(90, TimeUnit.SECONDS), name + ": the run did not finish");
            assertTrue(Files.size(picture) > 0, name + ": no picture");
            // The run's windows go the way every window goes, through WINDOW_HIDDEN, one at a time.
            for (TerminalWindow open : onFx(windows::windows)) {
                if (open == keeper) continue;
                onFx(() -> {
                    open.stage().hide();
                    return null;
                });
            }
        } finally {
            set.keySet().forEach(System::clearProperty);
        }
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
            Thread.sleep(50);
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
