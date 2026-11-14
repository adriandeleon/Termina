package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a paste is asked about. */
class PasteSafetyTest {

    @Test
    void aNewlineAnywhereNeedsConfirmation() {
        // Including a trailing one: "rm -rf x\n" runs the moment it lands.
        assertTrue(PasteSafety.needsConfirmation("ls\n"));
        assertTrue(PasteSafety.needsConfirmation("a\nb"));
        assertTrue(PasteSafety.needsConfirmation("a\r\nb"));
        assertTrue(PasteSafety.needsConfirmation("a\rb"));
    }

    @Test
    void aSingleLineDoesNot() {
        assertFalse(PasteSafety.needsConfirmation("ls -la"));
        assertFalse(PasteSafety.needsConfirmation("a\tb"));
        assertFalse(PasteSafety.needsConfirmation(""));
        assertFalse(PasteSafety.needsConfirmation(null));
    }

    @Test
    void sizeIsJudgedAgainstNamedThresholdsAndTheFreeHeap() {
        // PERF-12 (Pauta 2.0.2).
        long plenty = Long.MAX_VALUE;
        assertEquals(PasteSafety.Size.NORMAL, PasteSafety.bySize(PasteSafety.LARGE_CHARS, plenty));
        assertEquals(PasteSafety.Size.LARGE, PasteSafety.bySize(PasteSafety.LARGE_CHARS + 1, plenty));
        assertEquals(PasteSafety.Size.LARGE, PasteSafety.bySize(PasteSafety.MAX_CHARS, plenty));
        assertEquals(PasteSafety.Size.REFUSED, PasteSafety.bySize(PasteSafety.MAX_CHARS + 1, plenty));
        // What will not fit the heap on its way out is refused, however small the threshold says.
        long tight = 1000L * PasteSafety.BYTES_IN_FLIGHT_PER_CHAR;
        assertEquals(PasteSafety.Size.NORMAL, PasteSafety.bySize(1000, tight));
        assertEquals(PasteSafety.Size.REFUSED, PasteSafety.bySize(1001, tight));
    }

    @Test
    void theReviewShowsOnlyTheStartOfALargePaste() {
        String small = "a\nb";
        assertEquals(small, PasteSafety.preview(small));
        String large = "x".repeat(PasteSafety.PREVIEW_CHARS + 10);
        String shown = PasteSafety.preview(large);
        assertEquals(PasteSafety.PREVIEW_CHARS + 2, shown.length());
        assertTrue(shown.endsWith("\n…"));
        // Never half a surrogate pair at the cut: it moves back before the emoji.
        String emoji = "x".repeat(PasteSafety.PREVIEW_CHARS - 1) + "😀" + "tail";
        assertTrue(PasteSafety.preview(emoji).startsWith("x".repeat(PasteSafety.PREVIEW_CHARS - 1) + "\n"));
    }
}
