package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** PERF-09 (Pauta 6.0.1): text a frame has drawn before is not copied again to draw it. */
class RunTextTest {

    @Test
    void theSameCharactersGiveBackTheSameString() {
        RunText text = new RunText();
        String first = text.of(new StringBuilder("drwxr-xr-x"));
        assertEquals("drwxr-xr-x", first);
        // Other characters holding the same text, as the next frame's view onto the line is.
        assertSame(first, text.of(new StringBuilder("drwxr-xr-x")), "nothing new for text seen before");
        assertSame(first, text.of(java.nio.CharBuffer.wrap("drwxr-xr-x".toCharArray())));
    }

    @Test
    void differentTextIsNeverMistakenForWhatIsKept() {
        RunText text = new RunText();
        // Every pair here shares a slot or does not; either way each comes back as itself.
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 3 * RunText.SLOTS; i++) {
                String run = "row-" + i;
                assertEquals(run, text.of(new StringBuilder(run)));
            }
        }
        assertEquals("", text.of(""));
        assertEquals("a", text.of(new StringBuilder("a")));
        assertEquals("b", text.of(new StringBuilder("b")));
    }

    @Test
    void aCodePointIsMadeAStringOnce() {
        RunText text = new RunText();
        String wide = text.of(0x65E5); // 日
        assertEquals("日", wide);
        assertSame(wide, text.of(0x65E5));
        String icon = text.of(0xF0219); // a Nerd Font Material icon: two chars, one code point
        assertEquals(2, icon.length());
        assertEquals(0xF0219, icon.codePointAt(0));
        assertSame(icon, text.of(0xF0219));
        assertNotSame(wide, text.of(0x672C));
        assertEquals("\u0000", text.of(0));
        assertEquals(Character.toString(0x10FFFF), text.of(0x10FFFF));
    }
}
