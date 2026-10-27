package com.termina.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test
    void aBrokenFileInTheFolderIsSkipped() throws Exception {
        Path themes = Files.createDirectories(config.resolve("themes"));
        Files.writeString(themes.resolve("broken.properties"), "name=Broken\n");
        Themes.useDirectory(themes);
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
}
