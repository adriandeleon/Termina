package com.termina.ui;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The themes and the palette they hand the renderer. */
class ThemeTest {

    @ParameterizedTest
    @EnumSource(Theme.class)
    void everyThemeResolvesAChromeStylesheet(Theme theme) {
        // A missing resource is invisible until someone selects that theme at runtime, and under
        // -Pdist it would mean the file was never packaged. Not necessarily named after the theme:
        // a ported terminal palette borrows the chrome sheet matching its brightness.
        assertNotNull(theme.stylesheet(), () -> "no stylesheet for " + theme.id());
        assertTrue(theme.stylesheet().endsWith(".css"));
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
        assertTrue(
                contrast(theme.palette().background(), theme.palette().foreground()) > 4.5,
                () -> theme.id() + " default text does not meet a readable contrast ratio");
    }

    /**
     * Legibility is a rule for the themes we design, not one we can impose on a ported one.
     *
     * <p>1-6 and 9-14 are the colours programs use to mean something — an error, a directory, a
     * diff line. 0/8 and 7/15 are the ends of the ramp and are legitimately faint on a ground of
     * the same polarity; bright black is what dim text uses.
     */
    @ParameterizedTest
    @EnumSource(
            value = Theme.class,
            names = {"EDITORA_DARK", "EDITORA_LIGHT"})
    void ourOwnThemesKeepEveryChromaticColourLegible(Theme theme) {
        Color background = theme.palette().background();
        Color[] ansi = theme.palette().ansi();
        for (int i : new int[] {1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14}) {
            double ratio = contrast(background, ansi[i]);
            assertTrue(
                    ratio > 1.9,
                    () -> theme.id() + " ANSI colour " + i + " at " + ratio + " is too close to the background");
        }
    }

    @Test
    void editoraLightKeepsItsWhitesReadable() {
        // Our own light theme darkens ANSI white deliberately, so a program that sets colour 7 does
        // not write invisible text. Asserted so the choice cannot be undone by accident.
        Color background = Theme.EDITORA_LIGHT.palette().background();
        Color[] ansi = Theme.EDITORA_LIGHT.palette().ansi();
        assertTrue(contrast(background, ansi[7]) > 4.0);
        assertTrue(contrast(background, ansi[15]) > 4.0);
    }

    @Test
    void clearLightIsFaintInFourPlacesAndThatIsApplesChoice() {
        // Recorded rather than corrected. Against its white background Apple's Clear Light puts
        // white at 1.69, bright yellow at 1.64, bright cyan at 1.53 and bright white at 1.33 — so
        // anything a program prints in those colours is hard to read. A ported theme that
        // "improves" its source is no longer that theme, but this should not be a surprise anyone
        // discovers on their own, so the exact set is pinned here.
        Color background = Theme.CLEAR_LIGHT.palette().background();
        Color[] ansi = Theme.CLEAR_LIGHT.palette().ansi();
        for (int i : new int[] {7, 11, 14, 15}) {
            assertTrue(
                    contrast(background, ansi[i]) < 2.0,
                    () -> "index " + i + " was expected to be one of Apple's faint colours");
        }
        // Everything else in the palette is fine, so the theme is usable — it is these four only.
        for (int i : new int[] {1, 2, 3, 4, 5, 6, 9, 10, 12, 13}) {
            assertTrue(contrast(background, ansi[i]) > 1.9, () -> "index " + i + " should be legible in Clear Light");
        }
    }

    @Test
    void portedThemesMatchApplesPublishedColours() {
        // Decoded from Terminal.app's own .terminal profiles, not matched by eye. Spot-checked so a
        // future edit cannot silently drift away from the theme it claims to be.
        assertEquals(Color.web("#191d27"), Theme.CLEAR_DARK.palette().background());
        assertEquals(Color.web("#e0e0e0"), Theme.CLEAR_DARK.palette().foreground());
        assertEquals(Color.web("#b45648"), Theme.CLEAR_DARK.palette().ansi()[1]);
        assertEquals(Color.web("#ffffff"), Theme.CLEAR_LIGHT.palette().background());
        assertEquals(Color.web("#2d3840"), Theme.CLEAR_LIGHT.palette().foreground());
        assertEquals(Color.web("#5685a8"), Theme.CLEAR_LIGHT.palette().ansi()[4]);
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
