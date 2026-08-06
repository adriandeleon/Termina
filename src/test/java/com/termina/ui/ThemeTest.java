package com.termina.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The themes and the palette they hand the renderer. */
class ThemeTest {

    @ParameterizedTest
    @EnumSource(Theme.class)
    void everyThemeShipsTheStylesheetItNames(Theme theme) {
        // A missing resource is invisible until someone selects that theme at runtime, and under
        // -Pdist it would mean the file was never packaged.
        assertNotNull(Theme.class.getResource("/com/termina/styles/" + theme.id() + ".css"),
                () -> "no stylesheet for " + theme.id());
        assertTrue(theme.stylesheet().endsWith(theme.id() + ".css"));
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void everyThemeDefinesAllSixteenAnsiColours(Theme theme) {
        assertEquals(16, theme.palette().ansi().length);
        for (Color colour : theme.palette().ansi()) {
            assertNotNull(colour);
        }
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void foregroundAndBackgroundDiffer(Theme theme) {
        // The whole screen being one colour is a failure mode worth one assertion.
        assertNotSame(theme.palette().background(), theme.palette().foreground());
        assertTrue(contrast(theme.palette().background(), theme.palette().foreground()) > 4.5,
                () -> theme.id() + " default text does not meet a readable contrast ratio");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void everyAnsiColourIsLegibleAgainstTheBackground(Theme theme) {
        // On a light theme this is the rule that forces ANSI "white" to actually be dark: a program
        // that sets colour 7 must not end up writing invisible text.
        Color background = theme.palette().background();
        Color[] ansi = theme.palette().ansi();
        for (int i = 1; i < 16; i++) {
            if (i == 8) continue; // bright black is deliberately faint — it is what dim text uses
            double ratio = contrast(background, ansi[i]);
            assertTrue(ratio > 1.9,
                    () -> theme.id() + " ANSI colour is too close to the background");
        }
    }

    @Test
    void unknownThemeIdsFallBackRatherThanFailing() {
        // A settings file naming a theme this build does not have must still open a terminal.
        assertEquals(Theme.EDITORA_DARK, Theme.byId("no-such-theme", Theme.EDITORA_DARK));
        assertEquals(Theme.EDITORA_LIGHT, Theme.byId("editora-light", Theme.EDITORA_DARK));
        assertEquals(Theme.EDITORA_DARK, Theme.byId(null, Theme.EDITORA_DARK));
    }

    @Test
    void paletteDoesNotShareItsArrayWithCallers() {
        // The renderer holds a palette for the life of a theme; a caller mutating the array it got
        // back would silently recolour the terminal.
        Theme theme = Theme.EDITORA_DARK;
        Color[] first = theme.palette().ansi();
        first[0] = Color.HOTPINK;
        assertNotSame(Color.HOTPINK, theme.palette().ansi()[0]);
    }

    @Test
    void extendedColourCubeMatchesXterm() {
        TerminalPalette palette = Theme.EDITORA_DARK.palette();
        // Index 196 is pure red in xterm's 6x6x6 cube; programs compute these arithmetically, so
        // they are not a theme's to restyle.
        assertEquals(Color.rgb(255, 0, 0), palette.resolve(indexed(196), Color.BLACK));
        // 232 is the darkest step of the grey ramp.
        assertEquals(Color.rgb(8, 8, 8), palette.resolve(indexed(232), Color.BLACK));
    }

    @Test
    void indexedColoursBelowSixteenComeFromTheTheme() {
        assertEquals(
                Theme.EDITORA_LIGHT.palette().ansi()[1],
                Theme.EDITORA_LIGHT.palette().resolve(indexed(1), Color.BLACK));
    }

    private static com.jediterm.terminal.TerminalColor indexed(int index) {
        return com.jediterm.terminal.TerminalColor.index(index);
    }

    /** WCAG relative-luminance contrast ratio. */
    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        double lighter = Math.max(la, lb);
        double darker = Math.min(la, lb);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
