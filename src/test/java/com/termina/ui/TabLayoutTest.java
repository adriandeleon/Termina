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

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 6})
    void tabsAlwaysReachTheNewTabButton(int count) {
        // The point of the whole feature: whatever the count, the tabs divide the strip and meet
        // the button. An earlier cap of 260px per tab was hit at two tabs on any wide window and
        // left most of the strip empty.
        double strip = 1800;
        double each = TabLayout.tabWidth(strip, count, RESERVED, CHROME);
        double rendered = (each + CHROME) * count;
        double slack = (strip - RESERVED) - rendered;
        assertTrue(slack >= 0, () -> count + " tabs overflow by " + (-slack));
        assertTrue(slack < count + 1,
                () -> count + " tabs leave " + slack + "px unused before the button");
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
        assertEquals(TabLayout.MIN_TAB_WIDTH, TabLayout.tabWidth(900, 0, RESERVED, CHROME));
    }
}
