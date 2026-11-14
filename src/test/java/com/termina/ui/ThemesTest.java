package com.termina.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.application.ColorScheme;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Built-in and imported themes, side by side. */
class ThemesTest {

    @TempDir
    Path config;

    @AfterEach
    void forget() {
        Themes.useDirectory(null);
    }

    private Path fixture(String name) throws Exception {
        Path file = config.resolve(name);
        try (var in = ThemesTest.class.getResourceAsStream("/themes/" + name)) {
            Files.write(file, in.readAllBytes());
        }
        return file;
    }

    @Test
    void anImportedThemeIsListedAfterTheBuiltInOnesAndChosenById() throws Exception {
        Themes.useDirectory(config.resolve("themes"));
        List<ThemeEntry> added = Themes.importFile(fixture("Dracula.itermcolors"));
        assertEquals("user-dracula", added.getFirst().id());
        List<ThemeEntry> all = Themes.all();
        assertEquals(Theme.values().length + 1, all.size());
        assertEquals("Dracula", all.getLast().displayName());
        assertEquals(Color.web("#282a36"), Themes.byId("user-dracula").palette().background());
        assertTrue(Themes.byId("user-dracula").isDark());
        assertNotNull(Themes.byId("user-dracula").palette().selection());
    }

    @Test
    void windowsAreToldTheThemesSchemeNotTheDesktops() throws Exception {
        assertEquals(ColorScheme.DARK, ThemeEntry.of(Theme.EDITORA_DARK).colorScheme());
        assertEquals(ColorScheme.LIGHT, ThemeEntry.of(Theme.EDITORA_LIGHT).colorScheme());
        assertEquals(ColorScheme.LIGHT, ThemeEntry.of(Theme.CLEAR_LIGHT).colorScheme());
        // An imported scheme has no declared side: it is decided from the background it draws.
        Themes.useDirectory(config.resolve("themes"));
        Themes.importFile(fixture("Dracula.itermcolors"));
        assertEquals(ColorScheme.DARK, Themes.byId("user-dracula").colorScheme());
    }

    @Test
    void itSurvivesARestart() throws Exception {
        Themes.useDirectory(config.resolve("themes"));
        Themes.importFile(fixture("Dracula.toml"));
        Themes.useDirectory(config.resolve("themes"));
        assertEquals("Dracula", Themes.byId("user-dracula").displayName());
    }

    @Test
    void importingTheSameFileAgainReplacesItRatherThanAddingASecond() throws Exception {
        Themes.useDirectory(config.resolve("themes"));
        Themes.importFile(fixture("Dracula.itermcolors"));
        Themes.importFile(fixture("Dracula.json"));
        assertEquals(Theme.values().length + 1, Themes.all().size());
    }

    @Test
    void aBuiltInNameIsKeptApart() throws Exception {
        Themes.useDirectory(config.resolve("themes"));
        Path copy = config.resolve("copy.json");
        Files.writeString(copy, Files.readString(fixture("Dracula.json")).replace("\"Dracula\"", "\"Editora Dark\""));
        ThemeEntry added = Themes.importFile(copy).getFirst();
        assertEquals("Editora Dark (imported)", added.displayName());
        assertEquals("editora-dark", Themes.byId("editora-dark").id());
        assertTrue(Themes.byId("editora-dark").isBuiltIn());
    }

    @Test
    void removingOneFallsBackAndABuiltInCannotBeRemoved() throws Exception {
        Themes.useDirectory(config.resolve("themes"));
        Themes.importFile(fixture("Dracula.conf"));
        Themes.remove("user-dracula");
        assertEquals("editora-dark", Themes.byId("user-dracula").id());
        Themes.remove("editora-dark");
        assertEquals("editora-dark", Themes.byId("editora-dark").id());
    }

