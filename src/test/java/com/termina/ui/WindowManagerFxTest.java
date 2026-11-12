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

        // The status bar off and on from the View menu's command; then a window closed by asking.
        onFx(() -> {
            for (MenuAction row : keeper.runnablePaletteRowsForTest()) {
                if ("showStatusBar".equals(row.id())) {
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
            Thread.sleep(50);
        }
    }
}
