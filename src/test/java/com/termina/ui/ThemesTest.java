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
        assertEquals("theme", Themes.slug("***"));
        assertFalse(Themes.slug("../../etc/passwd").contains("/"));
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
