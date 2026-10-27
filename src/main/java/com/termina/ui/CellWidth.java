package com.termina.ui;

import com.jediterm.terminal.util.CharUtils;

/**
 * How many terminal cells a stretch of buffer text occupies.
 *
 * <p>Not its length. The buffer is a {@code char[]}, so a character outside the Basic Multilingual
 * Plane — an emoji, or one of the Nerd Font icons a modern {@code ls} puts before every filename —
 * takes two array slots while occupying one column. A double-width character is the opposite case:
 * one slot for the glyph, then a {@link CharUtils#DWC} placeholder for the column it spills into.
 *
 * <p>Confusing the two shifts everything after the character by a column, which is what makes a
 * directory listing's columns fail to line up while each line looks individually fine.
 */
final class CellWidth {

    private CellWidth() {}

    /**
     * Whether every char of {@code text} is one narrow cell, so the run can be drawn in one call.
     *
     * <p>This is the fast-path test for the renderer, and its scope is deliberate. Only two things
     * break the one-char-one-column assumption a single {@code fillText} relies on: an astral code
     * point, which is two chars for one column, and a wide glyph's {@link CharUtils#DWC} filler,
     * which is one char for a column that must not be drawn. Everything else in the BMP — box
     * drawing, block elements, braille, accented letters — is a cell apiece and stays batched. The
     * first version dropped every non-ASCII run to per-glyph drawing, which put every TUI border
     * and progress bar on the slow path for no gain. Control characters are excluded too, since a
     * run containing one has nothing sensible to draw in a batch.
     *
     * <p>A third thing breaks it: a code point the face draws with no advance — a combining mark
     * with no precomposed form, a zero-width joiner, the variation selector in {@code ⚠️}. The buffer
     * gives it a cell like any other character, and one {@code fillText} gives it none, so every
     * character after it in the run was drawn a cell to the left of its column. See {@link
     * #isZeroAdvance}.
     */
    static boolean isOneCellPerChar(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7f) continue; // ASCII: the common case, settled in one test
            if (c < 0x20 || c == 0x7f || c == CharUtils.DWC || Character.isSurrogate(c) || isZeroAdvance(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a BMP character is drawn with no advance of its own: non-spacing and enclosing marks
     * (Mn, Me), format characters (Cf — the soft hyphen, bidi controls), the variation selectors
     * U+FE00–FE0F, and the zero-width and invisible-operator blocks U+200B–200F and U+2060–206F.
     */
    static boolean isZeroAdvance(char c) {
        if (c >= 0xFE00 && c <= 0xFE0F) return true;
        if (c >= 0x200B && c <= 0x200F) return true;
        if (c >= 0x2060 && c <= 0x206F) return true;
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.FORMAT;
    }

    /**
     * Cells occupied by {@code text}, counting from the start of a style run.
     *
     * <p>A {@link CharSequence} so the renderer can pass JediTerm's {@code CharBuffer} as it is —
     * this runs for every run of every frame, and a {@code toString} here was a copy of the run
     * made only to be counted.
     */
    static int cells(CharSequence text) {
        if (text == null || text.isEmpty()) return 0;
        int cells = 0;
        int i = 0;
        int length = text.length();
        while (i < length) {
            if (text.charAt(i) == CharUtils.DWC) {
                // The second half of a wide glyph: a column of its own, nothing drawn in it.
                cells++;
                i++;
                continue;
            }
            cells++;
            i += Character.charCount(Character.codePointAt(text, i));
        }
        return cells;
    }
}
