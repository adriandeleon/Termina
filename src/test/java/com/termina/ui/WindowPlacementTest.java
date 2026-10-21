package com.termina.ui;

import javafx.geometry.Rectangle2D;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Where a window opened from another one lands. */
class WindowPlacementTest {

    private static final Rectangle2D SCREEN = new Rectangle2D(0, 0, 1920, 1080);

    @Test
    void aCascadeOffsetsFromTheOriginatingWindow() {
        assertArrayEquals(new double[] {128, 228}, WindowPlacement.offsetWithin(100, 200, 28, 800, 600, SCREEN));
    }

    @Test
    void aWindowThatWouldHangOffTheScreenIsPulledBack() {
        // From a window near the bottom-right corner the plain offset puts the new title bar past
        // the edge, where it cannot be grabbed.
        assertArrayEquals(
                new double[] {1920 - 800, 1080 - 600}, WindowPlacement.offsetWithin(1500, 900, 28, 800, 600, SCREEN));
    }

    @Test
    void aSecondScreenToTheLeftHasNegativeCoordinatesAndThatIsFine() {
        Rectangle2D left = new Rectangle2D(-1920, 0, 1920, 1080);
        assertArrayEquals(new double[] {-1900 + 28, 128}, WindowPlacement.offsetWithin(-1900, 100, 28, 800, 600, left));
        // Clamped against that screen's own bounds, not the primary's.
        assertArrayEquals(new double[] {-800, 480}, WindowPlacement.offsetWithin(-100, 900, 28, 800, 600, left));
    }

    @Test
    void aWindowLargerThanTheScreenIsPinnedToItsOrigin() {
        // So at least the title bar is reachable.
        assertArrayEquals(new double[] {0, 0}, WindowPlacement.offsetWithin(100, 100, 28, 3000, 2000, SCREEN));
    }

    @Test
    void noScreenMeansNoClamping() {
        assertArrayEquals(new double[] {128, 228}, WindowPlacement.offsetWithin(100, 200, 28, 800, 600, null));
    }
}
