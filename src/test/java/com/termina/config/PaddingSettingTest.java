package com.termina.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The inner padding: a few pixels by default, and clamped both ways, since the file is hand-edited. */
class PaddingSettingTest {

    @Test
    void defaultsToAFewPixels(@TempDir Path dir) {
        assertEquals(Settings.DEFAULT_PADDING, new Settings(dir.resolve("settings.properties")).padding());
        assertEquals(4, Settings.DEFAULT_PADDING);
    }

    @Test
    void isClampedOnWrite(@TempDir Path dir) {
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.setPadding(12);
        assertEquals(12, settings.padding());
        settings.setPadding(500);
        assertEquals(Settings.MAX_PADDING, settings.padding());
        settings.setPadding(-3);
        assertEquals(Settings.MIN_PADDING, settings.padding());
    }

    @Test
    void isClampedOnReadToo(@TempDir Path dir) throws IOException {
        // A padding of ten thousand would leave the shell the minimum grid in a window of any size.
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "terminal.padding=10000\n");
        Settings settings = new Settings(file);
        settings.load();
        assertEquals(Settings.MAX_PADDING, settings.padding());

        Files.writeString(file, "terminal.padding=-8\n");
        settings.load();
        assertEquals(Settings.MIN_PADDING, settings.padding());

        Files.writeString(file, "terminal.padding=wide\n");
        settings.load();
        assertEquals(Settings.DEFAULT_PADDING, settings.padding());
    }

    @Test
    void survivesARoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        Settings written = new Settings(file);
        written.setPadding(9);
        Settings read = new Settings(file);
        read.load();
        assertEquals(9, read.padding());
    }
}
