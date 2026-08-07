package com.termina.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tabs tiling the width, the way macOS Terminal and GNOME Terminal lay them out. */
class TabLayoutTest {

    private static final double RESERVED = 40; // the new-tab button
    private static final double CHROME = 17; // per-tab padding JavaFX adds outside the set width

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5, 8, 12})
    void tabsNeverOverflowTheStrip(int count) {
        double strip = 900;
        double each = TabLayout.tabWidth(strip, count, RESERVED, CHROME);
        if (each <= TabLayout.MIN_TAB_WIDTH) return; // past the floor, overflow is expected

        double rendered = (each + CHROME) * count;
        assertTrue(rendered <= strip - RESERVED,
                () -> count + " tabs render " + rendered + " into " + (strip - RESERVED));
    }

    @Test
    void tabsShrinkAsMoreAreAdded() {
        double three = TabLayout.tabWidth(900, 3, RESERVED, CHROME);
        double six = TabLayout.tabWidth(900, 6, RESERVED, CHROME);
        double twelve = TabLayout.tabWidth(900, 12, RESERVED, CHROME);
        assertTrue(three > six, "six tabs should be narrower than three");
        assertTrue(six > twelve, "twelve should be narrower than six");
    }

    @Test
    void aLoneTabDoesNotStretchAcrossTheWindow() {
        // Filling the width with one tab reads as a title bar. Neither macOS nor GNOME does it.
        assertEquals(TabLayout.MAX_TAB_WIDTH, TabLayout.tabWidth(1600, 1, RESERVED, CHROME));
    }

    @Test
    void tabsStopShrinkingAtALegibleWidth() {
        // Past the floor the strip overflows rather than rendering slivers with no readable title.
        assertEquals(TabLayout.MIN_TAB_WIDTH, TabLayout.tabWidth(900, 40, RESERVED, CHROME));
    }

    @Test
    void aStripTooNarrowToUseDoesNotProduceNonsense() {
        // Reachable during the first layout pass, before the pane has been given a width.
        assertEquals(TabLayout.MIN_TAB_WIDTH, TabLayout.tabWidth(0, 3, RESERVED, CHROME));
        assertEquals(TabLayout.MIN_TAB_WIDTH, TabLayout.tabWidth(20, 3, RESERVED, CHROME));
    }

    @Test
    void noTabsIsNotADivideByZero() {
        assertEquals(TabLayout.MAX_TAB_WIDTH, TabLayout.tabWidth(900, 0, RESERVED, CHROME));
    }
}
