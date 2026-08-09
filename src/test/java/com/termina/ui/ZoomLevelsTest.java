package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The zoom ladder. */
class ZoomLevelsTest {

    @Test
    void steppingUpAndDownFollowsTheLadder() {
        assertEquals(1.1, ZoomLevels.in(1.0), 1e-9);
        assertEquals(1.2, ZoomLevels.in(1.1), 1e-9);
        assertEquals(0.9, ZoomLevels.out(1.0), 1e-9);
        assertEquals(1.0, ZoomLevels.out(1.1), 1e-9);
    }

    @Test
    void aStoredValueStillFindsItsPlaceOnTheLadder() {
        // 0.67 does not survive a round trip through a properties file exactly, and a strict
        // comparison would step from it to itself — the zoom would appear stuck.
        assertEquals(0.8, ZoomLevels.in(0.6700000001), 1e-9);
        assertEquals(0.5, ZoomLevels.out(0.6699999999), 1e-9);
    }

    @Test
    void theEndsOfTheLadderHold() {
        assertEquals(ZoomLevels.max(), ZoomLevels.in(ZoomLevels.max()), 1e-9);
        assertEquals(ZoomLevels.min(), ZoomLevels.out(ZoomLevels.min()), 1e-9);
        // And from beyond them, which a hand-edited settings file can ask for.
        assertEquals(ZoomLevels.max(), ZoomLevels.in(99), 1e-9);
        assertEquals(ZoomLevels.min(), ZoomLevels.out(0.01), 1e-9);
    }

    @Test
    void aValueOffTheLadderStepsToTheNextRungRatherThanSnapping() {
        // Arrived at by a hand-edited file. Stepping should move, not round in place.
        assertTrue(ZoomLevels.in(1.05) > 1.05);
        assertTrue(ZoomLevels.out(1.05) < 1.05);
    }

    @Test
    void thePercentageIsWhatTheRowShows() {
        assertEquals(100, ZoomLevels.percent(1.0));
        assertEquals(110, ZoomLevels.percent(1.1));
        assertEquals(67, ZoomLevels.percent(0.67));
        assertEquals(300, ZoomLevels.percent(3.0));
    }

    @Test
    void clampKeepsAHandEditedFileInRange() {
        assertEquals(ZoomLevels.max(), ZoomLevels.clamp(50), 1e-9);
        assertEquals(ZoomLevels.min(), ZoomLevels.clamp(-1), 1e-9);
        assertEquals(ZoomLevels.DEFAULT, ZoomLevels.clamp(Double.NaN), 1e-9);
    }
}
