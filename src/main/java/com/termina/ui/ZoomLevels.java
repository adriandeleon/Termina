package com.termina.ui;

/**
 * The zoom steps, as a browser does them.
 *
 * <p>A ladder rather than a increment, because zoom is multiplicative: a fixed step that feels
 * right at 100% is imperceptible at 300% and violent at 30%. These are Firefox's own levels, which
 * is what makes the row read the way the one it is modelled on does.
 *
 * <p>Zoom is deliberately separate from the configured font size. They were the same number until
 * this existed — zooming edited the preference — which left no meaning for "100%" and made
 * resetting the zoom silently discard the size the user had chosen.
 */
final class ZoomLevels {

    private ZoomLevels() {}

    static final double DEFAULT = 1.0;

    private static final double[] STEPS = {0.3, 0.5, 0.67, 0.8, 0.9, 1.0, 1.1, 1.2, 1.33, 1.5, 1.7, 2.0, 2.4, 3.0};

    /** Doubles do not land on the table exactly; a stored 0.67 must still be found there. */
    private static final double EPSILON = 1e-6;

    static double min() {
        return STEPS[0];
    }

    static double max() {
        return STEPS[STEPS.length - 1];
    }

    /** The next step up, or the top of the ladder. */
    static double in(double current) {
        for (double step : STEPS) {
            if (step > current + EPSILON) return step;
        }
        return max();
    }

    /** The next step down, or the bottom. */
    static double out(double current) {
        for (int i = STEPS.length - 1; i >= 0; i--) {
            if (STEPS[i] < current - EPSILON) return STEPS[i];
        }
        return min();
    }

    /** What the row shows. Rounded, because 0.67 is a rendering of 67% and not the other way round. */
    static int percent(double zoom) {
        return (int) Math.round(clamp(zoom) * 100);
    }

    /** Keeps a hand-edited settings file, or an older one, inside the ladder's range. */
    static double clamp(double zoom) {
        if (Double.isNaN(zoom)) return DEFAULT;
        return Math.max(min(), Math.min(max(), zoom));
    }
}
