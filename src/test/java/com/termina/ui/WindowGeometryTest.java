package com.termina.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Restoring a window size onto whatever screen is actually present. */
class WindowGeometryTest {

    @Test
    void aSavedSizeIsUsedAsIs() {
        assertEquals(1400, WindowGeometry.fit(1400, 900, 2560));
    }

    @Test
    void nothingSavedMeansTheDefault() {
        assertEquals(900, WindowGeometry.fit(0, 900, 2560));
        assertEquals(900, WindowGeometry.fit(-1, 900, 2560));
    }

    @Test
    void aSizeFromABiggerScreenIsClampedToThisOne() {
        // The case that matters: saved on a 4K monitor, reopened on the laptop. Unclamped, the
        // window's own title bar is off-screen and it cannot be resized back by mouse.
        assertEquals(1440, WindowGeometry.fit(3840, 900, 1440));
    }

    @Test
    void anAbsurdlySmallSavedSizeIsRaisedToSomethingUsable() {
        assertEquals(WindowGeometry.MIN, WindowGeometry.fit(12, 900, 2560));
    }

    @Test
    void aScreenSmallerThanTheMinimumStillFits() {
        // A minimum that exceeds the screen would put us back where we started.
        assertEquals(200, WindowGeometry.fit(900, 900, 200));
    }
}