    /** SET-10 (Pauta 6.0.1): a newer build's theme is kept beside itself and not read. */
    @Test
    void aThemeFromANewerTerminaIsSetAsideAndNotListed() throws Exception {
        Path themes = Files.createDirectories(config.resolve("themes"));
        String current = com.termina.theme.ThemeFile.write(
                new com.termina.theme.ColorScheme("Night", 0x101010, 0xf0f0f0, new int[16], null, null));
        int newer = com.termina.theme.ThemeFile.SCHEMA_VERSION + 1;
        String fromTheFuture = current.replace(
                "\"schemaVersion\" : " + com.termina.theme.ThemeFile.SCHEMA_VERSION, "\"schemaVersion\" : " + newer);
        assertFalse(current.equals(fromTheFuture), "the file carries its version");
        Files.writeString(themes.resolve("night.json"), current);
        Files.writeString(themes.resolve("later.json"), fromTheFuture);
        Themes.useDirectory(themes);
        assertEquals(Theme.values().length + 1, Themes.all().size());
        assertEquals("Night", Themes.byId("user-night").displayName());
        Path backup = themes.resolve("later.json.v" + newer + ".bak");
        assertEquals(backup, Themes.backupOf(themes.resolve("later.json"), newer));
        assertEquals(fromTheFuture, Files.readString(backup));
        assertFalse(Files.exists(themes.resolve("later.json")));
        // Whatever named it falls back, as for any theme that is not there.
        assertEquals(
                Themes.byId("no-such-theme").id(), Themes.byId("user-later").id());

        // A second newer file of the same name does not overwrite the copy already kept.
        Files.writeString(themes.resolve("later.json"), fromTheFuture + "\n");
        Themes.useDirectory(themes);
        assertEquals(fromTheFuture, Files.readString(backup));
        assertEquals(Theme.values().length + 1, Themes.all().size());
    }

    @Test
    void aBrokenFileInTheFolderIsSkipped() throws Exception {
        Path themes = Files.createDirectories(config.resolve("themes"));
        Files.writeString(themes.resolve("broken.properties"), "name=Broken\n");
        Files.writeString(themes.resolve("also.json"), "{\"name\": \"Also broken\"}");
        Files.writeString(themes.resolve("notes.txt"), "not a theme at all");
        Themes.useDirectory(themes);
        assertEquals(Theme.values().length, Themes.all().size());
        // Neither is touched: a file that does not read is the user's to mend.
        assertTrue(Files.exists(themes.resolve("broken.properties")));
        assertFalse(Files.exists(themes.resolve("broken.json")));
    }

    /**
     * PLAT-14 (Pauta 6.0.1): a theme from before 0.15, a properties file, is rewritten as JSON the
     * first time it is read, under the same id, and the old file is kept.
     */
    @Test
    void aThemeStoredAsPropertiesIsRewrittenAsJsonOnce() throws Exception {
        Path themes = Files.createDirectories(config.resolve("themes"));
        StringBuilder old = new StringBuilder("schemaVersion=1\nname=Night\nbackground=#101010\nforeground=#f0f0f0\n");
        for (int i = 0; i < 16; i++)
            old.append("ansi.")
                    .append(i)
                    .append("=#0000")
                    .append(String.format("%02x", i))
                    .append('\n');
        Files.writeString(themes.resolve("night.properties"), old.toString());

        Themes.useDirectory(themes);
        ThemeEntry night = Themes.byId("user-night");
        assertEquals("Night", night.displayName());
        assertEquals(themes.resolve("night.json"), night.file());
        assertEquals(Color.web("#00000f"), night.palette().ansi(15));
        assertFalse(Files.exists(themes.resolve("night.properties")));
        assertEquals(old.toString(), Files.readString(themes.resolve("night.properties.migrated")));
        String json = Files.readString(themes.resolve("night.json"));
        assertEquals(com.termina.theme.ThemeFile.SCHEMA_VERSION, com.termina.theme.ThemeFile.versionOf(json));
        try (var files = Files.list(themes)) {
            assertEquals(2, files.count(), "the theme, the file it was, and no temporary");
        }

        // The next read finds the JSON file and writes nothing.
        var written = Files.getLastModifiedTime(themes.resolve("night.json"));
        Themes.useDirectory(themes);
        assertEquals(written, Files.getLastModifiedTime(themes.resolve("night.json")));
        assertEquals(Theme.values().length + 1, Themes.all().size());

        // With both forms of one name in the folder the JSON file is the theme, the other left alone.
        Files.writeString(themes.resolve("night.properties"), old.toString().replace("Night", "Older"));
        Themes.useDirectory(themes);
        assertEquals("Night", Themes.byId("user-night").displayName());
        assertTrue(Files.exists(themes.resolve("night.properties")));

        // Removing the theme retires the older file too, or it would bring the theme back at the next read.
        Themes.remove("user-night");
        assertFalse(Files.exists(themes.resolve("night.json")));
        assertFalse(Files.exists(themes.resolve("night.properties")));
        assertTrue(Files.exists(themes.resolve("night.properties.migrated")));
        assertEquals(Theme.values().length, Themes.all().size());
    }

    @Test
    void theChromeFollowsTheBackground() {
        assertTrue(Themes.isDark(Color.web("#282a36")));
        assertFalse(Themes.isDark(Color.web("#fdf6e3")));
        assertFalse(Themes.isDark(Color.WHITE));
    }

