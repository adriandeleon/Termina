package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The control themes come through AtlantaFX, with its Primer themes behind them (PLAT-05). */
class ChromeThemeTest {

    @Test
    void theBundledEditoraSheetsAreFound() {
        for (Theme theme : Theme.values()) {
            String url = ThemeEntry.of(theme).stylesheet();
            assertTrue(url.endsWith("/com/termina/styles/" + theme.chrome() + ".css"), url);
        }
    }

    @Test
    void thereIsALightAndADarkTheme() {
        assertTrue(java.util.Arrays.stream(Theme.values()).anyMatch(Theme::isDark));
        assertTrue(java.util.Arrays.stream(Theme.values()).anyMatch(t -> !t.isDark()));
    }

    @Test
    void aMissingSheetFallsBackToAtlantaFxPrimerOfTheSameLightness() {
        assertEquals(ChromeTheme.primerUrl(true), new ChromeTheme("no-such-sheet", true).stylesheetUrl());
        assertEquals(ChromeTheme.primerUrl(false), new ChromeTheme("no-such-sheet", false).stylesheetUrl());
        assertTrue(ChromeTheme.primerUrl(true).contains("primer-dark"), ChromeTheme.primerUrl(true));
        assertTrue(ChromeTheme.primerUrl(false).contains("primer-light"), ChromeTheme.primerUrl(false));
    }

    @Test
    void anAtlantaFxThemeSaysWhetherItIsDark() {
        assertTrue(new ChromeTheme("editora-dark", true).isDarkMode());
        assertEquals("editora-light", new ChromeTheme("editora-light", false).getName());
    }
}
