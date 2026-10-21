package com.termina.ui;

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.model.TerminalSelection;

/**
 * The one convention for where a selection ends: <b>exclusive</b>, in slots.
 *
 * <p>JediTerm disagrees with itself. {@code SelectionUtil.getSelectionText} takes
 * {@code substring(start.x, end.x)}, so the end column is not copied; {@code TerminalSelection
 * .intersect} highlights through the end column inclusive. Using both, a drag over {@code hello}
 * to its last cell highlighted five characters and copied four, and the word selection's {@code +1}
 * that made double-click copy correctly made it highlight one cell too many. Copy is the one that
 * leaves the program, so copy's convention wins: the end is the first slot <em>not</em> selected,
 * and the highlight is computed here rather than asked of {@code intersect}.
 *
 * <p>Pure, because every method here is an off-by-one waiting to happen, and the failure shows up
 * as a highlight that is nearly right.
 */
final class SelectionGeometry {

    private SelectionGeometry() {}

    /**
     * The slots a selection covers on one row, as {@code {from, to}} with {@code to} exclusive, or
     * null when it covers none of that row.
     *
     * @param rowSlots how many slots the row has, so a middle row of a multi-row selection is
     *     covered to its end
     */
    static int[] onRow(TerminalSelection selection, int row, int rowSlots) {
        Point start = selection.getStart();
        Point end = selection.getEnd();
        if (after(start, end)) {
            Point swap = start;
            start = end;
            end = swap;
        }
        if (row < start.y || row > end.y) return null;
        int from = row == start.y ? start.x : 0;
        int to = row == end.y ? end.x : rowSlots;
        return to > from ? new int[] {from, to} : null;
    }

    /**
     * The selection a drag describes.
     *
     * <p>The pointer's position is a <em>boundary</em> between cells, rounded from where it is: past
     * the middle of a cell counts that cell in. That is what every editor does, and it is what makes
     * a drag that stops on the last letter of a word take the letter. The anchor — the cell the
     * button went down in — is always included, whichever way the drag went, which is the part the
     * naive {@code (press, pointer)} pair gets wrong on a backwards drag.
     *
     * @param anchor the cell under the press, as a slot
     * @param row the row under the pointer now
     * @param boundary the slot boundary nearest the pointer
     */
    static TerminalSelection drag(Point anchor, int row, int boundary) {
        boolean forward = row > anchor.y || (row == anchor.y && boundary > anchor.x);
        return forward
                ? new TerminalSelection(new Point(anchor.x, anchor.y), new Point(boundary, row))
                : new TerminalSelection(new Point(boundary, row), new Point(anchor.x + 1, anchor.y));
    }

    /**
     * Whether the pointer has left the cell it pressed in. Until it has there is no selection: a
     * click, however shaky, must not leave a one-cell highlight behind.
     */
    static boolean leftPressCell(Point anchor, int row, int boundary) {
        return row != anchor.y || boundary < anchor.x || boundary > anchor.x + 1;
    }

    private static boolean after(Point a, Point b) {
        return a.y > b.y || (a.y == b.y && a.x > b.x);
    }
}
