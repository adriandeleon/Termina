package com.termina.ui;

import com.jediterm.terminal.util.CharUtils;
import com.termina.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where the cursor is drawn — by column, from JediTerm's slot — and how, focused or not. */
class CursorDrawTest {

    /** A📁B📁C: five columns, seven slots. */
    private static final String ASTRAL =
            "A" + new String(Character.toChars(0x1F4C1)) + "B" + new String(Character.toChars(0x1F4C1)) + "C";

    @Test
    void plainTextPutsTheCursorAtItsSlot() {
        assertEquals(new CursorDraw.Cell(3, 3), CursorDraw.cell("abc", 3, 80));
        assertEquals(new CursorDraw.Cell(0, 0), CursorDraw.cell("", 0, 80));
    }

    @Test
    void eachAstralCharacterBeforeTheCursorIsOneColumnNotTwo() {
        // The review's run: printf 'A📁B📁C' leaves JediTerm's cursor at slot 7, which is column 5 —
        // drawn at 7 it sat two cells to the right of where the next character would land.
        assertEquals(7, ASTRAL.length());
        assertEquals(new CursorDraw.Cell(5, 7), CursorDraw.cell(ASTRAL, 7, 80));
        // On B, past one icon: column 2, slot 3. On the second icon: column 3, slot 4.
        assertEquals(new CursorDraw.Cell(2, 3), CursorDraw.cell(ASTRAL, 3, 80));
        assertEquals(new CursorDraw.Cell(3, 4), CursorDraw.cell(ASTRAL, 4, 80));
    }

    @Test
    void aWideCharacterIsTwoColumnsAndTwoSlots() {
        String cjk = "日" + CharUtils.DWC + "x";
        assertEquals(new CursorDraw.Cell(2, 2), CursorDraw.cell(cjk, 2, 80));
    }

    @Test
    void aCursorPastAFullLineIsDrawnOnTheLastColumn() {
        // Written to exactly the width, the cursor waits at the width for the wrap: xterm draws it
        // on the last column, and the glyph is the one in that column.
        assertEquals(new CursorDraw.Cell(4, 4), CursorDraw.cell("abcde", 5, 5));
        // Five columns of A📁B📁C fill a five-column grid with the cursor at slot 7: last column, C.
        assertEquals(new CursorDraw.Cell(4, 6), CursorDraw.cell(ASTRAL, 7, 5));
    }

    @Test
    void aCursorPastTheTextExtrapolatesOneToOne() {
        assertEquals(new CursorDraw.Cell(10, 10), CursorDraw.cell("ab", 10, 80));
        assertEquals(new CursorDraw.Cell(6, 8), CursorDraw.cell(ASTRAL, 8, 80));
    }

    @Test
    void anUnfocusedBlockIsHollow() {
        assertEquals(CursorDraw.Look.BLOCK, CursorDraw.look(Settings.CursorShape.BLOCK, true));
        assertEquals(CursorDraw.Look.HOLLOW_BLOCK, CursorDraw.look(Settings.CursorShape.BLOCK, false));
    }

    @Test
    void anUnfocusedBarOrUnderlineKeepsItsShapeAndDims() {
        // A program's bar means insert mode; it stays a bar, only quieter.
        assertEquals(CursorDraw.Look.BAR, CursorDraw.look(Settings.CursorShape.BAR, true));
        assertEquals(CursorDraw.Look.DIM_BAR, CursorDraw.look(Settings.CursorShape.BAR, false));
        assertEquals(CursorDraw.Look.UNDERLINE, CursorDraw.look(Settings.CursorShape.UNDERLINE, true));
        assertEquals(CursorDraw.Look.DIM_UNDERLINE, CursorDraw.look(Settings.CursorShape.UNDERLINE, false));
    }
}
