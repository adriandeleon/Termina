package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chord that picks a pane up, and every smaller one that must not. */
class PaneDragTest {

    @Test
    void cmdOptionShiftOnMacCtrlAltShiftElsewhere() {
        assertTrue(PaneDrag.starts(true, true, false, true, true));
        assertTrue(PaneDrag.starts(false, true, true, true, false));
    }

    @Test
    void theChordsAlreadyTakenDoNotStartIt() {
        // Alt alone is a block, Shift alone the mouse-reporting bypass, Ctrl or Cmd alone a link.
        assertFalse(PaneDrag.starts(false, false, false, true, false));
        assertFalse(PaneDrag.starts(false, true, false, false, false));
        assertFalse(PaneDrag.starts(false, false, true, false, false));
        assertFalse(PaneDrag.starts(true, false, false, false, true));
        // Shift and Alt together are a block over a program that has the mouse.
        assertFalse(PaneDrag.starts(false, true, false, true, false));
        assertFalse(PaneDrag.starts(true, true, false, true, false));
    }

    @Test
    void theOtherPlatformsChordDoesNotCount() {
        assertFalse(PaneDrag.starts(true, true, true, true, false));
        assertFalse(PaneDrag.starts(false, true, false, true, true));
    }
}
