package com.termina.ui;

import com.termina.config.Settings;

/**
 * Where the cursor is drawn, and how.
 *
 * <p><b>Where.</b> JediTerm's cursor x is a <em>slot</em> — an index into the line's {@code char[]}
 * — not a column. The two part at every astral character to the left of the cursor (an emoji, a
 * Nerd Font Material icon from U+F0000 up), which is two slots and one column. Drawing the slot as
 * a column put the cursor one cell further right per such character, detached from where typing
 * lands; a prompt with an icon in it showed that on every line. The slot is still what the glyph
 * under a block cursor is read from.
 *
 * <p><b>How.</b> A cursor drawn the same in every pane cannot say which one takes the keys. A pane
 * that is not focused, or is in a window that is not, draws its block hollow — the convention of
 * every mainstream terminal, and the strongest focus cue a terminal has. A bar or an underline has
 * no inside to hollow out, and outlining a two-pixel stroke draws the same stroke again; those are
 * kept in the shape a program asked for (vim's insert-mode bar still says insert mode) and dimmed.
 */
final class CursorDraw {

    private CursorDraw() {}

    /** How opaque an unfocused bar or underline is drawn. */
    static final double UNFOCUSED_ALPHA = 0.5;

    /** The cell the cursor is drawn in: its column on the grid, and the slot its glyph is read from. */
    record Cell(int column, int slot) {}

    /**
     * The cell for a cursor at {@code slot} on a line holding {@code line}.
     *
     * <p>After a line is written to exactly its full width the cursor is <em>at</em> the width,
     * pending the wrap the next character will cause. xterm draws it on the last column, and so does
     * this — with the slot moved back to the character in that column.
     *
     * @param columns the grid's width
     */
    static Cell cell(String line, int slot, int columns) {
        int at = Math.max(0, slot);
        int column = ColumnMap.columnAt(line, at);
        int last = Math.max(0, columns - 1);
        if (column > last) return new Cell(last, ColumnMap.slotAt(line, last));
        return new Cell(column, at);
    }

    /** A cursor as drawn: the shape, and whether it is the focused pane's. */
    enum Look {
        BLOCK,
        HOLLOW_BLOCK,
        UNDERLINE,
        DIM_UNDERLINE,
        BAR,
        DIM_BAR
    }

    /** @param focused whether this pane has the keyboard and its window is the active one */
    static Look look(Settings.CursorShape shape, boolean focused) {
        return switch (shape) {
            case UNDERLINE -> focused ? Look.UNDERLINE : Look.DIM_UNDERLINE;
            case BAR -> focused ? Look.BAR : Look.DIM_BAR;
            default -> focused ? Look.BLOCK : Look.HOLLOW_BLOCK;
        };
    }
}
