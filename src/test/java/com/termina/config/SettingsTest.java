package com.termina.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        settings.addOnChange(changes::incrementAndGet);

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
    void everyListenerHearsAChange(@TempDir Path dir) {
        // The window manager and the settings window both listen. A single-consumer setter had
        // whichever registered second silently replace the first.
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.addOnChange(first::incrementAndGet);
        settings.addOnChange(second::incrementAndGet);

        settings.setBell(false);
        assertEquals(1, first.get());
        assertEquals(1, second.get());

        settings.removeOnChange(second::incrementAndGet); // a different lambda: not removed
        settings.setBell(true);
        assertEquals(2, second.get());
    }

    @Test
    void bookkeepingIsSavedWithoutTheBroadcast(@TempDir Path dir) throws IOException {
        // The update timestamp is written moments after the first window shows; a broadcast there
        // rebuilt every font in every tab for nothing anyone could see.
        AtomicInteger changes = new AtomicInteger();
        Path file = dir.resolve("settings.properties");
        Settings settings = new Settings(file);
        settings.addOnChange(changes::incrementAndGet);

        settings.setLastUpdateCheck(1_700_000_000_000L);
        settings.setDismissedUpdate("9.9.9");
        settings.setWindowGeometry(10, 20, 800, 600, false);

        assertEquals(0, changes.get(), "quiet keys must not fire the change broadcast");
        String stored = Files.readString(file);
        assertTrue(stored.contains("1700000000000"), stored);
        assertTrue(stored.contains("9.9.9"), stored);
        assertTrue(stored.contains("window.x=10"), stored);
    }

    @Test
    void resetKeepsTheProfilesAndNotifies(@TempDir Path dir) {
        AtomicInteger changes = new AtomicInteger();
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.setThemeId("editora-light");
        settings.setFontSize(22);
        settings.replaceProfileBlock(
                java.util.Map.of("profile.1.id", "work", "profile.1.name", "Work", "profile.1.command", "ssh work"));
        settings.setDefaultProfileId("work");
        settings.setHiddenProfileIds("shell-fish");
        settings.addOnChange(changes::incrementAndGet);

        settings.resetToDefaults();

        assertEquals("editora-dark", settings.themeId());
        assertEquals(Settings.DEFAULT_FONT_SIZE, settings.fontSize());
        assertEquals(1, changes.get());
        // The button sits beside Close and people press it to get a font back. Four hand-written
        // WSL profiles vanishing with it is data loss, not a reset.
        assertEquals("ssh work", settings.profileBlock().get("profile.1.command"));
        assertEquals("work", settings.defaultProfileId());
        assertEquals("shell-fish", settings.hiddenProfileIds());
    }

    @Test
    void theResetRuleSparesExactlyTheProfileKeys() {
        java.util.Set<String> stored = java.util.Set.of(
                Settings.THEME,
                Settings.FONT_SIZE,
                Settings.WINDOW_WIDTH,
                Settings.LAST_UPDATE_CHECK,
                Settings.PROFILE_DEFAULT,
                Settings.PROFILE_HIDDEN,
                "profile.1.name",
                "profile.12.command",
                Settings.SYNC_ENABLED,
                Settings.SYNC_REPOSITORY);
        assertEquals(
                java.util.Set.of(Settings.THEME, Settings.FONT_SIZE, Settings.WINDOW_WIDTH, Settings.LAST_UPDATE_CHECK),
                Settings.keysToReset(stored));
    }

    @Test
    void aSaveGoesThroughATemporaryAndLeavesNoneBehind(@TempDir Path dir) throws IOException {
        // Rewriting in place truncates first; a kill between truncate and flush left an empty file
        // that the next launch read as "no settings", profiles and all.
        Path file = dir.resolve("settings.properties");
        Settings settings = new Settings(file);
        settings.setThemeId("editora-light");
        settings.setFontSize(19);

        assertTrue(Files.readString(file).contains("editora-light"));
        try (var listing = Files.list(dir)) {
            assertEquals(
                    java.util.List.of(),
                    listing.map(p -> p.getFileName().toString())
                            .filter(name -> name.endsWith(".tmp"))
                            .toList(),
                    "the temporary must have been renamed away");
        }
        assertNull(settings.lastSaveError());
    }

    @Test
    void twoProcessesOnOneFileKeepEachOthersChanges(@TempDir Path dir) {
        // Each used to rewrite the whole file from its own memory: change the theme in one, close a
        // window of the other, and the theme was back — and the other's stale keys.* could then sync
        // out as a local edit.
        Path file = dir.resolve("settings.properties");
        Settings seed = new Settings(file);
        seed.setThemeId("editora-dark");
        seed.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+Y"));

        Settings first = new Settings(file);
        first.load();
        Settings second = new Settings(file);
        second.load();

        first.setThemeId("editora-light");
        first.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+U"));
        second.setWindowGeometry(10, 20, 800, 600, false); // closing a window
        second.setFontSize(19);

        Settings read = new Settings(file);
        read.load();
        assertEquals("editora-light", read.themeId(), "the other process's save must not revert it");
        assertEquals(java.util.Map.of("newTab", "Ctrl+Shift+U"), read.keyBindings());
        assertEquals(19, read.fontSize());
        assertEquals(800, read.windowWidth());
    }

    @Test
    void aRemovalIsAChangeToo(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        Settings first = new Settings(file);
        first.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+Y", "closeTab", "none"));
        Settings second = new Settings(file);
        second.load();

        second.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+Y"));
        first.setFontSize(18); // must not put closeTab back

        Settings read = new Settings(file);
        read.load();
        assertEquals(java.util.Map.of("newTab", "Ctrl+Shift+Y"), read.keyBindings());
        assertEquals(18, read.fontSize());
    }

    @Test
    void concurrentSavesLoseNothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.properties");
        Settings a = new Settings(file);
        Settings b = new Settings(file);
        Thread one = new Thread(() -> {
            for (int i = 0; i < 40; i++) a.setLinkOpenCommand("a" + i);
        });
        Thread two = new Thread(() -> {
            for (int i = 0; i < 40; i++) b.setShell("b" + i);
        });
        one.start();
        two.start();
        one.join();
        two.join();
        assertNull(a.lastSaveError());
        assertNull(b.lastSaveError());

        Settings read = new Settings(file);
        read.load();
        assertEquals("a39", read.linkOpenCommand());
        assertEquals("b39", read.shell());
    }

    @Test
    void theMergeAppliesOnlyWhatChanged() {
        java.util.Map<String, String> disk = java.util.Map.of("theme", "theirs", "font.size", "12", "gone", "x");
        java.util.Map<String, String> base = java.util.Map.of("theme", "old", "font.size", "12", "gone", "x");
        java.util.Map<String, String> mine = java.util.Map.of("theme", "old", "font.size", "20", "added", "y");
        assertEquals(
                java.util.Map.of("theme", "theirs", "font.size", "20", "added", "y"), Settings.merge(disk, base, mine));
    }

    @Test
    void anUnreadableFileFallsBackToDefaultsAndIsKept(@TempDir Path dir) throws IOException {
        // A directory where the file should be: reading fails. Nothing may escape load().
        Path file = dir.resolve("settings.properties");
        Files.createDirectory(file);
        Settings settings = new Settings(file);
        settings.load();
        assertEquals("editora-dark", settings.themeId());
    }

    @Test
    void aBadFileIsCopiedAsideBeforeAnythingOverwritesIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "theme=mine\n");
        Path backup = Settings.backUpUnreadable(file, 0);
        assertNotNull(backup);
        assertTrue(backup.getFileName().toString().startsWith("settings.properties.bad-"), backup.toString());
        assertEquals("theme=mine\n", Files.readString(backup));
        assertNull(Settings.backUpUnreadable(dir.resolve("missing.properties"), 0));
    }

    @Test
    void aMalformedEscapeNoLongerStopsTheAppStarting(@TempDir Path dir) throws IOException {
        // Properties.load threw IllegalArgumentException for this, out of App.start.
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "terminal.shell=C:\\uxyz\\shell.exe\ntheme=editora-light\n");
        Settings settings = new Settings(file);
        settings.load();
        assertEquals("C:\\uxyz\\shell.exe", settings.shell());
        assertEquals("editora-light", settings.themeId());
    }

    @Test
    void aHandTypedWindowsPathSurvivesLoadAndSave(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(
                file,
                "terminal.shell=C:\\Windows\\System32\\wsl.exe\nlinks.openCommand=C:\\tools\\nu.exe {file}\n",
                java.nio.charset.StandardCharsets.UTF_8);
        Settings settings = new Settings(file);
        settings.load();
        assertEquals("C:\\Windows\\System32\\wsl.exe", settings.shell());
        assertEquals("C:\\tools\\nu.exe {file}", settings.linkOpenCommand());

        settings.setFontSize(15);
        Settings read = new Settings(file);
        read.load();
        assertEquals("C:\\Windows\\System32\\wsl.exe", read.shell());
        assertEquals("C:\\tools\\nu.exe {file}", read.linkOpenCommand());
    }

    @Test
    void theFileIsUtf8AndOldEscapesStillRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(
                file,
                "terminal.shell=/home/josé/bin/fish\nlinks.openCommand=caf\\u00e9 {file}\n",
                java.nio.charset.StandardCharsets.UTF_8);
        Settings settings = new Settings(file);
        settings.load();
        assertEquals("/home/josé/bin/fish", settings.shell());
        assertEquals("café {file}", settings.linkOpenCommand());

        settings.setThemeId("editora-light");
        assertTrue(
                Files.readString(file, java.nio.charset.StandardCharsets.UTF_8).contains("/home/josé/bin/fish"),
                "written back as UTF-8, legible");
    }

    @Test
    void aFailedSaveIsReportedRatherThanSwallowed(@TempDir Path dir) throws IOException {
        // The control shows the new value either way; this is the only way the user finds out the
        // file still holds the old one before the next launch does.
        Path notADirectory = dir.resolve("blocker");
        Files.writeString(notADirectory, "a file where a directory is needed");
        Settings settings = new Settings(notADirectory.resolve("settings.properties"));

        settings.setFontSize(19);

        assertNotNull(settings.lastSaveError());
        assertEquals(19, settings.fontSize(), "the in-memory value is still taken");
    }

    @Test
    void nanAndInfinityAreNotNumbersASettingCanHold(@TempDir Path dir) throws IOException {
        // Double.parseDouble reads "NaN" happily, and Math.max/min pass NaN straight through —
        // into Font.font(family, NaN).
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "font.size=NaN\nfont.zoom=Infinity\nwindow.opacity=-Infinity\n");
        Settings settings = new Settings(file);
        settings.load();
        assertEquals(Settings.DEFAULT_FONT_SIZE, settings.fontSize());
        assertEquals(Settings.DEFAULT_FONT_ZOOM, settings.fontZoom());
        assertEquals(1.0, settings.windowOpacity());

        settings.setFontSize(Double.NaN);
        settings.setFontZoom(Double.POSITIVE_INFINITY);
        assertEquals(Settings.DEFAULT_FONT_SIZE, settings.fontSize(), "a setter handed NaN writes nothing");
        assertEquals(Settings.DEFAULT_FONT_ZOOM, settings.fontZoom());
    }

    @Test
    void aWindowPositionIsRememberedOnlyWhenItIsSane(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Settings settings = new Settings(file);
        assertTrue(settings.windowX().isEmpty(), "nothing recorded yet");

        settings.setWindowGeometry(-120, 40, 800, 600, false);
        assertEquals(-120, settings.windowX().orElseThrow(), "a screen to the left has negative x");
        assertEquals(40, settings.windowY().orElseThrow());

        // A corrupt line reads as unset rather than as a window a hundred thousand pixels away.
        Files.writeString(file, "window.x=999999999\nwindow.y=NaN\n");
        Settings read = new Settings(file);
        read.load();
        assertTrue(read.windowX().isEmpty());
        assertTrue(read.windowY().isEmpty());

        settings.setWindowGeometry(Double.NaN, 1e9, 800, 600, false);
        assertEquals(-120, settings.windowX().orElseThrow(), "an insane position leaves the last sane one");
    }

    @Test
    void keyBindingsSurviveAReloadAsOneBlock(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        AtomicInteger changes = new AtomicInteger();
        Settings written = new Settings(file);
        written.addOnChange(changes::incrementAndGet);
        written.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+Y", "closeTab", Settings.UNBOUND));
        assertEquals(1, changes.get(), "a rebind that takes a chord is two lines and one broadcast");

        Settings read = new Settings(file);
        read.load();
        assertEquals(java.util.Map.of("newTab", "Ctrl+Shift+Y", "closeTab", "none"), read.keyBindings());

        read.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+Y"));
        assertEquals(java.util.Map.of("newTab", "Ctrl+Shift+Y"), read.keyBindings(), "a dropped line is gone");
    }

    @Test
    void anUnchangedBlockIsNotAChange(@TempDir Path dir) {
        AtomicInteger changes = new AtomicInteger();
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+Y"));
        settings.addOnChange(changes::incrementAndGet);
        settings.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+Y"));
        assertEquals(0, changes.get());
    }

    @Test
    void theOldPaletteLineIsReadAsItsBinding(@TempDir Path dir) throws IOException {
        // keys.commandPalette predates the rest; a file written before the rest existed keeps its palette chord. Its
        // blank form meant "the default" then and still does.
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "keys.commandPalette=Ctrl+Shift+P\nkeys.newTab=\n");
        Settings settings = new Settings(file);
        settings.load();
        assertEquals(java.util.Map.of("commandPalette", "Ctrl+Shift+P"), settings.keyBindings());
    }

    @Test
    void resetPutsTheKeysBack(@TempDir Path dir) {
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+Y"));
        settings.resetToDefaults();
        assertTrue(settings.keyBindings().isEmpty());
    }

    @Test
    void aResetLeavesSettingsSyncConnected(@TempDir Path dir) {
        // A reset is about this computer's preferences. Disconnecting the repository with it would
        // leave the next launch unsynced without a word; the cleared bindings are synced instead.
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.setSyncRepository("git@github.com:me/termina-sync.git");
        settings.setSyncBranch("termina");
        settings.setSyncEnabled(true);
        settings.replaceKeyBindings(java.util.Map.of("newTab", "Ctrl+Shift+Y"));

        settings.resetToDefaults();

        assertTrue(settings.syncEnabled());
        assertEquals("git@github.com:me/termina-sync.git", settings.syncRepository());
        assertEquals("termina", settings.syncBranch());
        assertTrue(settings.keyBindings().isEmpty());
        assertTrue(Settings.isSyncKey(Settings.SYNC_INTERVAL));
        assertFalse(Settings.isSyncKey(Settings.KEYS_PREFIX + "newTab"));
    }

    @Test
    void syncSettingsDefaultAndClamp(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Settings settings = new Settings(file);
        assertFalse(settings.syncEnabled());
        assertEquals("", settings.syncRepository());
        assertEquals(Settings.DEFAULT_SYNC_BRANCH, settings.syncBranch());
        assertTrue(settings.syncAuto());
        assertEquals(Settings.DEFAULT_SYNC_INTERVAL, settings.syncIntervalMinutes());

        // Hand-edited: a one-minute interval would be a fetch a minute against someone's host.
        Files.writeString(file, "sync.intervalMinutes=1\nsync.branch=  \n");
        Settings read = new Settings(file);
        read.load();
        assertEquals(Settings.MIN_SYNC_INTERVAL, read.syncIntervalMinutes());
        assertEquals(Settings.DEFAULT_SYNC_BRANCH, read.syncBranch());
        read.setSyncIntervalMinutes(1_000_000);
        assertEquals(Settings.MAX_SYNC_INTERVAL, read.syncIntervalMinutes());
    }
}
