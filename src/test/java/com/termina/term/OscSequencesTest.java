package com.termina.term;

import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three commands as shells and programs actually send them. */
class OscSequencesTest {

    @Test
    void readsEachPromptMark() {
        assertEquals(Optional.of(new OscSequences.PromptMark('A', null)), OscSequences.promptMark("A"));
        assertEquals(Optional.of(new OscSequences.PromptMark('C', null)), OscSequences.promptMark("C"));
        assertEquals(Optional.of(new OscSequences.PromptMark('D', 0)), OscSequences.promptMark("D;0"));
        assertEquals(Optional.of(new OscSequences.PromptMark('D', 130)), OscSequences.promptMark("D;130"));
    }

    @Test
    void ignoresTheOptionsIntegrationsAppend() {
        assertEquals(Optional.of(new OscSequences.PromptMark('A', null)), OscSequences.promptMark("A;aid=12"));
        assertEquals(Optional.of(new OscSequences.PromptMark('D', 2)), OscSequences.promptMark("D;2;aid=12"));
        // Fish sends D with no status after an empty command line.
        assertEquals(Optional.of(new OscSequences.PromptMark('D', null)), OscSequences.promptMark("D"));
        assertEquals(Optional.of(new OscSequences.PromptMark('D', null)), OscSequences.promptMark("D;aid=3"));
    }

    @Test
    void anUnknownMarkIsNothing() {
        assertTrue(OscSequences.promptMark("P;k=i").isEmpty());
        assertTrue(OscSequences.promptMark("Ax").isEmpty());
        assertTrue(OscSequences.promptMark("").isEmpty());
    }

    private static Optional<String> directory(String payload, Set<String> directories) {
        return OscSequences.localDirectory(payload, host -> host.equals("devbox"), directories::contains);
    }

    @Test
    void aDirectoryOnThisMachineIsTaken() {
        assertEquals(Optional.of("/home/me/src"), directory("file://devbox/home/me/src", Set.of("/home/me/src")));
        assertEquals(Optional.of("/tmp"), directory("file:///tmp", Set.of("/tmp")));
        assertEquals(Optional.of("/tmp"), directory("file://localhost/tmp", Set.of("/tmp")));
    }

    @Test
    void percentEscapesAreDecoded() {
        assertEquals(Optional.of("/tmp/a b"), directory("file:///tmp/a%20b", Set.of("/tmp/a b")));
    }

    @Test
    void anotherMachinesDirectoryIsNot() {
        // Over ssh the shell reports its own host. A matching path here would be a coincidence.
        assertTrue(directory("file://server/home/me", Set.of("/home/me")).isEmpty());
    }

    @Test
    void aDirectoryThatDoesNotExistIsNot() {
        assertTrue(directory("file:///nowhere", Set.of()).isEmpty());
        assertTrue(directory("not a uri", Set.of("not a uri")).isEmpty());
    }

    @Test
    void aWindowsPathLosesItsLeadingSlash() {
        assertEquals(Optional.of("C:/Users/me"), directory("file://devbox/C:/Users/me", Set.of("C:/Users/me")));
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
    }

    @Test
    void decodesTheClipboardText() {
        assertEquals(Optional.of("hello, wörld"), OscSequences.clipboardText("c;" + b64("hello, wörld")));
        // The selection letters are accepted and ignored: there is one clipboard.
        assertEquals(Optional.of("x"), OscSequences.clipboardText("p;" + b64("x")));
        assertEquals(Optional.of("x"), OscSequences.clipboardText(";" + b64("x")));
    }

    @Test
    void neverAnswersAQuery() {
        assertTrue(OscSequences.clipboardText("c;?").isEmpty());
    }

    @Test
    void rejectsWhatIsNotBase64OrNotText() {
        assertTrue(OscSequences.clipboardText("c;***").isEmpty());
        assertTrue(OscSequences.clipboardText(
                        "c;" + Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff, (byte) 0xfe}))
                .isEmpty());
        assertTrue(OscSequences.clipboardText("c;").isEmpty());
        assertTrue(OscSequences.clipboardText("no semicolon").isEmpty());
    }
}
