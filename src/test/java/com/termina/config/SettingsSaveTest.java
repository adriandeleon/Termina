package com.termina.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deferred saves: memory and the listeners change at once, the file a moment after the last change.
 *
 * <p>Every setter used to write the whole file, under a file lock, on the thread that called it —
 * the FX thread — and the callers that fire per tick (a spinner held down, the zoom chord under key
 * auto-repeat, a slider's arrow keys) wrote it per tick. The scheduler here is stepped by hand, so
 * the tests count writes rather than wait for them.
 */
class SettingsSaveTest {

    /** A scheduler that runs nothing until told; each call replaces nothing, cancel removes. */
    private static final class ManualScheduler implements Settings.SaveScheduler {
        final List<Runnable> pending = new ArrayList<>();
        int scheduled;
        long lastDelay = -1;

        @Override
        public Scheduled schedule(Runnable task, long delayMs) {
            scheduled++;
            lastDelay = delayMs;
            pending.add(task);
            return () -> pending.remove(task);
        }

        /** The delay running out: every save still pending runs. */
        void elapse() {
            for (Runnable task : List.copyOf(pending)) {
                pending.remove(task);
                task.run();
            }
        }
    }

    private static Settings deferred(Path dir, ManualScheduler scheduler, AtomicInteger saves) {
        Settings settings = new Settings(dir.resolve("settings.properties"), scheduler, 300);
        settings.addOnSaved(saves::incrementAndGet);
        return settings;
    }

    @Test
    void fortyChangesAreOneWrite(@TempDir Path dir) throws IOException {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger saves = new AtomicInteger();
        AtomicInteger changes = new AtomicInteger();
        Settings settings = deferred(dir, scheduler, saves);
        settings.addOnChange(changes::incrementAndGet);

        // A slider dragged across forty values, within the clamp.
        for (int lines = 1000; lines < 41_000; lines += 1000) settings.setScrollbackLines(lines);

        assertEquals(40, changes.get(), "every change is broadcast as it is made");
        assertEquals(40_000, settings.scrollbackLines(), "and is in memory at once");
        assertEquals(0, saves.get(), "but nothing has been written yet");
        assertFalse(Files.exists(settings.file()));
        assertEquals(1, scheduler.pending.size(), "each change called the previous save off");
        assertEquals(40, scheduler.scheduled);
        assertEquals(300, scheduler.lastDelay);
        assertTrue(settings.hasPendingSave());

        scheduler.elapse();

        assertEquals(1, saves.get());
        assertFalse(settings.hasPendingSave());
        assertTrue(Files.readString(settings.file()).contains("terminal.scrollback=40000"));
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test
    void flushWritesWhatIsPendingNowAndCallsTheScheduledSaveOff(@TempDir Path dir) throws IOException {
        // Shutdown: the geometry written by a closing window may not wait 300 ms for a JVM that is
        // about to exit.
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger saves = new AtomicInteger();
        Settings settings = deferred(dir, scheduler, saves);
        settings.setThemeId("editora-light");
        settings.setWindowGeometry(800, 600, false);

        settings.flush();

        assertEquals(1, saves.get());
        assertTrue(scheduler.pending.isEmpty(), "the scheduled save was cancelled, not left to write again");
        String written = Files.readString(settings.file());
        assertTrue(written.contains("theme=editora-light"), written);
        assertTrue(written.contains("window.width=800"), written);

        scheduler.elapse();
        settings.flush();
        assertEquals(1, saves.get(), "nothing pending writes nothing");
    }

    @Test
    void theBatchSettersAndTheQuietOnesDeferTheirWritesToo(@TempDir Path dir) {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger saves = new AtomicInteger();
        Settings settings = deferred(dir, scheduler, saves);

        settings.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+Y"));
        settings.setLastUpdateCheck(1_700_000_000_000L);
        settings.setProfileStyle("p1", new Settings.ProfileStyle("Menlo", 14.0, ""));
        settings.forgetProfile("p1");
        settings.resetToDefaults();

        assertEquals(0, saves.get());
        assertEquals(1, scheduler.pending.size());
        scheduler.elapse();
        assertEquals(1, saves.get());
    }

    @Test
    void aChangeMadeWhileASaveIsPendingIsInTheNextWrite(@TempDir Path dir) throws IOException {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger saves = new AtomicInteger();
        Settings settings = deferred(dir, scheduler, saves);
        settings.setFontSize(12);
        scheduler.elapse();
        settings.setFontSize(13);
        assertTrue(settings.hasPendingSave());
        assertTrue(Files.readString(settings.file()).contains("font.size=12"), "the first write stands");
        scheduler.elapse();
        assertEquals(2, saves.get());
        assertTrue(Files.readString(settings.file()).contains("font.size=13"));
    }

    @Test
    void aFailedDeferredSaveIsReportedWhenItHappens(@TempDir Path dir) throws IOException {
        Path notADirectory = dir.resolve("file");
        Files.writeString(notADirectory, "in the way");
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger saves = new AtomicInteger();
        Settings settings = new Settings(notADirectory.resolve("settings.properties"), scheduler, 300);
        settings.addOnSaved(saves::incrementAndGet);

        settings.setFontSize(21);
        assertEquals(null, settings.lastSaveError(), "nothing has been tried yet");
        scheduler.elapse();
        assertEquals(1, saves.get(), "the save listener hears about a failure too");
        assertNotNull(settings.lastSaveError());
    }

    @Test
    void theApplicationsSchedulerWritesAfterTheDelayOnItsOwnThread(@TempDir Path dir) throws Exception {
        // The real scheduler, with a short delay: the save runs off the calling thread, and a flush
        // afterwards finds nothing left to write.
        var executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "test-settings-save");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Settings settings =
                    new Settings(dir.resolve("settings.properties"), Settings.SaveScheduler.on(executor), 20);
            CountDownLatch saved = new CountDownLatch(1);
            List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
            settings.addOnSaved(() -> {
                threads.add(Thread.currentThread().getName());
                saved.countDown();
            });
            for (int i = 0; i < 10; i++) settings.setPadding(i);
            assertTrue(saved.await(5, TimeUnit.SECONDS), "the save ran");
            assertEquals(List.of("test-settings-save"), threads);
            assertTrue(Files.readString(settings.file()).contains("terminal.padding=9"));
            settings.flush();
            assertEquals(1, threads.size(), "the flush had nothing to write");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void thePlainConstructorStillWritesAtOnce(@TempDir Path dir) throws IOException {
        Settings settings = new Settings(dir.resolve("settings.properties"));
        AtomicInteger saves = new AtomicInteger();
        settings.addOnSaved(saves::incrementAndGet);
        settings.setFontSize(21);
        assertEquals(1, saves.get());
        assertFalse(settings.hasPendingSave());
        assertTrue(Files.readString(settings.file()).contains("font.size=21"));
    }
}
