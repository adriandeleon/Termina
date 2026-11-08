package com.termina.config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.termina.config.ConfigArchive.Part;
import com.termina.config.ConfigArchive.Refused;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Export and Import of the configuration (SET-12, Pauta 2.0.2). */
class ConfigArchiveTest {

    @TempDir
    Path dir;

    @Test
    void theZipIsNamedForWhenItWasMade() {
        assertEquals(
                "termina-config-20261107-093005.zip", ConfigArchive.fileName(LocalDateTime.of(2026, 11, 7, 9, 30, 5)));
    }

    @Test
    void anExportHoldsTheConfigurationAndNotThisRunsFiles() throws IOException {
        Path config = dir.resolve("config");
        write(config.resolve("settings.json"), "{}");
        write(config.resolve("workspace-state.json"), "{}");
        write(config.resolve("settings.json.corrupt.bak"), "x");
        write(config.resolve("themes/dracula.properties"), "background=#282a36");
        for (String skipped : List.of(
                "settings.json.lock",
                "instance.lock",
                "instance.sock",
                "termina-session.log",
                "termina-session.log.1",
                ".settings.json.123.tmp",
                "sync/repo/keybindings/macos.properties",
                "backups/termina-config.before-import-20261101-000000.zip")) {
            write(config.resolve(skipped), "x");
        }

        Path zip = dir.resolve("out/" + ConfigArchive.fileName(LocalDateTime.of(2026, 11, 7, 9, 30, 5)));
        assertEquals(4, ConfigArchive.export(config, zip));

        assertEquals(
                List.of(
                        "settings.json",
                        "settings.json.corrupt.bak",
                        "themes/dracula.properties",
                        "workspace-state.json"),
                entries(zip));
        try (var siblings = Files.list(zip.getParent())) {
            assertEquals(List.of(zip), siblings.toList(), "no temporary is left beside the export");
        }
    }

    @Test
    void aBackupIsMadeInsideTheFolderWithoutContainingItself() throws IOException {
        Path config = dir.resolve("config");
        write(config.resolve("settings.json"), "{}");
        Path first = ConfigArchive.backup(config, LocalDateTime.of(2026, 11, 7, 9, 0));
        Path second = ConfigArchive.backup(config, LocalDateTime.of(2026, 11, 7, 10, 0));
        assertEquals(config.resolve("backups"), first.getParent());
        assertEquals(List.of("settings.json"), entries(second), "earlier backups stay out of later ones");
    }

    @Test
    void anExportReadsBackAsEveryPartItHolds() throws Exception {
        Path config = dir.resolve("config");
        Map<String, String> settings = Map.of(
                "theme", "dracula",
                "keys.newTab", "Ctrl+Shift+N",
                "profile.1.name", "zsh",
                "window.x", "40",
                "sync.repository", "git@example.com:me/dotfiles.git");
        Files.createDirectories(config);
        Files.write(config.resolve("settings.json"), ConfigArchive.settingsBytes(settings));
        write(config.resolve("themes/dracula.properties"), "background=#282a36");
        Path zip = dir.resolve("export.zip");
        ConfigArchive.export(config, zip);

        ConfigArchive.Scan scan = ConfigArchive.scan(zip);
        assertEquals(settings, scan.settings());
        assertEquals(EnumSet.allOf(Part.class), scan.parts());
        assertEquals(1, scan.count(Part.KEY_BINDINGS));
        assertEquals(1, scan.count(Part.THEMES));
    }

    @Test
    void eachKeyBelongsToOnePartOrToNone() {
        assertEquals(Part.PREFERENCES, ConfigArchive.partOf("font.size"));
        assertEquals(Part.KEY_BINDINGS, ConfigArchive.partOf("keys.commandPalette"));
        assertEquals(Part.PROFILES, ConfigArchive.partOf("profile.2.command"));
        assertEquals(Part.PROFILES, ConfigArchive.partOf("profileStyle.zsh.font"));
        assertEquals(Part.PROFILES, ConfigArchive.partOf(Settings.PROFILE_DEFAULT));
        assertEquals(Part.PROFILES, ConfigArchive.partOf(Settings.PROFILE_HIDDEN));
        // This computer's: never imported, never cleared by one.
        assertNull(ConfigArchive.partOf(Settings.WINDOW_WIDTH));
        assertNull(ConfigArchive.partOf(Settings.LAST_UPDATE_CHECK));
        assertNull(ConfigArchive.partOf(Settings.SYNC_REPOSITORY));
    }

    @Test
    void anImportReplacesOnlyTheTickedParts() {
        Map<String, String> here = Map.of(
                "theme", "solarized",
                "font.size", "15",
                "keys.newTab", "Ctrl+Shift+Y",
                "profile.1.name", "fish",
                "window.x", "100",
                "sync.repository", "git@here:me/dots.git");
        Map<String, String> zip = Map.of(
                "theme", "dracula",
                "keys.newTab", "Ctrl+Shift+N",
                "keys.closeTab", "none",
                "profile.1.name", "zsh",
                "window.x", "999",
                "sync.repository", "git@there:me/dots.git");

        Map<String, String> merged = ConfigArchive.merge(here, zip, EnumSet.of(Part.KEY_BINDINGS));

        Map<String, String> expected = new HashMap<>(here);
        expected.put("keys.newTab", "Ctrl+Shift+N");
        expected.put("keys.closeTab", "none");
        assertEquals(expected, merged);
    }

