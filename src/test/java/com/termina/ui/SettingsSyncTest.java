package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.termina.sync.GitRunner;
import com.termina.sync.SyncReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The rules around a sync run: when it may happen, what it says, what it keeps. */
class SettingsSyncTest {

    @Test
    void connectedNeedsTheSwitchAndARepositoryThatCanBeUsed() {
        assertTrue(SettingsSync.isConfigured(true, "git@github.com:me/s.git", "main"));
        assertFalse(SettingsSync.isConfigured(false, "git@github.com:me/s.git", "main"));
        assertFalse(SettingsSync.isConfigured(true, "", "main"));
        assertFalse(SettingsSync.isConfigured(true, "git@github.com:me/s.git", "a..b"));
        // A hand-edited file with a token in it does not get to sync with it.
        assertFalse(SettingsSync.isConfigured(true, "https://me:secret@github.com/me/s.git", "main"));
    }

    @Test
    void theFieldsSayWhatIsWrongWithThemBeforeConnecting() {
        assertNull(SettingsWindow.syncFieldProblem("git@github.com:me/s.git", "main"));
        assertEquals("settings.sync.repository.invalid", SettingsWindow.syncFieldProblem("", "main"));
        assertEquals(
                "settings.sync.repository.credentials",
                SettingsWindow.syncFieldProblem("https://me:secret@github.com/me/s.git", "main"));
        assertEquals("settings.sync.branch.invalid", SettingsWindow.syncFieldProblem("git@h:r.git", "-main"));
    }

    @Test
    void aSummaryNamesConflictsByWhatTheCommandDoes() {
        SyncReport report =
                new SyncReport(SyncReport.Status.OK, List.of("copy"), List.of("newTab"), List.of("newTab"), "");
        String text = SettingsSync.summary(report, id -> id.equals("newTab") ? "New Tab" : id);
        assertTrue(text.contains("New Tab"), text);
        assertFalse(text.contains("newTab"), text);

        SyncReport quiet = new SyncReport(SyncReport.Status.OK, List.of(), List.of(), List.of(), "");
        assertFalse(SettingsSync.summary(quiet, id -> id).isBlank());
    }

    @Test
    void onlyTheNewestBackupsAreKept(@TempDir Path dir) throws IOException {
        for (int i = 0; i < SettingsSync.BACKUPS_KEPT + 3; i++) {
            Files.createDirectories(
                    dir.resolve(String.format("20261025-1200%02d-000", i)).resolve("keybindings"));
        }
        SettingsSync.prune(dir, SettingsSync.BACKUPS_KEPT);
        try (var left = Files.list(dir)) {
            List<String> names =
                    left.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(SettingsSync.BACKUPS_KEPT, names.size());
            assertEquals("20261025-120003-000", names.get(0), "the oldest three go");
        }
    }

    @Test
    void aStoredUrlThePageWouldNeverHaveSavedIsNamedForWhatItIs() {
        String token = "https://me:secret@github.com/me/s.git";
        assertEquals("sync.state.credentials", SettingsSync.unavailableKey(true, token, false, false));
        assertEquals("sync.state.invalid", SettingsSync.unavailableKey(true, "-x", false, false));
        assertEquals("sync.state.notSetUp", SettingsSync.unavailableKey(false, "git@h:r.git", false, false));
        assertEquals("sync.state.secondary", SettingsSync.unavailableKey(true, "git@h:r.git", true, false));
        assertNull(SettingsSync.unavailableKey(true, "git@h:r.git", true, true));
    }

    @Test
    void everyStoppedRunSaysWhy() {
        for (SyncReport.Status status : SyncReport.Status.values()) {
            if (status == SyncReport.Status.OK) continue;
            String text = SettingsSync.problemText(
                    new SyncReport(status, List.of(), List.of(), List.of(), "/home/me/.termina"));
            assertFalse(text.startsWith("sync."), status + " has no words: " + text);
        }
    }

    @Test
    void aSettingsFolderInsideADotfilesRepositoryIsFound(@TempDir Path home) throws Exception {
        GitRunner probe = new GitRunner(List.of("git"), home, false, "");
        assumeTrue(probe.available(), "git is not installed");
        Path config = Files.createDirectories(home.resolve(".termina"));
        GitRunner inConfig = new GitRunner(List.of("git"), config, false, "");
        assertFalse(SettingsSync.insideAnotherRepository(inConfig), "no repository at all");

        assertTrue(probe.run("init", "--quiet").ok());
        assertTrue(SettingsSync.insideAnotherRepository(inConfig));

        Files.writeString(config.resolve(".gitignore"), "sync/\n");
        assertFalse(SettingsSync.insideAnotherRepository(inConfig), "ignored, so the clone is not staged");
    }
}
