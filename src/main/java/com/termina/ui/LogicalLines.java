package com.termina.ui;

/**
 * Rows grouped into the lines they were written as — a row and the rows a soft wrap carried it
 * onto — so a position can be described in a way a reflow does not change.
 *
 * <p>A resize to a different width rebuilds every row: a line that took two rows at 80 columns
 * takes one at 160, and every row number below it moves. What survives is the line itself and how
 * far into it a position was. So a position is translated to (line, cells in) before the resize
 * and back to (row, column) after, against the new width. This is what JediTerm does internally for
 * the cursor and the selection, and there is no hook to have it do the same for anything else.
 *
 * <p>Counted from the top of the scrollback. A reflow that pushes lines out of a full scrollback
 * shifts every index by the lines it dropped, which is the one case this gets wrong — and only by
 * those lines, at the far end of the history.
 */
final class LogicalLines {

    private final int firstRow;
    private final int width;
    /** Per row: the index of the logical line it belongs to. */
    private final int[] lineOfRow;
    /** Per logical line: its first and last row, relative to {@link #firstRow}. */
    private final int[] firstRowOf;

    private final int[] lastRowOf;

    private LogicalLines(int firstRow, int width, int[] lineOfRow, int[] firstRowOf, int[] lastRowOf) {
        this.firstRow = firstRow;
        this.width = width;
        this.lineOfRow = lineOfRow;
        this.firstRowOf = firstRowOf;
        this.lastRowOf = lastRowOf;
    }

    /**
     * @param firstRow the buffer row of {@code wrapped[0]}, negative for history
     * @param wrapped per row, whether it continues onto the next
     * @param width the columns a wrapped row fills
     */
    static LogicalLines of(int firstRow, boolean[] wrapped, int width) {
        int[] lineOfRow = new int[wrapped.length];
        int[] first = new int[wrapped.length];
        int[] last = new int[wrapped.length];
        int line = -1;
        for (int row = 0; row < wrapped.length; row++) {
            if (row == 0 || !wrapped[row - 1]) {
                line++;
                first[line] = row;
            }
            lineOfRow[row] = line;
            last[line] = row;
        }
        int count = line + 1;
        return new LogicalLines(
                firstRow,
                Math.max(1, width),
                lineOfRow,
                java.util.Arrays.copyOf(first, count),
                java.util.Arrays.copyOf(last, count));
    }

    /** {line, cells into it} for a buffer position, or null when the row is not in the buffer. */
    int[] toLogical(int row, int column) {
        int r = row - firstRow;
        if (r < 0 || r >= lineOfRow.length) return null;
        int line = lineOfRow[r];
        return new int[] {line, (r - firstRowOf[line]) * width + Math.max(0, column)};
    }

    /**
     * {row, column} for a logical position, or null when there is no such line. A position past
     * the end of its line — the line got shorter, or the offset was the cursor parked past its last
     * character — is kept on the line's last row rather than spilling onto the next line.
     */
    int[] fromLogical(int line, int offset) {
        if (line < 0 || line >= firstRowOf.length) return null;
        int rowsIn = offset / width;
        int r = Math.min(firstRowOf[line] + rowsIn, lastRowOf[line]);
        int column = r == firstRowOf[line] + rowsIn ? offset % width : width;
        return new int[] {firstRow + r, column};
    }

    int lineCount() {
        return firstRowOf.length;
    }
}
