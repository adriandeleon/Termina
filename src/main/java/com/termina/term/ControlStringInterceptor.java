package com.termina.term;

import java.io.IOException;

import com.jediterm.terminal.TerminalDataStream;

/**
 * Takes the three control strings JediTerm does not know — APC ({@code ESC _}), SOS ({@code ESC X})
 * and PM ({@code ESC ^}) — out of the stream, and discards them.
 *
 * <p>JediTerm reads the introducer, gives up on it, and prints the body as text. APC is the one that
 * matters: it carries the kitty graphics protocol, so an image arrived as a line of base64. xterm
 * does nothing with any of the three, and nothing is what this does too.
 *
 * <p>A string ends at ST ({@code ESC \} or the single-byte {@code 0x9C}), and is abandoned at CAN or
 * SUB, which are consumed with it. An ESC that is not ST also ends it and is left for the emulator,
 * so a string a program never terminated costs only the output up to the next escape sequence —
 * a prompt's colours, or {@code reset} — rather than everything after it.
 */
public final class ControlStringInterceptor implements TerminalDataStream {

    private static final char ESC = 0x1B;
    private static final char ST = 0x9C;
    private static final char CAN = 0x18;
    private static final char SUB = 0x1A;

    private final TerminalDataStream in;

    public ControlStringInterceptor(TerminalDataStream in) {
        this.in = in;
    }

    /** Whether {@code c}, after ESC, opens a string this discards. */
    static boolean opensString(char c) {
        return c == '_' || c == 'X' || c == '^';
    }

    @Override
    public char getChar() throws IOException {
        while (true) {
            char c = in.getChar();
            if (c != ESC) return c;
            char next = in.getChar();
            if (!opensString(next)) {
                in.pushChar(next);
                return c;
            }
            skipString();
            // Taken: the emulator is owed whatever follows it.
        }
    }

    private void skipString() throws IOException {
        while (true) {
            char c = in.getChar();
            if (c == ST || c == CAN || c == SUB) return;
            if (c == ESC) {
                char next = in.getChar();
                if (next != '\\') {
                    in.pushChar(next);
                    in.pushChar(ESC);
                }
                return;
            }
        }
    }

    @Override
    public void pushChar(char c) throws IOException {
        in.pushChar(c);
    }

    @Override
    public String readNonControlCharacters(int maxChars) throws IOException {
        return in.readNonControlCharacters(maxChars);
    }

    @Override
    public void pushBackBuffer(char[] bytes, int length) throws IOException {
        in.pushBackBuffer(bytes, length);
    }

    @Override
    public boolean isEmpty() {
        return in.isEmpty();
    }
}
