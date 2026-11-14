package com.termina.ui;

import org.jspecify.annotations.Nullable;

/**
 * Where to put a window that opens from another one.
 *
 * <p>Pure, because the arithmetic is trivial and the screen is not: a new window cascaded from one
 * that sits near the bottom-right corner lands with its title bar past the edge, and on a second
 * monitor to the left, {@code x} is negative and the primary screen's bounds are the wrong ones to
 * clamp against. The caller supplies the bounds of the originating window's screen; this decides
 * the point.
 */
final class WindowPlacement {

    private WindowPlacement() {}

    /**
     * {@code (x + offset, y + offset)}, pulled back so a {@code width}-by-{@code height} window
     * stays inside {@code screen}.
     *
     * <p>Pulled back, not wrapped: a cascade that jumps to the top-left after a few windows is the
     * classic behaviour, but it puts the new window exactly over the first one, which is the thing
     * the offset exists to avoid. A window larger than the screen is pinned to the screen's origin
     * so that at least its title bar is reachable.
     *
     * @return {@code {x, y}}
     */
    static double[] offsetWithin(
            double x, double y, double offset, double width, double height, @Nullable Bounds screen) {
        double nx = x + offset;
        double ny = y + offset;
        if (screen != null) {
            nx = Math.min(nx, screen.maxX() - width);
            ny = Math.min(ny, screen.maxY() - height);
            nx = Math.max(nx, screen.minX());
            ny = Math.max(ny, screen.minY());
        }
        return new double[] {nx, ny};
    }

    /**
     * A screen's usable area, as JavaFX's {@code Rectangle2D} gives it, without the JavaFX type
     * (QUAL-05, Pauta 2.0.2); {@link SecondaryWindows#bounds} converts.
     */
    record Bounds(double minX, double minY, double maxX, double maxY) {}
}
