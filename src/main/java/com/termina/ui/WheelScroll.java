package com.termina.ui;

/**
 * Turns wheel and trackpad deltas into whole lines of scrollback, without losing the fractions.
 *
 * <p>A mouse wheel reports one event per notch; a trackpad or a high-resolution wheel reports
 * dozens of small ones for the same travel. Scrolling a fixed number of lines per event, as the
 * first version did, made the second kind fly: every two-pixel event was worth three lines. The
 * distance is what the user moved, so the distance is what scrolls — the same
 * {@code deltaY / lineHeight} the mouse-reporting path uses — and the part of a line left over is
 * carried into the next event rather than rounded away or rounded up, so a slow drag still arrives
 * one line at a time instead of never or in bursts.
 *
 * <p>Inertia is honoured as ordinary scrolling. A fling is still the user's gesture, and on a
 * trackpad most of the distance is in the tail of it; what used to make it unusable was the per-event
 * minimum, not the inertia. The carry is dropped when the direction reverses, so a remainder from a
 * scroll up cannot pay for part of a scroll down.
 *
 * <p>Not thread-safe and not meant to be: one per view, used on the FX thread.
 */
final class WheelScroll {

    private double carry;

    /**
     * Lines to move the viewport by. Positive is back into history, matching JavaFX's sign for a
     * wheel turned away from the user.
     */
    int lines(double deltaY, double lineHeight) {
        if (deltaY == 0) return 0;
        // Before the first layout has measured the font: a notch is a line, which is at least a
        // direction, rather than a division by zero.
        double lines = lineHeight > 0 ? deltaY / lineHeight : Math.signum(deltaY);
        if (Math.signum(lines) != Math.signum(carry)) carry = 0;
        carry += lines;
        int whole = (int) (carry < 0 ? Math.ceil(carry) : Math.floor(carry));
        carry -= whole;
        return whole;
    }

    /** Forgets any fraction in hand — for when the view jumps for a reason that was not the wheel. */
    void reset() {
        carry = 0;
    }
}
