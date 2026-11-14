package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACC-09 (Pauta 6.0.1): while Windows' high-contrast mode is on the app draws with a high-contrast
 * theme, and with the chosen one again when it ends; and the theme list has that theme.
 */
class HighContrastTest {

    @AfterEach
    void theModeIsOffForEveryOtherTest() {
        Themes.followHighContrast(false);
    }

    @Test
    void windowsSaysSoThroughOnePreference() {
        Map<String, Object> preferences = new HashMap<>();
        assertFalse(HighContrast.isOn(preferences), "no such key off Windows");
        preferences.put(HighContrast.KEY, Boolean.FALSE);
        assertFalse(HighContrast.isOn(preferences));
        preferences.put(HighContrast.KEY, Boolean.TRUE);
        assertTrue(HighContrast.isOn(preferences));
        preferences.put(HighContrast.KEY, "true");
        assertFalse(HighContrast.isOn(preferences), "only the boolean");
        assertEquals("Windows.SPI.HighContrast", HighContrast.KEY);
    }

    @Test
    void whileTheModeIsOnEverythingIsDrawnWithTheHighContrastThemeAndTheChoiceIsKept() {
        String chosen = Theme.EDITORA_LIGHT.id();
        assertEquals(chosen, Themes.drawn(chosen).id());

        Themes.followHighContrast(true);
        assertEquals(Theme.HIGH_CONTRAST.id(), Themes.drawn(chosen).id());
        assertEquals(Theme.HIGH_CONTRAST.id(), Themes.drawn(Themes.SYSTEM_ID).id());
        assertEquals(Theme.HIGH_CONTRAST.id(), Themes.drawn("no such theme").id());
        // What shows or edits the choice still reads the choice.
        assertEquals(chosen, Themes.byId(chosen).id());

        Themes.followHighContrast(false);
        assertEquals(chosen, Themes.drawn(chosen).id(), "the mode's end brings the chosen theme back");
    }

    @Test
    void theThemeListHasAHighContrastThemeAnybodyCanChoose() {
        assertTrue(Themes.all().stream().anyMatch(theme -> theme.id().equals(Theme.HIGH_CONTRAST.id())));
        ThemeEntry theme = Themes.byId(Theme.HIGH_CONTRAST.id());
        assertEquals("High Contrast", theme.displayName());
        assertTrue(theme.stylesheet().endsWith("/com/termina/styles/editora-contrast.css"), theme.stylesheet());
    }

    /** White on black, and every colour a program can ask for at 7:1 or better (WCAG AAA). */
    @Test
    void everyColourOfTheTerminalIsAtSevenToOneOnItsGround() {
        TerminalPalette palette = Theme.HIGH_CONTRAST.palette();
        assertEquals(Color.BLACK, palette.background());
        assertEquals(Color.WHITE, palette.foreground());
        for (int index = 1; index < 16; index++) {
            double ratio = contrast(palette.background(), palette.ansi(index));
            assertTrue(ratio >= 7.0, "colour " + index + " is " + ratio + ":1");
        }
        // Black is the ground itself: what a program paints with it is a block, not text.
        assertEquals(palette.background(), palette.ansi(0));
    }

    /** The chrome's text, borders and state colours at 7:1 on its ground too. */
    @Test
    void theChromesForegroundsAreAtSevenToOneOnItsGround() throws IOException {
        Map<String, String> tokens = tokens(sheet("editora-contrast.css"));
        Color ground = Color.web(tokens.get("bg-default"));
        assertEquals(Color.BLACK, ground);
        for (String name : List.of(
                "fg-default",
                "fg-muted",
                "fg-subtle",
                "border-default",
                "border-muted",
                "accent-fg",
                "accent-emphasis",
                "warning-fg",
                "success-fg",
                "danger-fg")) {
            double ratio = contrast(ground, Color.web(tokens.get(name)));
            assertTrue(ratio >= 7.0, name + " is " + ratio + ":1");
        }
        // And the text drawn on an accent-coloured control is black on that accent.
        assertTrue(contrast(Color.web(tokens.get("accent-emphasis")), Color.web(tokens.get("fg-emphasis"))) >= 7.0);
    }

    /**
     * The sheet is the dark one with other values for its colour tokens and nothing else changed,
     * so a fix to a control's rule in one reaches the other: scripts/high-contrast-theme.py writes it.
     */
    @Test
    void theSheetIsTheDarkOneWithOtherValuesForItsTokens() throws IOException {
        List<String> dark = rules(sheet("editora-dark.css"));
        List<String> contrast = rules(sheet("editora-contrast.css"));
        assertEquals(dark.size(), contrast.size(), "run scripts/high-contrast-theme.py");
        int changed = 0;
        boolean inPalette = true;
        for (int i = 0; i < dark.size(); i++) {
            String a = dark.get(i);
            String b = contrast.get(i);
            if (a.equals("}")) inPalette = false;
            if (a.equals(b)) continue;
            changed++;
            assertTrue(inPalette, "a rule differs after the palette block, line " + i + ": " + b);
            assertTrue(a.strip().startsWith("-color-") && b.strip().startsWith("-color-"), a + " / " + b);
            assertEquals(a.substring(0, a.indexOf(':')), b.substring(0, b.indexOf(':')), "the same token");
        }
        assertTrue(changed > 60, "tokens that differ: " + changed);
    }

    private static String sheet(String name) throws IOException {
        return Files.readString(Path.of("src/main/resources/com/termina/styles", name))
                .replace("\r\n", "\n");
    }

    /** A sheet's lines from its first rule on: the header comment is each sheet's own. */
    private static List<String> rules(String sheet) {
        return sheet.substring(sheet.indexOf(".root {")).lines().toList();
    }

    /** The palette block's {@code -color-*} tokens, by name without the prefix. */
    private static Map<String, String> tokens(String sheet) {
        Map<String, String> out = new HashMap<>();
        String block = sheet.substring(sheet.indexOf(".root {"), sheet.indexOf("\n}\n"));
        java.util.regex.Matcher token = java.util.regex.Pattern.compile("-color-([a-z0-9-]+): (#[0-9a-fA-F]{6});")
                .matcher(block);
        while (token.find()) out.putIfAbsent(token.group(1), token.group(2));
        return out;
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
