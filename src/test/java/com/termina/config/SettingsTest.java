package com.termina.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Persistence and the tolerance of a hand-edited file. */
class SettingsTest {

    @Test
    void unwrittenSettingsReportTheirDefaults(@TempDir Path dir) {
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.load(); // no file at all

        assertEquals("editora-dark", settings.themeId());
        assertEquals(Settings.DEFAULT_FONT_SIZE, settings.fontSize());
        assertEquals(Settings.DEFAULT_SCROLLBACK, settings.scrollbackLines());
        assertEquals(Settings.CursorShape.BLOCK, settings.cursorShape());
        assertTrue(settings.altIsMeta());
        assertTrue(settings.bell());
        assertEquals("", settings.shell());
    }

    @Test
    void valuesSurviveAReload(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        Settings written = new Settings(file);
        written.setThemeId("editora-light");
        written.setFontFamily("Menlo");
        written.setFontSize(17);
        written.setScrollbackLines(1234);
        written.setCursorShape(Settings.CursorShape.BAR);
        written.setAltIsMeta(false);
        written.setBell(false);
        written.setShell("/bin/bash");

        Settings read = new Settings(file);
        read.load();
        assertEquals("editora-light", read.themeId());
        assertEquals("Menlo", read.fontFamily());
        assertEquals(17, read.fontSize());
        assertEquals(1234, read.scrollbackLines());
        assertEquals(Settings.CursorShape.BAR, read.cursorShape());
        assertFalse(read.altIsMeta());
        assertFalse(read.bell());
        assertEquals("/bin/bash", read.shell());
    }

    @Test
    void everySetterPersistsImmediately(@TempDir Path dir) throws IOException {
        // There is no Save button, so a value that is not on disk the moment it changes is a value
        // lost to a crash or a force-quit.
        Path file = dir.resolve("settings.properties");
        new Settings(file).setFontSize(21);
        assertTrue(Files.readString(file).contains("21"));
    }

    @Test
    void aGarbledValueFallsBackToItsDefaultWithoutTakingTheRestWithIt(@TempDir Path dir) throws IOException {
        // The file is meant to be hand-editable, so one bad line must not cost the others.
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "font.size=enormous\nterminal.cursorShape=spiral\ntheme=editora-light\n");

        Settings settings = new Settings(file);
        settings.load();
        assertEquals(Settings.DEFAULT_FONT_SIZE, settings.fontSize());
        assertEquals(Settings.CursorShape.BLOCK, settings.cursorShape());
        assertEquals("editora-light", settings.themeId());
    }

    @Test
    void outOfRangeValuesAreClamped(@TempDir Path dir) {
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.setFontSize(9999);
        assertEquals(Settings.MAX_FONT_SIZE, settings.fontSize());
        settings.setFontSize(-5);
        assertEquals(Settings.MIN_FONT_SIZE, settings.fontSize());
        settings.setScrollbackLines(Integer.MAX_VALUE);
        assertEquals(Settings.MAX_SCROLLBACK, settings.scrollbackLines());
    }

    @Test
    void aClampedValueOnDiskIsStillClampedWhenRead(@TempDir Path dir) throws IOException {
        // Guards the asymmetry: clamping only on write would let a hand-edited file set a
        // 2-million-line scrollback and exhaust memory on the next launch.
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "terminal.scrollback=99999999\nfont.size=400\n");
        Settings settings = new Settings(file);
        settings.load();
        assertEquals(Settings.MAX_SCROLLBACK, settings.scrollbackLines());
        assertEquals(Settings.MAX_FONT_SIZE, settings.fontSize());
    }

    @Test
    void changeNotificationsDriveLiveApply(@TempDir Path dir) {
        AtomicInteger changes = new AtomicInteger();
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.setOnChange(changes::incrementAndGet);

        settings.setFontSize(20);
        assertEquals(1, changes.get());
        // Re-setting the same value must not fire: it would re-apply the theme and re-measure the
        // font on every spinner tick that did not actually change anything.
        settings.setFontSize(20);
        assertEquals(1, changes.get());
        settings.setThemeId("editora-light");
        assertEquals(2, changes.get());
    }

    @Test
    void resetClearsEverythingAndNotifies(@TempDir Path dir) {
        AtomicInteger changes = new AtomicInteger();
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.setThemeId("editora-light");
        settings.setFontSize(22);
        settings.setOnChange(changes::incrementAndGet);

        settings.resetToDefaults();
        assertEquals("editora-dark", settings.themeId());
        assertEquals(Settings.DEFAULT_FONT_SIZE, settings.fontSize());
        assertEquals(1, changes.get());
    }
}
