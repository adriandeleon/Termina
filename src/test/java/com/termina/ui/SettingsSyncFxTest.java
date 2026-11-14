package com.termina.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.termina.config.Settings;
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
 * Settings sync as the app runs it — connected, run from the menu, a binding changed and run again,
 * disconnected — against a real bare repository in a temporary folder. The engine has its own tests
 * ({@code SyncEngineTest}); these are the scheduling, the state the Sync page shows and the reports
 * Sync Now gives. Not on Windows, where the engine's own whole-sync tests are off too.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "as SyncEngineTest's whole syncs")
class SettingsSyncFxTest {

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
    void aConnectedSyncPushesTheBindingsAndSaysHowItWent() throws Exception {
        Path bare = dir.resolve("remote.git");
        Files.createDirectories(bare);
        Process init = new ProcessBuilder("git", "init", "--quiet", "--bare", bare.toString())
                .redirectErrorStream(true)
                .start();
        assertEquals(0, init.waitFor(), "git init --bare");

        Settings settings = new Settings(dir.resolve("config/settings.json"));
        settings.load();
        settings.setSyncRepository(bare.toString());
        settings.setSyncBranch(Settings.DEFAULT_SYNC_BRANCH);
        settings.setSyncEnabled(true);
        settings.setSyncAuto(true);
        settings.replaceKeyBindings(Map.of("file.newTab", "Shortcut+Shift+Y"));

        List<String> reports = new CopyOnWriteArrayList<>();
        Reporter report = (severity, message) -> reports.add(severity + " " + message);
        SettingsSync sync = onFx(() -> new SettingsSync(settings, () -> true));
        onFx(() -> {
            sync.connected();
            sync.syncNow(report, id -> id);
            return null;
        });
        waitFor(() -> reports.size() >= 2, "Sync Now to report its start and its end: " + reports);
        waitFor(() -> phase(sync) == SettingsSync.Phase.IDLE, "the sync to settle");
        assertTrue(reports.get(reports.size() - 1).startsWith("INFO"), reports.toString());

        // A changed binding goes up on the next run.
        onFx(() -> {
            settings.replaceKeyBindings(Map.of("file.newTab", "Shortcut+Shift+U"));
            sync.syncNow();
            return null;
        });
        waitFor(() -> pushedBindings(bare).contains("Shift+U"), "the changed binding to be pushed");

        // Another computer changes a binding and pushes it: the next run brings it here.
        Path other = dir.resolve("other");
        // The branch sync pushed to, which is not necessarily the bare repository's default.
        assertEquals(
                0,
                git(
                        dir,
                        "clone",
                        "--quiet",
                        "--branch",
                        Settings.DEFAULT_SYNC_BRANCH,
                        bare.toString(),
                        other.toString()));
        // The one file every system shares, in the Windows and Linux form (OS-04).
        String file = com.termina.sync.KeybindingsFile.PATH;
        Files.createDirectories(other.resolve(file).getParent());
        Files.writeString(other.resolve(file), "file.newTab=Ctrl+Shift+K\n");
        assertEquals(0, git(other, "add", file));
        assertEquals(
                0,
                git(other, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "from elsewhere"));
        assertEquals(0, git(other, "push", "--quiet"));
        onFx(() -> {
            sync.syncNow();
            return null;
        });
        waitFor(
                () -> com.termina.sync.SharedChords.fromShared("Ctrl+Shift+K", com.termina.ui.Keybindings.MAC)
                        .equals(settings.values().get("keys.file.newTab")),
                "the other computer's binding");

        // What the Sync page asks, and the buttons it offers, with a run settled.
        onFx(() -> {
            sync.checkGitHub();
            assertFalse(sync.cancel(), "nothing to cancel between runs");
            sync.syncAllowingLargeRemoval();
            return null;
        });
        waitFor(() -> phase(sync) == SettingsSync.Phase.IDLE, "the forced run to settle");

        // A token typed into the address: removed from it on request, and a listener let go.
        onFx(() -> {
            Runnable listener = () -> {};
            sync.addListener(listener);
            sync.removeListener(listener);
            settings.setSyncRepository("https://someone:secret-token@example.com/me/sync.git");
            sync.removeCredentialsFromUrl();
            return null;
        });
        assertFalse(settings.syncRepository().contains("secret-token"), settings.syncRepository());

        onFx(() -> {
            // Auto off first, so no timer starts a run after the test; then let a run in flight end.
            settings.setSyncAuto(false);
            settings.setSyncEnabled(false);
            sync.disconnected();
            return null;
        });
        assertEquals(SettingsSync.Phase.OFF, phase(sync));
        onFx(() -> {
            sync.syncNow(report, id -> id); // disconnected: says why at once
            return null;
        });
        assertTrue(reports.get(reports.size() - 1).startsWith("WARNING"), reports.toString());
        // Nothing left writing into the folder JUnit is about to delete: the worker runs one job at a
        // time, so a job queued now runs after any it was still finishing.
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        sync.afterQueuedWorkForTest(drained::countDown);
        assertTrue(drained.await(30, TimeUnit.SECONDS), "the sync worker finished");
    }

    private static SettingsSync.Phase phase(SettingsSync sync) {
        try {
            return onFx(() -> sync.state().phase());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Every file on the remote branch, as one string. */
    private static String pushedBindings(Path bare) {
        try {
            Process show = new ProcessBuilder(
                            "git", "--git-dir", bare.toString(), "log", "-p", "--all", "--format=", "--", "keybindings")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(show.getInputStream().readAllBytes());
            show.waitFor();
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
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

    private static int git(Path in, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git", "-C", in.toString()));
        command.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }
}
