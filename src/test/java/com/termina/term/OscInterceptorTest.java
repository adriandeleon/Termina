package com.termina.term;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.TerminalDataStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which sequences the interceptor takes, and that it leaves everything else exactly as it was. */
class OscInterceptorTest {

    private final List<String> taken = new ArrayList<>();

    /** Everything the emulator would read, until the stream runs dry. */
    private String readAll(String input) throws IOException {
        TerminalDataStream stream = new OscInterceptor(
                new ArrayTerminalDataStream(input.toCharArray()),
                OscSequences::handled,
                (code, payload) -> taken.add(code + ":" + payload));
        StringBuilder out = new StringBuilder();
        try {
            while (true) out.append(stream.getChar());
        } catch (TerminalDataStream.EOF end) {
            return out.toString();
        }
    }

    @Test
    void takesAPromptMarkOutOfTheStream() throws IOException {
        assertEquals("$ ls", readAll("\u001b]133;A\u0007$ ls"));
        assertEquals(List.of("133:A"), taken);
    }

    @Test
    void acceptsStringTerminatorAsWellAsBell() throws IOException {
        assertEquals("ab", readAll("a\u001b]133;D;1\u001b\\b"));
        assertEquals(List.of("133:D;1"), taken);
    }

    @Test
    void leavesEveryOtherSequenceForTheEmulator() throws IOException {
        String title = "\u001b]0;my title\u0007";
        String link = "\u001b]8;;https://example.com\u0007x\u001b]8;;\u0007";
        String csi = "\u001b[31mred";
        assertEquals(title + link + csi, readAll(title + link + csi));
        assertEquals(List.of(), taken);
    }

    @Test
    void aCodeThatOnlyStartsWithOursIsNotOurs() throws IOException {
        // 1337 begins with the digits of no code we take, and 77 is not 7.
        assertEquals("\u001b]1337;x\u0007\u001b]77;y\u0007", readAll("\u001b]1337;x\u0007\u001b]77;y\u0007"));
        assertEquals(List.of(), taken);
    }

    @Test
    void takesConsecutiveSequencesAndKeepsTheTextBetween() throws IOException {
        assertEquals("out", readAll("\u001b]133;C\u0007out\u001b]133;D;0\u0007\u001b]7;file:///tmp\u0007"));
        assertEquals(List.of("133:C", "133:D;0", "7:file:///tmp"), taken);
    }

    @Test
    void anEscapeInsideAPayloadEndsItAndStartsTheNextSequence() throws IOException {
        // Malformed, but the CSI after it must still reach the emulator intact.
        assertEquals("\u001b[1mbold", readAll("\u001b]133;A\u001b[1mbold"));
        assertEquals(List.of("133:A"), taken);
    }

    @Test
    void anOverlongPayloadIsReadToItsEndAndDropped() throws IOException {
        String huge = "x".repeat(OscInterceptor.MAX_PAYLOAD + 10);
        assertEquals("after", readAll("\u001b]52;c;" + huge + "\u0007after"));
        assertEquals(List.of(), taken);
    }
}
