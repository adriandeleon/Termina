package com.termina.ui;

/**
 * How see-through a terminal window is.
 *
 * <p>Pure because the floor is the whole point. Opacity is one of the few settings that can make an
 * application unusable *and* unfixable: at 5% the Settings window is invisible too, so there is no
 * way to put it back. Everything that reaches the stage goes through {@link #clamp} — a stored value
 * from a hand-edited file included.
 *
 * <p>This is plain alpha, not the blur macOS Terminal and Windows Terminal use. Those go through
 * NSVisualEffectView and DWM acrylic; JavaFX exposes neither, and without the blur the desktop
 * behind stays legible through the text, which is why the floor is as high as it is.
 */
final class WindowOpacity {

    /** Below this the text is competing with whatever is behind the window, and losing. */
    static final double MIN = 0.6;

    static final double MAX = 1.0;

    private WindowOpacity() {}

    static double clamp(double value) {
        if (Double.isNaN(value)) return MAX;
        if (value < MIN) return MIN;
        if (value > MAX) return MAX;
        return value;
    }

    /** The value as a whole percentage, for display. */
    static int percent(double value) {
        return (int) Math.round(clamp(value) * 100);
    }
}
