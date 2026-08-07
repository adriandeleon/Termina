package com.termina.ui;

/**
 * How wide each tab should be so the strip fills the window, the way macOS Terminal and GNOME
 * Terminal lay tabs out.
 *
 * <p>Pure, because it is arithmetic with several ways to be subtly wrong — a width that creeps a
 * pixel over the available space per tab makes the strip overflow into JavaFX's overflow menu at
 * some tab count and not others, which looks like a mystery rather than a rounding error.
 */
final class TabLayout {

    /** Below this a tab shows nothing useful, so tabs stop shrinking and the strip overflows. */
    static final double MIN_TAB_WIDTH = 60;

    /**
     * A lone tab does not stretch across the whole window. Filling the width with one tab reads as
     * a title bar rather than a tab, and neither macOS nor GNOME does it.
     */
    static final double MAX_TAB_WIDTH = 260;

    private TabLayout() {}

    /**
     * @param stripWidth width available to the whole strip
     * @param tabCount how many tabs there are
     * @param reserved space to leave for the new-tab button
     * @param chrome per-tab padding and close button, which sit outside the width being set
     * @return the width to give each tab, or {@link #MIN_TAB_WIDTH} when they can no longer fit
     */
    static double tabWidth(double stripWidth, int tabCount, double reserved, double chrome) {
        if (tabCount <= 0) return MAX_TAB_WIDTH;
        double available = stripWidth - reserved;
        if (available <= 0) return MIN_TAB_WIDTH;

        // Floor rather than round: rounding up puts the total a fraction over the available space,
        // and the strip overflows for some tab counts and not others.
        double each = Math.floor(available / tabCount) - chrome;
        if (each < MIN_TAB_WIDTH) return MIN_TAB_WIDTH;
        if (each > MAX_TAB_WIDTH) return MAX_TAB_WIDTH;
        return each;
    }
}
