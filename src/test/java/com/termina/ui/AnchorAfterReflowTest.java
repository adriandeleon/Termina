package com.termina.ui;

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.model.TerminalSelection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A reflow moves the selection's ends; the drag's anchor follows the end it was. */
class AnchorAfterReflowTest {

    private static TerminalSelection selection(int x1, int y1, int x2, int y2) {
        return new TerminalSelection(new Point(x1, y1), new Point(x2, y2));
    }

    @Test
    void aForwardDragKeepsItsAnchorAtTheStart() {
        Point anchor = TerminalView.anchorAfterReflow(selection(3, -2, 9, 1), true, false);
        assertEquals(new Point(3, -2), anchor);
    }

    @Test
    void aBackwardDragKeepsItsAnchorJustBeforeTheEnd() {
        // SelectionGeometry.drag ends a backwards selection one past the anchor, so its cell is in.
        Point anchor = TerminalView.anchorAfterReflow(selection(0, 4, 7, 6), false, false);
        assertEquals(new Point(6, 6), anchor);
    }

    @Test
    void withNothingToFollowThereIsNoAnchor() {
        assertNull(TerminalView.anchorAfterReflow(null, true, false));
        // A block's rows have been rewrapped into other rows; it is dropped, and so is its anchor.
        assertNull(TerminalView.anchorAfterReflow(selection(1, 1, 4, 3), true, true));
    }
}
