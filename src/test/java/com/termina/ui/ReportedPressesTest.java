package com.termina.ui;

import javafx.scene.input.MouseButton;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A program hears the drag and release of a press it was told about, and of no other. */
class ReportedPressesTest {

    private final ReportedPresses presses = new ReportedPresses();

    @Test
    void aReportedPressIsOwedItsDragAndOneRelease() {
        presses.pressed(MouseButton.PRIMARY, true);
        assertTrue(presses.owesDrag(MouseButton.PRIMARY));
        assertTrue(presses.owesRelease(MouseButton.PRIMARY));
        // The press is over: a stray second release is nobody's.
        assertFalse(presses.owesRelease(MouseButton.PRIMARY));
        assertFalse(presses.owesDrag(MouseButton.PRIMARY));
    }

    @Test
    void aPressKeptFromTheProgramOwesItNothing() {
        // Opened a link, dismissed the menu, Shift-bypassed, landed on the scrollbar.
        presses.pressed(MouseButton.PRIMARY, false);
        assertFalse(presses.owesDrag(MouseButton.PRIMARY));
        assertFalse(presses.owesRelease(MouseButton.PRIMARY));
    }

    @Test
    void aReleaseWithNoPressAtAllIsNotReported() {
        // A press outside the view, released inside it.
        assertFalse(presses.owesRelease(MouseButton.SECONDARY));
    }

    @Test
    void aKeptPressReplacesAnEarlierReportedOneOnTheSameButton() {
        // A release lost to another window leaves the button marked; the next press decides afresh.
        presses.pressed(MouseButton.PRIMARY, true);
        presses.pressed(MouseButton.PRIMARY, false);
        assertFalse(presses.owesRelease(MouseButton.PRIMARY));
    }

    @Test
    void buttonsAreTrackedApart() {
        presses.pressed(MouseButton.PRIMARY, true);
        presses.pressed(MouseButton.SECONDARY, false);
        assertFalse(presses.owesRelease(MouseButton.SECONDARY));
        assertTrue(presses.owesRelease(MouseButton.PRIMARY));
    }
}
