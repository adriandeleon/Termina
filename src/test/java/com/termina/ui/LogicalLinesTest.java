package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A position described so that a reflow cannot change it, and placed again afterwards. */
class LogicalLinesTest {

    @Test
    void aWrappedRowAndItsContinuationAreOneLine() {
        // Rows -2..1: "ab" wrapped onto the next row, then two plain rows.
        LogicalLines lines = LogicalLines.of(-2, new boolean[] {true, false, false, false}, 10);
        assertEquals(3, lines.lineCount());
        assertArrayEquals(new int[] {0, 13}, lines.toLogical(-1, 3));
        assertArrayEquals(new int[] {2, 4}, lines.toLogical(1, 4));
        assertNull(lines.toLogical(2, 0));
    }

    @Test
    void survivesAReflowToAWiderWindow() {
        // At width 10 the first line took two rows; at width 20 it takes one, and the rest move up.
        LogicalLines narrow = LogicalLines.of(0, new boolean[] {true, false, false}, 10);
        LogicalLines wide = LogicalLines.of(0, new boolean[] {false, false}, 20);
        int[] logical = narrow.toLogical(1, 2);
        assertArrayEquals(new int[] {0, 12}, wide.fromLogical(logical[0], logical[1]));
        int[] second = narrow.toLogical(2, 0);
        assertArrayEquals(new int[] {1, 0}, wide.fromLogical(second[0], second[1]));
    }

    @Test
    void survivesAReflowToANarrowerWindow() {
        LogicalLines wide = LogicalLines.of(0, new boolean[] {false, false}, 20);
        LogicalLines narrow = LogicalLines.of(-1, new boolean[] {true, false, false}, 10);
        int[] logical = wide.toLogical(0, 15);
        assertArrayEquals(new int[] {0, 5}, narrow.fromLogical(logical[0], logical[1]));
    }

    @Test
    void aPositionPastTheEndOfItsLineStaysOnIt() {
        LogicalLines lines = LogicalLines.of(0, new boolean[] {false, false}, 10);
        assertArrayEquals(new int[] {0, 10}, lines.fromLogical(0, 25));
        assertNull(lines.fromLogical(5, 0));
    }
}
