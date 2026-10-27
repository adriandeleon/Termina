package com.termina.term;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.jediterm.terminal.TerminalDataStream;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites the colon forms of SGR ({@code CSI … m}) into what JediTerm reads, before it sees them.
 *
 * <p>ITU T.416 separates a parameter's sub-parameters with colons: {@code 38:2::255:0:0} for a
 * true colour, {@code 4:3} for a curly underline. JediTerm knows only semicolons, and on meeting a
 * colon stops parsing and prints the rest — {@code :::::X} for one red X, with the colour lost.
 * Each parameter that has colons is rewritten by {@link #rewrite}; one without passes as written.
 *
 * <p>Only a plain SGR is touched: no private marker (XTMODKEYS is {@code CSI > 4 ; 2 m}), no
 * intermediate bytes, and at least one colon. Anything else is put back exactly as it was read.
 */
public final class SgrColonInterceptor implements TerminalDataStream {

    private static final char ESC = 0x1B;

    /** Longer than any SGR a program writes; a run past it is put back for the emulator. */
    private static final int MAX_PARAMS = 256;

    private final TerminalDataStream in;

    public SgrColonInterceptor(TerminalDataStream in) {
        this.in = in;
    }

    @Override
    public char getChar() throws IOException {
        while (true) {
            char c = in.getChar();
            if (c != ESC) return c;
            char next = in.getChar();
            if (next != '[' || takeSgr()) {
                if (next != '[') in.pushChar(next);
                return c;
            }
            // Every parameter was one JediTerm cannot use, and the sequence is gone: sending
            // CSI m in its place would be a reset the program never asked for.
        }
    }

    /**
     * Having read {@code ESC [}, rewrites an SGR with colons and puts the result back after the
     * {@code [}. False when nothing of it was left to send.
     */
    private boolean takeSgr() throws IOException {
        StringBuilder read = new StringBuilder();
        char c = in.getChar();
        boolean colon = false;
        while (((c >= '0' && c <= '9') || c == ';' || c == ':') && read.length() < MAX_PARAMS) {
            colon |= c == ':';
            read.append(c);
            c = in.getChar();
        }
        String rewritten = colon && c == 'm' ? rewrite(read.toString()) : null;
        if (rewritten != null && rewritten.isEmpty()) return false;
        String back = (rewritten != null ? rewritten : read) + String.valueOf(c);
        for (int i = back.length() - 1; i >= 0; i--) in.pushChar(back.charAt(i));
        in.pushChar('[');
        return true;
    }

    /**
     * The parameters of one SGR with each colon form spelled the way JediTerm reads it, and the ones
     * it cannot draw left out. Empty when none is left.
     *
     * <ul>
     *   <li>{@code 38:5:n}, {@code 48:5:n} become {@code 38;5;n}, {@code 48;5;n}.
     *   <li>{@code 38:2:cs:r:g:b} becomes {@code 38;2;r;g;b}, dropping the colour space, which is
     *       usually empty and never used. With three values after the 2 they are r, g and b — the
     *       form some programs write without the colour-space slot; kitty and VTE read it so too.
     *   <li>{@code 4:0} becomes {@code 24}, no underline; every other underline style ({@code 4:1}
     *       to {@code 4:5}) becomes {@code 4}, the one underline JediTerm has.
     *   <li>Anything else with a colon is dropped — {@code 58}, the underline colour, among them:
     *       JediTerm has no such attribute, and read with semicolons its 2 and 5 would be faint and
     *       blink.
     * </ul>
     *
     * Empty sub-parameters count as 0, as they do in every other parameter.
     */
    static String rewrite(String params) {
        List<String> out = new ArrayList<>();
        for (String param : params.split(";", -1)) {
            if (param.indexOf(':') < 0) {
                out.add(param);
                continue;
            }
            String spelled = spell(param.split(":", -1));
            if (spelled != null) out.add(spelled);
        }
        return String.join(";", out);
    }

    private static @Nullable String spell(String[] sub) {
        String head = sub[0];
        if (head.equals("38") || head.equals("48")) {
            if (sub.length == 3 && sub[1].equals("5")) return head + ";5;" + number(sub[2]);
            if (sub[1].equals("2") && (sub.length == 5 || sub.length == 6)) {
                int r = sub.length - 3;
                return head + ";2;" + number(sub[r]) + ";" + number(sub[r + 1]) + ";" + number(sub[r + 2]);
            }
            return null;
        }
        if (head.equals("4") && sub.length == 2) return number(sub[1]).equals("0") ? "24" : "4";
        return null;
    }

    private static String number(String sub) {
        return sub.isEmpty() ? "0" : sub;
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
