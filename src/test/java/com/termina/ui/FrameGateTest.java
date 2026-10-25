package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameGateTest {

    @Test
    void onlyTheFirstClaimStartsTheTimer() {
        FrameGate gate = new FrameGate(3);
        assertTrue(gate.claim());
        // Every later write while it runs is free: no second start, no runLater.
        assertFalse(gate.claim());
        assertFalse(gate.claim());
    }

    @Test
    void stopsAfterTheIdleLimitAndNotBefore() {
        FrameGate gate = new FrameGate(3);
        gate.claim();
        assertFalse(gate.frame(false));
        assertFalse(gate.frame(false));
        assertTrue(gate.frame(false));
    }

    @Test
    void workResetsTheIdleCount() {
        FrameGate gate = new FrameGate(2);
        gate.claim();
        assertFalse(gate.frame(false));
        assertFalse(gate.frame(true));
        assertFalse(gate.frame(false));
        assertTrue(gate.frame(false));
    }

    @Test
    void releaseWithNothingPendingLeavesItStoppedAndClaimable() {
        FrameGate gate = new FrameGate(1);
        gate.claim();
        assertFalse(gate.release(() -> false));
        assertFalse(gate.isRunning());
        assertTrue(gate.claim());
    }

    /**
     * The race: a write that lands while the FX thread is deciding to stop loses its own claim,
     * because the timer is still marked running. The re-check after the release has to catch it,
     * or that output sits undrawn until something else wakes the view.
     */
    @Test
    void workThatLostItsClaimDuringTheStopIsPickedUp() {
        FrameGate gate = new FrameGate(1);
        gate.claim();
        assertTrue(gate.frame(false));
        assertFalse(gate.claim()); // the emulator's attempt, before the release
        assertTrue(gate.release(() -> true));
        assertTrue(gate.isRunning());
    }

    @Test
    void anIdleCountDoesNotCarryIntoTheNextRun() {
        FrameGate gate = new FrameGate(3);
        gate.claim();
        gate.frame(false);
        gate.frame(false);
        gate.frame(false);
        gate.release(() -> false);
        gate.claim();
        assertFalse(gate.frame(false));
    }
}
