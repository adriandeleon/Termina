package com.termina.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a dragged tab lands.
 *
 * <p>The drop position is expressed against the list as it looks during the drag, but the insert
 * happens against the list with the dragged tab already removed. Off by one there moves the tab one
 * place from where it was dropped — which reads as sloppiness rather than a bug, and is exactly the
 * kind of thing that survives review.
 */
class TabReorderTest {

    @ParameterizedTest(name = "drag {0} onto {1} (after={2}) of {3} -> {4}")
    @CsvSource({
        // Dragging rightwards: everything above the source shifts down when it is removed.
        "0, 1, false, 3, 0", // onto the left half of its neighbour is where it already is
        "0, 1, true,  3, 1", // onto the right half moves it past
        "0, 2, true,  3, 2", // to the very end
        "0, 2, false, 3, 1",
        // Dragging leftwards: no shift, because the source sits above the target.
        "2, 0, false, 3, 0",
        "2, 0, true,  3, 1",
        "2, 1, false, 3, 1",
        "2, 1, true,  3, 2", // back where it started
        // Middle of a longer strip.
        "2, 4, true,  6, 4",
        "4, 1, false, 6, 1",
    })
    void dropLandsWhereItWasDropped(int from, int over, boolean after, int count, int expected) {
        assertEquals(expected, TabReorder.insertIndex(from, over, after, count));
    }

    @Test
    void aDropOnItselfChangesNothing() {
        // Both halves of the tab being dragged resolve back to where it already is, so a click that
        // registers as a tiny drag does not shuffle the strip.
        assertEquals(1, TabReorder.insertIndex(1, 1, false, 3));
        assertEquals(1, TabReorder.insertIndex(1, 1, true, 3));
    }

    @Test
    void theResultIsAlwaysAValidIndex() {
        for (int count = 1; count <= 6; count++) {
            for (int from = 0; from < count; from++) {
                for (int over = 0; over < count; over++) {
                    for (boolean after : new boolean[] {false, true}) {
                        int index = TabReorder.insertIndex(from, over, after, count);
                        int size = count;
                        assertTrue(index >= 0 && index < size, () -> "out of range for count=" + size);
                    }
                }
            }
        }
    }

    @Test
    void nonsensicalInputIsLeftAlone() {
        // A drag that outlives the tab it started on must not throw or reshuffle at random.
        assertEquals(5, TabReorder.insertIndex(5, 0, false, 3));
        assertEquals(0, TabReorder.insertIndex(0, 9, false, 3));
        assertEquals(0, TabReorder.insertIndex(0, 0, true, 1));
    }

    @Test
    void onlyARealChangeCountsAsAMove() {
        assertFalse(TabReorder.isMove(2, 2));
        assertFalse(TabReorder.isMove(-1, 0));
        assertTrue(TabReorder.isMove(0, 1));
    }
}
