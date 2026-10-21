package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wheel distance to scrollback lines, with the remainder carried. */
class WheelScrollTest {

    private static final double LINE = 16;

    @Test
    void aNotchScrollsByItsDistance() {
        WheelScroll wheel = new WheelScroll();
        assertEquals(2, wheel.lines(32, LINE));
        assertEquals(-2, wheel.lines(-32, LINE));
    }

    @Test
    void smallEventsAccumulateIntoLinesInsteadOfEachBeingThree() {
        // A trackpad reports a few pixels at a time. Eight 2px events are one line, not 24.
        WheelScroll wheel = new WheelScroll();
        int total = 0;
        for (int i = 0; i < 8; i++) total += wheel.lines(2, LINE);
        assertEquals(1, total);
    }

    @Test
    void theRemainderIsCarriedNotRoundedUp() {
        WheelScroll wheel = new WheelScroll();
        assertEquals(1, wheel.lines(24, LINE)); // 1.5 lines: one now,
        assertEquals(1, wheel.lines(8, LINE)); // and the half completes with the next half
    }

    @Test
    void theCarryWorksInBothDirections() {
        WheelScroll wheel = new WheelScroll();
        assertEquals(-1, wheel.lines(-24, LINE));
        assertEquals(-1, wheel.lines(-8, LINE));
    }

    @Test
    void reversingDirectionDropsTheRemainder() {
        // A half-line owed upwards must not pay for part of a scroll down.
        WheelScroll wheel = new WheelScroll();
        wheel.lines(8, LINE);
        assertEquals(0, wheel.lines(-8, LINE));
        assertEquals(-1, wheel.lines(-8, LINE));
    }

    @Test
    void beforeTheFontIsMeasuredANotchIsALine() {
        WheelScroll wheel = new WheelScroll();
        assertEquals(1, wheel.lines(40, 0));
        assertEquals(-1, wheel.lines(-40, 0));
    }

    @Test
    void nothingIsNothing() {
        assertEquals(0, new WheelScroll().lines(0, LINE));
    }

    @Test
    void resetForgetsTheCarry() {
        WheelScroll wheel = new WheelScroll();
        wheel.lines(12, LINE);
        wheel.reset();
        assertEquals(0, wheel.lines(4, LINE));
    }
}
