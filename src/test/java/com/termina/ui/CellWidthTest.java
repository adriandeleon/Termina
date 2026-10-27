package com.termina.ui;

import com.jediterm.terminal.util.CharUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Buffer text measured in columns rather than in array slots. */
class CellWidthTest {

    private static final String DWC = String.valueOf(CharUtils.DWC);

    @Test
    void asciiIsOneCellPerCharacter() {
        assertEquals(5, CellWidth.cells("hello"));
        assertEquals(0, CellWidth.cells(""));
    }

    @Test
    void aWideCharacterIsTwoCellsAcrossTwoSlots() {
        // JediTerm stores the glyph, then a placeholder for the column it spills into.
        assertEquals(2, CellWidth.cells("日" + DWC));
        assertEquals(4, CellWidth.cells("日" + DWC + "本" + DWC));
    }

    @Test
    void anAstralCharacterIsOneCellAcrossTwoSlots() {
        // The case that broke alignment: a Nerd Font icon from the supplementary private-use area
        // is a surrogate pair, so String.length() says 2 while the terminal gave it one column.
        String icon = new String(Character.toChars(0xF0219));
        assertEquals(2, icon.length());
        assertEquals(1, CellWidth.cells(icon));
        assertEquals(3, CellWidth.cells(icon + ".."));
    }

    @Test
    void aWideAstralCharacterIsTwoCells() {
        // An emoji: a surrogate pair *and* a placeholder.
        String emoji = new String(Character.toChars(0x1F600));
        assertEquals(2, CellWidth.cells(emoji + DWC));
    }

    // --- the fast-path classifier -------------------------------------------------------------

    @Test
    void narrowBmpTextStaysOnTheFastPath() {
        // Every TUI border, bar and accent. An ASCII-only test sent all of these per-glyph.
        assertTrue(CellWidth.isOneCellPerChar("hello"));
        assertTrue(CellWidth.isOneCellPerChar("┌──┐")); // box drawing
        assertTrue(CellWidth.isOneCellPerChar("█▓▒░")); // block elements
        assertTrue(CellWidth.isOneCellPerChar("⣿⣷⣧")); // braille
        assertTrue(CellWidth.isOneCellPerChar("café naïve"));
        assertTrue(CellWidth.isOneCellPerChar(""));
    }

    @Test
    void astralAndWideCellsNeedPerGlyphDrawing() {
        assertFalse(CellWidth.isOneCellPerChar(new String(Character.toChars(0xF0219))));
        assertFalse(CellWidth.isOneCellPerChar("日" + DWC));
        assertFalse(CellWidth.isOneCellPerChar("ab" + new String(Character.toChars(0x1F600)) + DWC));
    }

    @Test
    void controlCharactersAreNotBatchedEither() {
        assertFalse(CellWidth.isOneCellPerChar("a\u0000b"));
        assertFalse(CellWidth.isOneCellPerChar("a\u001bb"));
    }

    @Test
    void aCharacterDrawnWithNoAdvanceIsNotBatched() {
        // Each has a cell in the buffer and none in one fillText, so everything after it in the run
        // was drawn a column to the left.
        assertFalse(CellWidth.isOneCellPerChar("éx")); // combining acute, no NFC form used
        assertFalse(CellWidth.isOneCellPerChar("q̇x")); // combining dot above (Mn)
        assertFalse(CellWidth.isOneCellPerChar("a⃝b")); // enclosing circle (Me)
        assertFalse(CellWidth.isOneCellPerChar("⚠️ ok")); // ⚠️: VS16
        assertFalse(CellWidth.isOneCellPerChar("a‍b")); // zero-width joiner
        assertFalse(CellWidth.isOneCellPerChar("a​b")); // zero-width space
        assertFalse(CellWidth.isOneCellPerChar("a⁠b")); // word joiner
        assertFalse(CellWidth.isOneCellPerChar("a­b")); // soft hyphen (Cf)
        assertFalse(CellWidth.isOneCellPerChar("a︀b")); // variation selector 1
    }

    @Test
    void theZeroAdvanceTestLeavesOrdinaryTextAlone() {
        assertTrue(CellWidth.isOneCellPerChar("é ñ ü ─│┼ ⚠ ★"));
        assertFalse(CellWidth.isZeroAdvance('a'));
        assertFalse(CellWidth.isZeroAdvance('─'));
        assertTrue(CellWidth.isZeroAdvance('́'));
        assertTrue(CellWidth.isZeroAdvance('⁯'));
    }
}
