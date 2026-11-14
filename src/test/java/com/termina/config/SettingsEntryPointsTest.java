package com.termina.config;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ways the app reaches its settings: the folder it resolves, the deferred saver, the paste switch. */
class SettingsEntryPointsTest {

    @TempDir
    Path dir;

    @Test
    void theFolderIsTheOverrideWhenThereIsOne() {
        assertEquals(dir.resolve(Settings.SETTINGS_FILE), Settings.defaultFile(dir.toString()));
        assertEquals(dir.resolve(Settings.SETTINGS_FILE), Settings.defaultFile(" " + dir + " ", true));
        assertTrue(Settings.defaultFile("").endsWith(Settings.SETTINGS_FILE));
        assertTrue(Settings.defaultFile().endsWith(Settings.SETTINGS_FILE));
    }

    @Test
    void theApplicationsSettingsSaveLaterAndFlushOnRequest() {
        Settings settings = Settings.deferred(dir.resolve("settings.json"));
        settings.load();
        settings.setFontSize(15);
        settings.setConfirmPaste(false);
        assertTrue(settings.hasPendingSave(), "a change waits for the next one");
        settings.flush();
        assertFalse(settings.hasPendingSave());
        Settings reread = new Settings(dir.resolve("settings.json"));
        reread.load();
        assertEquals(15, reread.fontSize());
        assertFalse(reread.confirmPaste());
        assertTrue(reread.effectiveFontSize() > 0);
    }
}
