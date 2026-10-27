package com.termina.term;

import java.io.IOException;
import java.util.function.IntPredicate;

import com.jediterm.terminal.TerminalDataStream;
import org.jspecify.annotations.Nullable;

/**
 * Takes the operating-system commands JediTerm does not handle out of the stream before it sees
 * them, and hands each to a listener.
 *
 * <p>A wrapper around the stream the emulator reads from, rather than a filter on the bytes the
 * PTY delivers. The emulator pulls one character at a time and acts on it before pulling the next,
 * so when this sees {@code ESC ]} everything before it is already on the screen — and a prompt mark
 * (OSC 133) recorded at the cursor lands on the prompt's own row. Watching the PTY instead would
 * see a whole read at once, and every mark in it would be recorded a chunk early.
 *
 * <p>Only the codes asked for are taken; anything else is pushed back untouched, so JediTerm still
 * sees its titles, its OSC 8 links and the colour queries it answers. A sequence is ended by BEL,
 * by ST ({@code ESC \}) or by the single-byte ST, and one that runs past {@link #MAX_PAYLOAD} is read
 * to its end and dropped rather than buffered without limit: OSC 52 carries a whole clipboard,
 * and a program that never terminates one must not be able to grow this forever.
 */
public final class OscInterceptor implements TerminalDataStream {

    /** One operating-system command: its number, and everything after the first semicolon. */
    public interface Listener {
        void osc(int code, String payload);
    }

    /** Characters of payload kept, about a megabyte — a clipboard of three quarters of that. */
    static final int MAX_PAYLOAD = 1 << 20;

    private static final char ESC = 0x1B;
    private static final char BEL = 0x07;
    private static final char ST = 0x9C;

    /** OSC numbers are short; a run of digits longer than this is not one of ours. */
    private static final int MAX_CODE_DIGITS = 6;

    private final TerminalDataStream in;
    private final IntPredicate wanted;
    private final Listener listener;

    public OscInterceptor(TerminalDataStream in, IntPredicate wanted, Listener listener) {
        this.in = in;
        this.wanted = wanted;
        this.listener = listener;
    }

    @Override
    public char getChar() throws IOException {
        while (true) {
            char c = in.getChar();
            if (c != ESC) return c;
            char next = in.getChar();
            if (next != ']') {
                in.pushChar(next);
                return c;
            }
            if (!takeOsc()) return c;
            // Taken: the sequence is gone from the stream, and the emulator is owed the character
            // after it — which may itself start another one.
        }
    }

    /**
     * Having read {@code ESC ]}, takes the sequence if it is one of ours. Otherwise puts back what
     * it read, so the emulator meets {@code ] 0 ; title…} after the ESC it is about to be given.
     */
    private boolean takeOsc() throws IOException {
        StringBuilder digits = new StringBuilder();
        char c = in.getChar();
        while (c >= '0' && c <= '9' && digits.length() < MAX_CODE_DIGITS) {
            digits.append(c);
            c = in.getChar();
        }
        boolean ours = c == ';' && !digits.isEmpty() && wanted.test(Integer.parseInt(digits.toString()));
        if (!ours) {
            // A stack: pushed last-first, read back first-last.
            in.pushChar(c);
            for (int i = digits.length() - 1; i >= 0; i--) in.pushChar(digits.charAt(i));
            in.pushChar(']');
            return false;
        }
        String payload = readPayload();
        if (payload != null) listener.osc(Integer.parseInt(digits.toString()), payload);
        return true;
    }

    /** Reads to the terminator. Null when the payload overran the limit and was discarded. */
    private @Nullable String readPayload() throws IOException {
        StringBuilder payload = new StringBuilder();
        boolean overran = false;
        while (true) {
            char c = in.getChar();
            if (c == BEL || c == ST) break;
            if (c == ESC) {
                char next = in.getChar();
                if (next != '\\') {
                    // An escape that is not ST ends the string and starts something else, which is
                    // the emulator's to read: both go back.
                    in.pushChar(next);
                    in.pushChar(ESC);
                }
                break;
            }
            if (payload.length() < MAX_PAYLOAD) payload.append(c);
            else overran = true;
        }
        return overran ? null : payload.toString();
    }

    @Override
    public void pushChar(char c) throws IOException {
        in.pushChar(c);
    }

    @Override
    public String readNonControlCharacters(int maxChars) throws IOException {
        // ESC is a control character, so a run never swallows the start of a sequence.
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