    @Test
    void aTickedPartIsReplacedWholeSoAKeyTheZipLacksGoesBackToItsDefault() {
        Map<String, String> here = Map.of("theme", "solarized", "font.size", "15", "window.x", "100");
        Map<String, String> zip = Map.of("theme", "dracula");

        Map<String, String> merged = ConfigArchive.merge(here, zip, EnumSet.of(Part.PREFERENCES));

        assertEquals(Map.of("theme", "dracula", "window.x", "100"), merged);
    }

    @Test
    void importingThroughSettingsWritesTheFileAndTellsTheListeners() throws Exception {
        Path file = dir.resolve("settings.json");
        Settings settings = new Settings(file);
        settings.load();
        settings.setThemeId("solarized");
        settings.setShowStatusBar(true);
        int[] heard = {0};
        settings.addOnChange(() -> heard[0]++);

        Map<String, String> next =
                ConfigArchive.merge(settings.values(), Map.of("theme", "dracula"), EnumSet.of(Part.PREFERENCES));
        settings.replaceStored(next);

        assertEquals("dracula", settings.themeId());
        assertFalse(settings.showStatusBar(), "a preference the zip lacks goes back to its default");
        assertEquals(1, heard[0]);
        Settings reread = new Settings(file);
        reread.load();
        assertEquals("dracula", reread.themeId());
        assertFalse(reread.showStatusBar());
    }

    @Test
    void themesAreReplacedAsASetAndOnlyWhenTicked() throws Exception {
        Path config = dir.resolve("config");
        write(config.resolve("themes/old.properties"), "background=#000000");
        Path zip = zip(Map.of(
                "settings.json", "{\"schemaVersion\": 1, \"settings\": {}}",
                "themes/new.properties", "background=#ffffff"));
        ConfigArchive.Scan scan = ConfigArchive.scan(zip);

        ConfigArchive.applyFiles(scan, EnumSet.of(Part.PREFERENCES), config);
        assertTrue(Files.exists(config.resolve("themes/old.properties")), "unticked: untouched");

        ConfigArchive.applyFiles(scan, EnumSet.of(Part.THEMES), config);
        assertFalse(Files.exists(config.resolve("themes/old.properties")));
        assertEquals("background=#ffffff", Files.readString(config.resolve("themes/new.properties")));
    }

    @Test
    void whatIsNotAnExportIsRefusedBeforeAnythingIsWritten() throws Exception {
        Path notZip = dir.resolve("notes.zip");
        Files.writeString(notZip, "just text");
        assertEquals(
                Refused.Reason.NOT_A_ZIP,
                assertThrows(Refused.class, () -> ConfigArchive.scan(notZip)).reason());

        Path noSettings = zip(Map.of("themes/a.properties", "x"));
        assertEquals(
                Refused.Reason.NO_SETTINGS,
                assertThrows(Refused.class, () -> ConfigArchive.scan(noSettings))
                        .reason());

        Path newer = zip(Map.of("settings.json", "{\"schemaVersion\": 99, \"settings\": {}}"));
        assertEquals(
                Refused.Reason.NEWER,
                assertThrows(Refused.class, () -> ConfigArchive.scan(newer)).reason());

        Path garbled = zip(Map.of("settings.json", "theme=dracula"));
        assertEquals(
                Refused.Reason.UNREADABLE_SETTINGS,
                assertThrows(Refused.class, () -> ConfigArchive.scan(garbled)).reason());
    }

    @Test
    void aPathOutOfTheFolderIsRefused() throws Exception {
        for (String evil : List.of("../settings.json", "/etc/passwd", "themes/../../x.properties", "C:/x", "a\\b")) {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("settings.json", "{\"schemaVersion\": 1, \"settings\": {}}");
            entries.put(evil, "x");
            Path zip = zip(entries);
            assertEquals(
                    Refused.Reason.UNSAFE_PATH,
                    assertThrows(Refused.class, () -> ConfigArchive.scan(zip)).reason(),
                    evil);
        }
    }

    @Test
    void onlyPlainThemeFilesAreTaken() {
        assertEquals("a.properties", ConfigArchive.themeName("themes/a.properties"));
        assertNull(ConfigArchive.themeName("themes/sub/a.properties"));
        assertNull(ConfigArchive.themeName("themes/.hidden.properties"));
        assertNull(ConfigArchive.themeName("themes/a.txt"));
        assertNull(ConfigArchive.themeName("other/a.properties"));
    }

    @Test
    void anEntryLargerThanTheLimitIsRefusedByWhatItInflatesTo() throws Exception {
        Path zip = dir.resolve("bomb.zip");
        try (OutputStream file = Files.newOutputStream(zip);
                ZipOutputStream out = new ZipOutputStream(file)) {
            out.putNextEntry(new ZipEntry("settings.json"));
            byte[] zeros = new byte[64 * 1024];
            for (long n = 0; n <= ConfigArchive.MAX_ENTRY_BYTES; n += zeros.length) out.write(zeros);
            out.closeEntry();
        }
        assertEquals(
                Refused.Reason.TOO_LARGE,
                assertThrows(Refused.class, () -> ConfigArchive.scan(zip)).reason());
    }

    @Test
    void aPartTheZipHasNothingForIsNotOffered() throws Exception {
        Path zip = zip(Map.of("settings.json", "{\"schemaVersion\": 1, \"settings\": {\"theme\": \"x\"}}"));
        assertEquals(Set.of(Part.PREFERENCES), ConfigArchive.scan(zip).parts());
    }

    // ---------------------------------------------------------------- helpers

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private Path zip(Map<String, String> entries) throws IOException {
        Path zip = Files.createTempFile(dir, "test", ".zip");
        try (OutputStream file = Files.newOutputStream(zip);
                ZipOutputStream out = new ZipOutputStream(file)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    private static List<String> entries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) names.add(entry.getName());
        }
        return names;
    }
}
