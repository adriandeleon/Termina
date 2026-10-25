package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.termina.sync.SyncReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
