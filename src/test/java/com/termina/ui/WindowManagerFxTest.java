package com.termina.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.termina.config.Settings;
import com.termina.pty.LaunchOptions;
import com.termina.testing.Await;
import com.termina.update.ReleaseInfo;
import com.termina.update.UpdateCheck;
import com.termina.update.UpdateService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the window manager does across windows: an update found, opened and dismissed; a check that
 * failed; a second launch handed over; a closed window brought back. The update answers are given to
 * it directly — the network is {@code UpdateServiceTest}'s — and links go to a recorder, not a browser.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class WindowManagerFxTest {

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
    void updatesHandOffsAndAReopenedWindow() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        settings.setConfirmClose(false);
        List<String> opened = new CopyOnWriteArrayList<>();

        WindowManager windows = onFx(() -> new WindowManager(settings));
        onFx(() -> {
            windows.setLinkOpener(opened::add);
            return null;
        });
        TerminalWindow first = onFx(() -> windows.openWindow(null));
        TerminalWindow keeper = onFx(() -> windows.openWindow(first));
        waitFor(() -> started(first), "the shell");

        ReleaseInfo release = new ReleaseInfo("99.0.0", "https://example.com/releases/99.0.0", "Termina 99.0.0");
        onFx(() -> {
            windows.showAbout(first.stage());
            windows.onUpdateOutcome(new UpdateService.Outcome(true, release, null, null), false);
            return null;
        });
        // DIAG-09 (Pauta 6.0.1): every window's status bar names the version in a link, and the
        // link opens that release. Following it in one window takes it down in all of them.
        String notice = com.termina.i18n.Messages.tr("status.updateAvailable", "99.0.0");
        assertEquals(notice, onFx(first::statusBarUpdateLinkForTest));
        assertEquals(notice, onFx(keeper::statusBarUpdateLinkForTest));
        assertEquals(List.of(), opened);
        onFx(() -> {
            keeper.followStatusBarUpdateLinkForTest();
            return null;
        });
        assertEquals("", onFx(first::statusBarUpdateLinkForTest));
        assertEquals("", onFx(keeper::statusBarUpdateLinkForTest));
        onFx(() -> {
            windows.dismissCurrentUpdate();
            windows.onUpdateOutcome(
                    new UpdateService.Outcome(false, null, UpdateCheck.ErrorKind.OFFLINE, "no route"), true);
            windows.onUpdateOutcome(UpdateService.Outcome.none(), true);
            return null;
        });
        assertEquals(List.of(release.url()), opened, "the release page went to the link opener");
        assertEquals("99.0.0", settings.dismissedUpdate());

        // A second launch's request: a window in that directory.
        int before = onFx(() -> windows.windows().size());
        onFx(() -> windows.openHandedOff(new LaunchOptions("", dir.toString(), List.of()), "handed over"));
        assertEquals(before + 1, onFx(() -> windows.windows().size()));

        // A window closed, then brought back.
        onFx(() -> {
            first.stage().hide();
            return null;
        });
        int afterClose = onFx(() -> windows.windows().size());
        onFx(() -> {
            windows.reopenClosedWindow(keeper);
            return null;
        });
        assertTrue(onFx(() -> windows.windows().size()) > afterClose, "the closed window came back");

        // The update check from the menu and at startup, against a server of the test's own.
        try (java.net.ServerSocket server =
                new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread accept = new Thread(() -> serveRelease(server));
            accept.setDaemon(true);
            accept.start();
            List<String> said = new CopyOnWriteArrayList<>();
            onFx(() -> {
                windows.useUpdateServiceForTest(new UpdateService(
                        "http://127.0.0.1:" + server.getLocalPort() + "/latest", java.time.Duration.ofSeconds(5)));
                windows.checkForUpdatesNow((severity, message) -> said.add(message));
                return null;
            });
            waitFor(() -> said.size() >= 2, "the check to say it started and what it found");
            onFx(() -> {
                settings.setUpdateCheck(true);
                settings.setLastUpdateCheck(0);
                windows.maybeCheckForUpdates();
                settings.setUpdateCheck(false);
                return null;
            });
        }

        // The first window as App opens it, on the stage it is given; with a command line and a title.
        onFx(() -> {
            windows.openFirstWindow(new javafx.stage.Stage());
            windows.openFirstWindow(new javafx.stage.Stage(), new LaunchOptions("", dir.toString(), List.of()));
            windows.openFirstWindow(new javafx.stage.Stage(), LaunchOptions.DEFAULTS, "titled");
            return null;
        });

        // Export and Import from the palette's path, into a home folder of the test's own.
        Path home = dir.resolve("home");
        java.nio.file.Files.createDirectories(home);
        String was = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        List<String> reports = new CopyOnWriteArrayList<>();
        try {
            onFx(() -> {
                windows.exportConfiguration((severity, message) -> reports.add(message));
                return null;
            });
            waitFor(() -> !reports.isEmpty(), "the export");
        } finally {
            System.setProperty("user.home", was);
        }
        Path zip;
        try (var files = java.nio.file.Files.list(home)) {
            zip = files.filter(f -> f.toString().endsWith(".zip")).findFirst().orElseThrow();
        }
        onFx(() -> {
            windows.importConfigurationForCapture(
                    zip,
                    java.util.EnumSet.of(com.termina.config.ConfigArchive.Part.KEY_BINDINGS),
                    (severity, message) -> reports.add(message));
            return null;
        });
        waitFor(() -> reports.size() >= 2, "the import");

        // KBD-01 (Pauta 6.0.1): the buttons of Settings, Report a Problem and About are commands,
        // each run here from the palette's rows as a chord would run it.
        assertEquals(List.of(), onFx(() -> windows.profiles().userProfiles()));
        assertTrue(unavailable(keeper, "settings.removeTheme"), "the theme in use is built in");
        assertTrue(unavailable(keeper, "settings.removeProfile"), "no profile is selected");
        assertTrue(unavailable(keeper, "sync.cancel"), "no sync is running");
        assertTrue(unavailable(keeper, "sync.removeCredentials"), "the address holds none");
        run(keeper, "settings.addProfile");
        assertEquals(1, onFx(() -> windows.profiles().userProfiles().size()));
        assertTrue(!unavailable(keeper, "settings.removeProfile"), "the new profile is selected");
        // MENU-05, MENU-12: the profile list's own menu, Remove styled as dangerous.
        assertEquals(
                List.of(
                        com.termina.i18n.Messages.tr("settings.profileAdd"),
                        com.termina.i18n.Messages.tr("settings.profileRemove") + " (danger)"),
                onFx(() -> windows.settingsWindowForTest().profileMenuRowsForTest()));
        onFx(() -> {
            DialogAnswers.next(false, null);
            return null;
        });
        run(keeper, "settings.removeProfile");
        assertEquals(1, onFx(() -> windows.profiles().userProfiles().size()), "Cancel kept it");
        onFx(() -> {
            DialogAnswers.next(true, null);
            return null;
        });
        run(keeper, "settings.removeProfile");
        assertEquals(0, onFx(() -> windows.profiles().userProfiles().size()));

        onFx(() -> {
            settings.setFontSize(17);
            DialogAnswers.next(true, null);
            return null;
        });
        run(keeper, "settings.reset");
        assertEquals(Settings.DEFAULT_FONT_SIZE, onFx(settings::fontSize));
        onFx(() -> {
            settings.replaceKeyBindings(java.util.Map.of("file.newTab", "Shortcut+Shift+Y"));
            DialogAnswers.next(true, null);
            return null;
        });
        run(keeper, "settings.resetShortcuts");
        assertEquals(java.util.Map.of(), onFx(settings::keyBindings));

        // The connection's command is named for the way it will go.
        assertEquals(com.termina.i18n.Messages.tr("command.sync.connect"), label(keeper, "sync.connect"));

        int linksBefore = opened.size();
        run(keeper, "help.website");
        run(keeper, "help.sourceCode");
        run(keeper, "help.pautaRules");
        assertEquals(
                List.of(
                        com.termina.AppInfo.HOMEPAGE,
                        com.termina.AppInfo.RELEASES_PAGE,
                        com.termina.AppInfo.pautaUrl()),
                opened.subList(linksBefore, opened.size()));
        run(keeper, "help.copyDetails");
        assertTrue(clipboard().startsWith(com.termina.AppInfo.NAME + " "), clipboard());
        run(keeper, "report.copy");
        assertTrue(clipboard().contains("Problem report"), clipboard());
        run(keeper, "report.open");
        assertTrue(opened.get(opened.size() - 1).contains("/issues/new?template=bug_report.yml"), opened.toString());
        // The windows the commands brought up are put away before this one is closed by asking.
        onFx(() -> {
            for (javafx.stage.Window open : List.copyOf(javafx.stage.Window.getWindows())) {
                // Never a terminal's window: another test's, left open in this JVM, would end its
                // own manager, and the toolkit with it, on being hidden from here.
                if (open instanceof javafx.stage.Stage stage && !TerminalWindow.isTerminalWindow(open)) {
                    stage.hide();
                }
            }
            return null;
        });

        // The status bar off and on from the View menu's command; then a window closed by asking.
        onFx(() -> {
            for (MenuAction row : keeper.runnablePaletteRowsForTest()) {
                if ("view.toggleStatusBar".equals(row.id())) {
                    row.action().run();
                    row.action().run();
                }
            }
            settings.setConfirmClose(true);
            DialogAnswers.next(true, null);
            keeper.close();
            return null;
        });
        // The close above finishes once the shells' children are read off the FX thread, and the
        // update checks answer on their own time; both write into the settings folder when they do,
        // and JUnit cannot delete a folder that is still being written into.
        waitUntilQuiet(dir);
    }

    /**
     * WIN-13, WIN-14 (Pauta 6.0.1): one quit method. The app.quit command asks once for every
     * window, Cancel keeps them all, and the system's Quit, which arrives as a close request on
     * every window in one turn, runs the same method; one window's close request closes that one.
     */
    @Test
    void quitAsksOnceForEveryWindowAndTheSystemsQuitRunsTheSameMethod() throws Exception {
        Settings settings = new Settings(dir.resolve("quit.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        // Off for the first close: a fresh shell often has a child for a moment, a prompt
        // framework's helper, and a question nobody is waiting to answer would hang the run.
        settings.setConfirmClose(false);
        java.util.concurrent.atomic.AtomicInteger ended = new java.util.concurrent.atomic.AtomicInteger();
        WindowManager windows = onFx(() -> {
            WindowManager made = new WindowManager(settings);
            made.endAppWithForTest(ended::incrementAndGet);
            return made;
        });
        TerminalWindow first = onFx(() -> windows.openWindow(null));
        TerminalWindow second = onFx(() -> windows.openWindow(first));
        TerminalWindow third = onFx(() -> windows.openWindow(first));
        // Another manager's window, as every other FX test leaves one behind in this JVM: Quit
        // closes the windows beside the terminals, and this is a terminal's. Hidden from here it
        // ended its own manager, and with it the toolkit, on whichever machine ran this test late.
        java.util.concurrent.atomic.AtomicInteger otherEnded = new java.util.concurrent.atomic.AtomicInteger();
        Settings otherSettings = new Settings(dir.resolve("other.json"));
        otherSettings.load();
        otherSettings.setUpdateCheck(false);
        WindowManager other = onFx(() -> {
            WindowManager made = new WindowManager(otherSettings);
            made.endAppWithForTest(otherEnded::incrementAndGet);
            return made;
        });
        TerminalWindow bystander = onFx(() -> other.openWindow(null));
        waitFor(() -> started(first) && started(second) && started(third) && started(bystander), "the shells");

        // One window asked to close, with others open, is that window closing.
        onFx(() -> {
            javafx.event.Event.fireEvent(
                    third.stage(),
                    new javafx.stage.WindowEvent(third.stage(), javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            return null;
        });
        waitFor(() -> size(windows) == 2, "the one window to close");
        assertEquals(0, ended.get());

        // A program running in one of them: Quit asks, once, and Cancel keeps every window.
        long busy = onFx(() -> {
            settings.setConfirmClose(true);
            TerminalView view = second.activeTerminal();
            if (view != null) view.getSession().sendString("sleep 30\r");
            return view == null ? -1L : view.getSession().pid();
        });
        // The program itself, by name: a prompt's helper is a child for a moment too, and a
        // close asked while only that was running would find nothing by the time it looked.
        waitFor(
                () -> com.termina.process.RunningProgram.in(busy).stream()
                        .anyMatch(program -> program.contains("sleep")),
                "sleep to be running");
        int answered = DialogAnswers.answered();
        onFx(() -> {
            DialogAnswers.next(false, null);
            return null;
        });
        run(first, "app.quit");
        waitFor(() -> DialogAnswers.answered() > answered, "Quit's question, answered");
        Await.frames(3); // what a wrong answer would have closed, it would have closed by now
        assertEquals(2, size(windows), "Cancel kept the windows");
        DialogAnswers.Seen asked = DialogAnswers.seen().get(DialogAnswers.seen().size() - 1);
        assertEquals(com.termina.i18n.Messages.tr("dialog.quitRunning", com.termina.AppInfo.NAME), asked.header());
        assertEquals(
                List.of(com.termina.i18n.Messages.tr("dialog.quit"), com.termina.i18n.Messages.tr("dialog.cancel")),
                asked.buttons());
        assertTrue(asked.danger() && asked.cancelIsDefault(), asked.toString());
        assertEquals(0, ended.get());

        // The system's Quit: a close request on every window in the same turn. One question, and
        // on a yes every window goes and the app ends once.
        int before = DialogAnswers.answered();
        onFx(() -> {
            DialogAnswers.next(true, null);
            for (TerminalWindow window : List.of(first, second)) {
                javafx.event.Event.fireEvent(
                        window.stage(),
                        new javafx.stage.WindowEvent(window.stage(), javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            }
            return null;
        });
        waitFor(() -> size(windows) == 0, "every window to close");
        assertEquals(before + 1, DialogAnswers.answered(), "asked once, not once for each window");
        assertEquals(1, ended.get(), "the last window going ended the app, once");
        assertTrue(onFx(() -> bystander.stage().isShowing()), "another manager's window is not Quit's to close");
        assertEquals(0, otherEnded.get(), "and its app was not ended");
        assertEquals(1, size(other));
        // It is this test's to tidy: its own manager closes it, and ends nothing real.
        onFx(() -> {
            otherSettings.setConfirmClose(false);
            bystander.closeForShutdown();
            return null;
        });
        waitFor(() -> size(other) == 0, "the bystander to close");
        waitUntilQuiet(dir);
    }

    private static int size(WindowManager windows) {
        try {
            return onFx(() -> windows.windows().size());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void run(TerminalWindow window, String id) throws Exception {
        boolean ran = onFx(() -> {
            for (MenuAction row : window.runnablePaletteRowsForTest()) {
                if (id.equals(row.id())) {
                    row.action().run();
                    return true;
                }
            }
            return false;
        });
        assertTrue(ran, "no runnable command " + id);
    }

    private static boolean unavailable(TerminalWindow window, String id) throws Exception {
        return onFx(() -> window.runnablePaletteRowsForTest().stream().noneMatch(row -> id.equals(row.id())));
    }

    private static String label(TerminalWindow window, String id) throws Exception {
        return onFx(() -> window.runnablePaletteRowsForTest().stream()
                .filter(row -> id.equals(row.id()))
                .map(MenuAction::label)
                .findFirst()
                .orElse(""));
    }

    private static String clipboard() throws Exception {
        return onFx(() ->
                String.valueOf(javafx.scene.input.Clipboard.getSystemClipboard().getString()));
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

    /** Answers every request with a release newer than any build. */
    private static void serveRelease(java.net.ServerSocket server) {
        byte[] body = "{\"tag_name\": \"v99.0.0\", \"html_url\": \"https://example.com/r\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        while (!server.isClosed()) {
            try (java.net.Socket socket = server.accept()) {
                java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                    // headers; the answer does not depend on them
                }
                java.io.OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (java.io.IOException e) {
                return;
            }
        }
    }

    /** Waits until nothing in {@code folder} has changed for half a second, or five seconds pass. */
    private static void waitUntilQuiet(Path folder) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        String last = "";
        long stableSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            String now;
            try (var walk = java.nio.file.Files.walk(folder)) {
                now = walk.map(p -> p + ":" + p.toFile().lastModified())
                        .sorted()
                        .toList()
                        .toString();
            } catch (java.io.IOException | java.io.UncheckedIOException e) {
                now = "";
            }
            if (!now.equals(last)) {
                last = now;
                stableSince = System.nanoTime();
            } else if (System.nanoTime() - stableSince > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500)) {
                return;
            }
            Await.frame();
        }
    }
}
