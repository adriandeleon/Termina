package com.termina.ui;

import com.termina.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UI zoom's arithmetic (WIN-12, MENU-16, Pauta 6.0.1): which numbers of a stylesheet are
 * lengths, how far the zoom goes, and when its commands have nothing to do.
 */
class UiZoomTest {

    @Test
    void aSheetAtTheDefaultZoomIsTheSheetAsWritten() {
        String css = ".a { -fx-padding: 4 8; -fx-font-size: 14px; }";
        assertEquals(css, UiZoom.scale(css, 100));
    }

    @Test
    void aLengthWithItsUnitIsScaledWhereverItStands() {
        assertEquals(".root { -fx-font-size: 21px; }", UiZoom.scale(".root { -fx-font-size: 14px; }", 150));
        // The menus' two sizes (MENU-16): 14 and 11 at 100%.
        assertEquals("-fx-font-size: 16.5px;", UiZoom.scale("-fx-font-size: 11px;", 150));
        // A property this class has never heard of: px is a length all the same.
        assertEquals("-fx-icon-size: 32px;", UiZoom.scale("-fx-icon-size: 16px;", 200));
        assertEquals("-fx-background-insets: 0, 3px;", UiZoom.scale("-fx-background-insets: 0, 2px;", 150));
    }

    @Test
    void aBareNumberIsALengthOnlyInAPropertyThatTakesOne() {
        assertEquals("-fx-padding: 6px 12px 0 3px;", UiZoom.scale("-fx-padding: 4 8 0 2;", 150));
        assertEquals("-fx-border-insets: -4.5px;", UiZoom.scale("-fx-border-insets: -3;", 150));
        assertEquals("-fx-background-radius: 9px 9px 0 0;", UiZoom.scale("-fx-background-radius: 6 6 0 0;", 150));
        assertEquals("-fx-min-width: 3.3px;", UiZoom.scale("-fx-min-width: 2.2;", 150));
        // Not lengths: a ratio, a scale, a count, a path, and a glyph's stroke, which is scaled as a node.
        for (String untouched : new String[] {
            "-fx-opacity: 0.5;",
            "-fx-scale-x: 2;",
            "-fx-pref-column-count: 12;",
            "-fx-shape: \"M0 0 L10 10 Z\";",
            "-fx-stroke-width: 1.3;",
            "-fx-text-fill: -color-base-3;",
            "-fx-background-color: #12141d;",
        }) {
            assertEquals(untouched, UiZoom.scale(untouched, 150), untouched);
        }
    }

    @Test
    void whatIsRelativeAlreadyIsLeftAlone() {
        assertEquals("-fx-font-size: 1.6em;", UiZoom.scale("-fx-font-size: 1.6em;", 150));
        assertEquals("-fx-padding: 0.25em 12px;", UiZoom.scale("-fx-padding: 0.25em 8px;", 150));
        assertEquals("-fx-pref-width: 50%;", UiZoom.scale("-fx-pref-width: 50%;", 150));
    }

    @Test
    void theBundledSheetsScaleTheirRootAndTheMenus() {
        // The theme's one root size, which every em in it follows, and the menus' own two.
        String theme = UiZoom.scale(".root {\n  -fx-font-size: 14px;\n}", 120);
        assertTrue(theme.contains("-fx-font-size: 16.8px;"), theme);
        assertTrue(UiZoom.dataUrl(".a{}").startsWith("data:text/css;base64,"));
    }

    @Test
    void theZoomStepsByTenAndStopsAtItsEnds() {
        assertEquals(110, UiZoom.in(100));
        assertEquals(90, UiZoom.out(100));
        assertEquals(Settings.MAX_UI_ZOOM, UiZoom.in(Settings.MAX_UI_ZOOM));
        assertEquals(Settings.MIN_UI_ZOOM, UiZoom.out(Settings.MIN_UI_ZOOM));
        assertEquals(Settings.MIN_UI_ZOOM, Settings.clampUiZoom(5));
        assertEquals(Settings.MAX_UI_ZOOM, Settings.clampUiZoom(5000));
        assertEquals(100, Settings.DEFAULT_UI_ZOOM);
    }

    @Test
    void aZoomCommandWithNothingToDoSaysWhy() {
        assertNull(WindowRules.uiZoomUnavailableKey("view.uiZoomIn", 100));
        assertNull(WindowRules.uiZoomUnavailableKey("view.uiZoomOut", 100));
        assertEquals("palette.disabled.uiZoomDefault", WindowRules.uiZoomUnavailableKey("view.uiZoomReset", 100));
        assertEquals("palette.disabled.uiZoomLargest", WindowRules.uiZoomUnavailableKey("view.uiZoomIn", 200));
        assertEquals("palette.disabled.uiZoomSmallest", WindowRules.uiZoomUnavailableKey("view.uiZoomOut", 80));
        assertNull(WindowRules.uiZoomUnavailableKey("view.uiZoomReset", 150));
        assertNull(WindowRules.uiZoomUnavailableKey("view.zoomIn", 200), "the terminal's zoom is another command");
    }

    @Test
    void thePalettesListGrowsItsRowsNotItsInsets() {
        double rows = CommandPalette.VISIBLE_ROWS * CommandPalette.ROW_HEIGHT;
        assertEquals(rows + 10, CommandPalette.listHeight(10, 1), 0.001);
        assertEquals(rows * 1.5 + 10, CommandPalette.listHeight(10, 1.5), 0.001);
        assertFalse(rows <= 0);
    }
}
