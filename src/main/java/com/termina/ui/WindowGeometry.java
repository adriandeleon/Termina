package com.termina.ui;

/**
 * The size a window should open at, given what was saved and what the screen can show.
 *
 * <p>Pure because the interesting case is not the arithmetic but the monitor that is no longer
 * there: a size saved on a 4K display, restored on a laptop, opens a window whose title bar and
 * close button are both off-screen. That is unrecoverable by mouse, and it only happens to people
 * who have already closed the lid — which is to say, never during development.
 */
final class WindowGeometry {

    /** Below this a window has no usable terminal in it, whatever the saved value claims. */
    static final double MIN = 320;

    private WindowGeometry() {}

    /**
     * @param saved the width or height that was stored, or a non-positive value if none was
     * @param fallback the default to use when nothing was stored
     * @param available the screen's usable extent in that direction
     */
    static double fit(double saved, double fallback, double available) {
        double wanted = saved > 0 ? saved : fallback;
        if (available > 0 && wanted > available) wanted = available;
        return Math.max(wanted, Math.min(MIN, available > 0 ? available : MIN));
    }
}
