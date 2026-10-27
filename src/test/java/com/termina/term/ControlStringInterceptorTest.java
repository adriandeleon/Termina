package com.termina.term;

import java.io.IOException;

import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.TerminalDataStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which strings are discarded, where each one ends, and that nothing else is changed. */
class ControlStringInterceptorTest {

    /** Everything the emulator would read, until the stream runs dry. */
    private static String readAll(String input) throws IOException {
        TerminalDataStream stream = new ControlStringInterceptor(new ArrayTerminalDataStream(input.toCharArray()));
        StringBuilder out = new StringBuilder();
        try {
            while (true) out.append(stream.getChar());
        } catch (TerminalDataStream.EOF end) {
            return out.toString();
        }
    }

    @Test
    void dropsAKittyGraphicsCommand() throws IOException {
        assertEquals("ab", readAll("a\u001b_Gf=100,a=T;iVBORw0KGgo=\u001b\\b"));
    }

    @Test
    void dropsStartOfStringAndPrivacyMessage() throws IOException {
        assertEquals("abc", readAll("a\u001bXsos\u001b\\b\u001b^pm\u001b\\c"));
    }

    @Test
    void acceptsTheSingleByteStringTerminator() throws IOException {
        assertEquals("ab", readAll("a\u001b_x\u009cb"));
    }

    @Test
    void cancelAndSubstituteAbandonTheString() throws IOException {
        assertEquals("ab", readAll("a\u001b_x\u0018b"));
        assertEquals("ab", readAll("a\u001b_x\u001ab"));
    }

    @Test
    void anEscapeThatIsNotTheTerminatorIsLeftForTheEmulator() throws IOException {
        // An APC a program never finished: the colour that follows still arrives.
        assertEquals("a\u001b[31mb", readAll("a\u001b_never ended\u001b[31mb"));
    }

    @Test
    void backToBackStringsAreAllDropped() throws IOException {
        assertEquals("x", readAll("\u001b_1\u001b\\\u001b_2\u001b\\x"));
    }

    @Test
    void leavesEveryOtherSequenceForTheEmulator() throws IOException {
        String others = "\u001b]0;title\u0007\u001bPq#0\u001b\\\u001b[31mred\u001b7\u001b8\u001bc";
        assertEquals(others, readAll(others));
    }
}
