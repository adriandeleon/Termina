package com.termina.term;

import java.io.IOException;

import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.TerminalDataStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How each colon form is respelled, and that every other sequence is left exactly as it was. */
class SgrColonInterceptorTest {

    /** Everything the emulator would read, until the stream runs dry. */
    private static String readAll(String input) throws IOException {
        TerminalDataStream stream = new SgrColonInterceptor(new ArrayTerminalDataStream(input.toCharArray()));
        StringBuilder out = new StringBuilder();
        try {
            while (true) out.append(stream.getChar());
        } catch (TerminalDataStream.EOF end) {
            return out.toString();
        }
    }

    @Test
    void trueColourWithAnEmptyColourSpace() {
        assertEquals("38;2;255;0;10", SgrColonInterceptor.rewrite("38:2::255:0:10"));
        assertEquals("48;2;1;2;3", SgrColonInterceptor.rewrite("48:2::1:2:3"));
    }

    @Test
    void trueColourDropsAWrittenColourSpace() {
        assertEquals("38;2;1;2;3", SgrColonInterceptor.rewrite("38:2:0:1:2:3"));
    }

    @Test
    void trueColourWithoutTheColourSpaceSlot() {
        assertEquals("38;2;1;2;3", SgrColonInterceptor.rewrite("38:2:1:2:3"));
    }

    @Test
    void emptyComponentsAreZero() {
        assertEquals("38;2;0;0;0", SgrColonInterceptor.rewrite("38:2::::"));
    }

    @Test
    void paletteColour() {
        assertEquals("38;5;196", SgrColonInterceptor.rewrite("38:5:196"));
        assertEquals("48;5;0", SgrColonInterceptor.rewrite("48:5:"));
    }

    @Test
    void underlineStylesBecomeTheOneUnderline() {
        assertEquals("24", SgrColonInterceptor.rewrite("4:0"));
        assertEquals("4", SgrColonInterceptor.rewrite("4:1"));
        assertEquals("4", SgrColonInterceptor.rewrite("4:3"));
        assertEquals("4", SgrColonInterceptor.rewrite("4:5"));
    }

    @Test
    void whatCannotBeDrawnIsDropped() {
        // Underline colour: with semicolons, 58;2;… would read as faint and the rest as attributes.
        assertEquals("1", SgrColonInterceptor.rewrite("1;58:2::255:0:0"));
        assertEquals("", SgrColonInterceptor.rewrite("58:5:9"));
        // Malformed: a colour with too few values.
        assertEquals("1", SgrColonInterceptor.rewrite("38:2:1:2;1"));
    }

    @Test
    void parametersWithoutColonsPassAsWritten() {
        // An empty parameter is a reset, and must stay one.
        assertEquals(";1;38;2;9;9;9;4", SgrColonInterceptor.rewrite(";1;38:2::9:9:9;4:2"));
    }

    @Test
    void respellsInTheStream() throws IOException {
        assertEquals("a\u001b[1;38;2;255;0;0mX", readAll("a\u001b[1;38:2::255:0:0mX"));
    }

    @Test
    void aSequenceWithNothingLeftIsTakenOutWhole() throws IOException {
        // Sending CSI m in its place would reset every attribute the program had set.
        assertEquals("\u001b[1mab", readAll("\u001b[1ma\u001b[58:5:9mb"));
    }

    @Test
    void leavesEveryOtherSequenceForTheEmulator() throws IOException {
        String others = "\u001b[31m\u001b[38;2;1;2;3m\u001b[>4;2m\u001b[?2026h\u001b[2 q\u001b[1:2H"
                + "\u001b]0;a:b\u0007\u001b7plain: text";
        assertEquals(others, readAll(others));
    }

    @Test
    void anOverlongRunIsPutBackUntouched() throws IOException {
        String overlong = "\u001b[" + "1;".repeat(200) + "4:3m";
        assertEquals(overlong, readAll(overlong));
    }
}
