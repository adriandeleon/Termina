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
                        Map.entry("termina.capturePaletteKeys", "C-n,C-p,down,up,pagedown,pageup,M-<,M->"),
                        Map.entry("termina.captureProfiles", "true"),
                        Map.entry("termina.captureLogProbe", "true"),
                        Map.entry("termina.captureResizeTo", "900,600")));
        runs.put(
                "the settings window",
                Map.of("termina.captureSettings", "font", "termina.captureSettingsKeys", "newTab:C-S-y"));
        runs.put("the shortcut reference", Map.of("termina.captureShortcuts", "true"));
        runs.put("the about window", Map.of("termina.captureAbout", "true"));
        runs.put(
                "what programs say: prompt marks, links, notifications, the clipboard",
                Map.of(
                        // OSC 133 around a command and its output, an OSC 8 link, OSC 9 and 777
                        // notifications, an OSC 52 clipboard write and OSC 7's directory.
                        "termina.captureCommand",
                        "printf '\\033]133;A\\007$ \\033]133;B\\007echo hi\\n\\033]133;C\\007hi\\n"
                                + "\\033]133;D;0\\007\\033]8;;https://example.com\\007LINK\\033]8;;\\007\\n"
                                + "\\033]9;hello\\007\\033]777;notify;title;body\\007"
                                + "\\033]52;c;aGVsbG8=\\007\\033]7;file://localhost/tmp\\007done\\n'",
                        "termina.captureMarks",
                        "true",
                        "termina.captureJumpPrompt",
                        "prev,next,prev",
                        "termina.captureSelectLastOutput",
                        "true",
                        "termina.captureLinkAt",
                        "10,20;30,40;30,60;30,80;60,100",
                        "termina.captureScreenText",
                        "true"));
        runs.put(
                "a full-screen program that reports the mouse",
                Map.of(
                        // The alternate screen and SGR mouse reporting, as vim or htop would ask.
                        "termina.captureCommand", "printf '\\033[?1049h\\033[?1000h\\033[?1006hfull\\n'",
                        "termina.captureClick", "60,55,1",
                        "termina.captureScroll", "400,200,-120",
                        "termina.captureDrag", "10,30,200,90",
                        "termina.captureMenu", "300,300,true",
                        "termina.captureKeystrokes", "up,down,S-pageup,escape"));
        runs.put(
                "dragging past the edge, the scroll bar and a block",
                Map.of(
                        "termina.captureCommand", "seq 1 400",
                        "termina.captureDragPath", "10,300;10,-60",
                        "termina.captureDragHoldMs", "400",
                        "termina.captureDragAlt", "true",
                        "termina.captureDragScrollBar", "40,300",
                        "termina.captureMenuDismiss", "escape",
                        "termina.captureSelectTabLater", "1,200",
                        "termina.captureRenameTab", "build"));
        runs.put(
                "panes, profiles and pins",
                Map.of(
                        "termina.captureTabs", "2",
                        "termina.capturePanes", "right,down,up,right!,down!,styles,move:1,profile:1,closeTab,reopen",
                        "termina.capturePinTab", "1,others",
                        "termina.captureCloseAnswer", "close"));
        runs.put(
                "a link under the pointer, opened",
                Map.of(
                        // The screen cleared first, so the address is on the top row under the pointer;
                        // then an OSC 8 link on the row below.
                        "termina.captureCommand",
                        "clear; echo https://example.com/page; printf '\\033]8;;https://example.com/b\\007LINK\\033]8;;\\007\\n'",
                        "termina.captureLinkAt",
                        "12,12;40,12;20,30",
                        "termina.captureOpenLink",
                        "true",
                        "termina.captureMenu",
                        "40,12,false"));
        runs.put(
                "a program that names the window and asks the terminal",
                Map.of(
                        // OSC 2 sets the title; the kitty protocol's push, query and pop; DA1 and DA2.
                        "termina.captureCommand",
                        "printf '\\033]2;my title\\007\\033[>1u\\033[?u\\033[=1;1u\\033[<u\\033[c\\033[>c'",
                        "termina.captureKeystrokes",
                        "a,C-a,escape,up",
                        "termina.captureScreenText",
                        "true"));
        runs.put(
                "a shell that will not start",
                Map.of("termina.captureFailShellStarts", "1", "termina.captureTabs", "1"));
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

    /** The runs that need a file made first: a theme to import and a configuration to restore. */
    @org.junit.jupiter.api.Test
    void importingAThemeAndAConfiguration() throws Exception {
        Path theme = dir.resolve("night.conf");
        StringBuilder colours = new StringBuilder("foreground #f0f0f0\nbackground #101010\n");
        for (int i = 0; i < 16; i++)
            colours.append("color").append(i).append(" #2").append(i % 10).append("2020\n");
        Files.writeString(theme, colours);
        Path config = dir.resolve("exported-config");
        Files.createDirectories(config);
        Files.writeString(
                config.resolve("settings.json"),
                "{\n  \"schemaVersion\" : 1,\n  \"settings\" : {\n    \"keys.newTab\" : \"Shortcut+Shift+Y\"\n  }\n}\n");
        Path zip = dir.resolve("config.zip");
        com.termina.config.ConfigArchive.export(config, zip);
        runCapture(
                "imports",
                Map.of(
                        "termina.captureImportTheme", theme.toString(),
                        "termina.captureImportConfig", zip + ";KEY_BINDINGS",
                        "termina.captureSyncNow", "true",
                        "termina.capturePressKeyAfter", "ls ",
                        "termina.capturePressKey", "TAB,false"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    void aCaptureRunCompletesAndWritesItsPicture(String name) throws Exception {
        runCapture(name, runs().get(name));
    }

    private void runCapture(String name, Map<String, String> options) throws Exception {
        Path picture = dir.resolve("capture.png");
        Map<String, String> set = new LinkedHashMap<>(options);
        set.put("termina.capture", picture.toString());
        set.put("termina.captureSettleMs", "700");
        set.put("termina.captureAfterCommandMs", "700");
        // A middle-click or a paste with several lines on the clipboard (a CI runner's) would ask
        // first, and the prompt is modal: answered up front, as captureCloseAnswer answers closing.
        set.putIfAbsent("termina.capturePasteAnswer", "cancel");
        set.forEach(System::setProperty);
        try {
            Settings settings = new Settings(dir.resolve("settings.json"));
            settings.load();
            settings.setUpdateCheck(false);
            settings.setShowStatusBar(true); // a report is then a line, not a modal dialog
            settings.setNotifications(false); // an OSC 9 marks the tab; it never reaches the desktop
            WindowManager windows = onFx(() -> new WindowManager(settings));
            onFx(() -> {
                windows.setLinkOpener(url -> System.out.println("[test] would open " + url));
                return null;
            });
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