    @Test
    void slugsAreFileNames() {
        assertEquals("solarized-dark", Themes.slug("Solarized Dark"));
        assertEquals("cafe-noir", Themes.slug("Café  Noir!"));
        assertFalse(Themes.slug("../../etc/passwd").contains("/"));
        // Nothing Latin to keep: named for a hash, the same for the same name, never just "theme".
        assertTrue(Themes.slug("夜").matches("theme-[0-9a-f]{8}"), Themes.slug("夜"));
        assertEquals(Themes.slug("夜"), Themes.slug("夜"));
        assertFalse(Themes.slug("夜").equals(Themes.slug("Ночь")));
        for (String name : List.of("***", "夜", "Ночь", "Café  Noir!", "a/b\\c:d")) {
            assertTrue(Themes.slug(name).matches("[a-z0-9-]+"), name);
        }
    }

    @Test
    void namesThatSlugAlikeGetFilesOfTheirOwn() {
        java.util.Map<String, String> stored = new java.util.HashMap<>();
        assertEquals("gruvbox-dark", Themes.fileStem("Gruvbox Dark", stored));
        stored.put("gruvbox-dark", "Gruvbox Dark");
        assertEquals("gruvbox-dark-2", Themes.fileStem("Gruvbox (Dark)", stored));
        assertEquals("gruvbox-dark", Themes.fileStem("Gruvbox Dark", stored), "the exact name replaces itself");
        stored.put("gruvbox-dark-2", "Gruvbox (Dark)");
        assertEquals("gruvbox-dark-2", Themes.fileStem("Gruvbox (Dark)", stored));
        stored.put("x", null); // a file that does not read is somebody's
        assertEquals("x-2", Themes.fileStem("X", stored));
    }

    @Test
    void twoCollidingSchemesInOneFileAreBothKeptAndReportedOnce() throws Exception {
        Themes.useDirectory(config.resolve("themes"));
        String dracula = Files.readString(fixture("Dracula.json"));
        String one = dracula.replace("\"Dracula\"", "\"Gruvbox Dark\"");
        String two = dracula.replace("\"Dracula\"", "\"Gruvbox (Dark)\"");
        Path settings = config.resolve("settings.json");
        Files.writeString(settings, "{\"schemes\": [" + one + "," + two + "," + one + "]}");

        List<ThemeEntry> added = Themes.importFile(settings);

        assertEquals(
                List.of("Gruvbox Dark", "Gruvbox (Dark)"),
                added.stream().map(ThemeEntry::displayName).toList());
        assertEquals(Theme.values().length + 2, Themes.all().size());
        // Again: both replace themselves, nothing is added.
        Themes.importFile(settings);
        assertEquals(Theme.values().length + 2, Themes.all().size());
    }

    @Test
    void namesWithNoLatinLettersDoNotShareAFile() throws Exception {
        Themes.useDirectory(config.resolve("themes"));
        String dracula = Files.readString(fixture("Dracula.json"));
        Path night = config.resolve("night.json");
        Files.writeString(night, dracula.replace("\"Dracula\"", "\"夜\""));
        Path dark = config.resolve("dark.json");
        Files.writeString(dark, dracula.replace("\"Dracula\"", "\"Ночь\""));
        Themes.importFile(night);
        Themes.importFile(dark);
        assertEquals(Theme.values().length + 2, Themes.all().size());
    }

    @Test
    void aProfileThemeThatNamesNothingFallsBackToTheGlobalOneNotToTheDefault() {
        var style = new com.termina.config.Settings.ProfileStyle("", null, "user-gone");
        assertEquals(
                "clear-light", TerminalWindow.themeFor(style, "clear-light").id());
        var own = new com.termina.config.Settings.ProfileStyle("", null, "clear-dark");
        assertEquals("clear-dark", TerminalWindow.themeFor(own, "clear-light").id());
        assertEquals(
                "clear-light",
                TerminalWindow.themeFor(com.termina.config.Settings.ProfileStyle.NONE, "clear-light")
                        .id());
    }

    @Test
    void followingTheSystemResolvesToEditoraLightOrDarkAndChangesWithIt() {
        try {
            Themes.followSystem(false);
            assertEquals("editora-light", Themes.byId(Themes.SYSTEM_ID).id());
            Themes.followSystem(true);
            assertEquals("editora-dark", Themes.byId(Themes.SYSTEM_ID).id());
        } finally {
            Themes.followSystem(true);
        }
    }

    @Test
    void thePickerOffersTheSystemChoiceFirstAndStoresItsIdNotTodaysAnswer() {
        var first = Themes.byDisplayName().entrySet().iterator().next();
        assertEquals(Themes.systemDisplayName(), first.getKey());
        assertEquals(Themes.SYSTEM_ID, first.getValue().id());
        assertTrue(first.getValue().isBuiltIn(), "the system choice cannot be removed");
    }
}
