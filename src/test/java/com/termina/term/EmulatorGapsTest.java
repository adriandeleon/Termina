package com.termina.term;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TerminalDataStream;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sequences JediTerm gets wrong, fed through the chain a session reads through
 * ({@link TerminalSession#readThrough}), then {@link JediEmulator} into a real buffer — with no PTY
 * and no toolkit.
 *
 * <p>Where an interceptor repairs a sequence, its test asserts xterm's behaviour. Where nothing does
 * yet, the test pins what happens <em>now</em>, with what xterm does in its comment: a known gap, not
 * intended behaviour. When a JediTerm upgrade or a new interceptor fixes one, its test fails, and the
 * assertion flips to xterm's. The {@code control} tests are the same checks on forms JediTerm reads
 * itself, so a gap cannot be the harness's fault.
 */
class EmulatorGapsTest {

    private static final int COLUMNS = 10;

    private final StyleState styleState = new StyleState();
    private final TerminalTextBuffer buffer = new TerminalTextBuffer(COLUMNS, 3, styleState, 0);
    private final List<CursorShape> shapes = new ArrayList<>();
    private final JediTerminal terminal = new JediTerminal(new Display(), buffer, styleState);

    /** Everything up to the end of {@code input}, as the emulator thread would process it. */
    private void feed(String input) throws IOException {
        // The constructor's reset reports a null shape; only what the input causes is of interest.
        shapes.clear();
        TerminalDataStream stream = TerminalSession.readThrough(
                new ArrayTerminalDataStream(input.toCharArray()),
                (code, payload) -> {},
                new KeyboardModeInterceptor.Listener() {
                    @Override
                    public void keyboardMode(char marker, String params) {}

                    @Override
                    public void reset() {}
                });
        JediEmulator emulator = new JediEmulator(stream, terminal);
        while (emulator.hasNext()) emulator.next();
    }

    private String row(int y) {
        return buffer.getLine(y).getText().stripTrailing();
    }

    private TextStyle styleAt(int x) {
        return buffer.getLine(0).getStyleAt(x);
    }

    // ---------------------------------------------------------------- REP

    @Test
    void repeatIsIgnored() throws IOException {
        // xterm: "aaaaaa". ncurses sends REP for runs of 7 or more, so borders lose their middles.
        feed("a\u001b[5b");
        assertEquals("a", row(0));
    }

    // ---------------------------------------------------------------- colon SGR

    @Test
    void controlSemicolonTruecolor() throws IOException {
        feed("\u001b[38;2;255;0;0mX");
        assertEquals("X", row(0));
        assertEquals(TerminalColor.rgb(255, 0, 0), styleAt(0).getForeground());
    }

    @Test
    void colonTruecolor() throws IOException {
        feed("\u001b[38:2::255:0:0mX");
        assertEquals("X", row(0));
        assertEquals(TerminalColor.rgb(255, 0, 0), styleAt(0).getForeground());
    }

    @Test
    void controlSemicolonIndexed() throws IOException {
        feed("\u001b[38;5;196mX");
        assertEquals("X", row(0));
        // JediTerm stores palette colours above 15 as their RGB; 196 is #ff0000.
        assertEquals(TerminalColor.rgb(255, 0, 0), styleAt(0).getForeground());
    }

    @Test
    void colonIndexed() throws IOException {
        feed("\u001b[38:5:196mX");
        assertEquals("X", row(0));
        assertEquals(TerminalColor.rgb(255, 0, 0), styleAt(0).getForeground());
    }

    @Test
    void colonUnderlineStyle() throws IOException {
        // xterm draws it curly; JediTerm has one underline, and that is the one it gets. Neovim
        // sends this for diagnostics when it believes the terminal supports styled underlines.
        feed("\u001b[4:3mX");
        assertEquals("X", row(0));
        assertTrue(styleAt(0).hasOption(TextStyle.Option.UNDERLINED));
    }

    @Test
    void underlineColourIsDroppedWithoutDisturbingTheRest() throws IOException {
        feed("\u001b[1;58:2::255:0:0mX");
        assertEquals("X", row(0));
        assertTrue(styleAt(0).hasOption(TextStyle.Option.BOLD));
        assertFalse(styleAt(0).hasOption(TextStyle.Option.DIM));
    }

    // ---------------------------------------------------------------- APC, SOS, PM

    @Test
    void applicationProgramCommand() throws IOException {
        // The kitty graphics protocol's envelope: unanswered, it is an image not drawn, not base64.
        feed("\u001b_Ga=T;AAAA\u001b\\X");
        assertEquals("X", row(0));
    }

    @Test
    void startOfString() throws IOException {
        feed("\u001bXhidden\u001b\\X");
        assertEquals("X", row(0));
    }

    @Test
    void privacyMessage() throws IOException {
        feed("\u001b^hidden\u001b\\X");
        assertEquals("X", row(0));
    }

    // ---------------------------------------------------------------- DECSLRM

    @Test
    void leftRightMarginsAreIgnored() throws IOException {
        // Margins at columns 2–4, then insert a blank at column 2. xterm shifts only within the
        // margins, dropping the 3: "0 12456789". Here the whole rest of the line shifts.
        feed("0123456789\u001b[?69h\u001b[2;4s\u001b[1;2H\u001b[@");
        assertEquals("0 12345678", row(0));
    }

    // ---------------------------------------------------------------- cursor blink

    @Test
    void controlCursorStyleReachesTheDisplay() throws IOException {
        feed("\u001b[2 qX");
        assertEquals(List.of(CursorShape.STEADY_BLOCK), shapes);
    }

    @Test
    void blinkModeNeverReachesTheDisplay() throws IOException {
        // xterm: ?12 l stops the cursor blinking and ?12 h starts it. Neither is reported here.
        // Harmless for now, because Termina draws a steady cursor whatever it is told.
        feed("\u001b[?12l\u001b[?12hX");
        assertEquals("X", row(0));
        assertEquals(List.of(), shapes);
    }

    private final class Display implements TerminalDisplay {
        @Override
        public void setCursor(int x, int y) {}

        @Override
        public void setCursorShape(CursorShape shape) {
            shapes.add(shape);
        }

        @Override
        public void beep() {}

        @Override
        public void onResize(TermSize newSize, RequestOrigin origin) {}

        @Override
        public void scrollArea(int top, int size, int dy) {}

        @Override
        public void setCursorVisible(boolean visible) {}

        @Override
        public void useAlternateScreenBuffer(boolean enabled) {}

        @Override
        public String getWindowTitle() {
            return "";
        }

        @Override
        public void setWindowTitle(String title) {}

        @Override
        public TerminalSelection getSelection() {
            return null;
        }

        @Override
        public void terminalMouseModeSet(MouseMode mode) {}

        @Override
        public void setMouseFormat(MouseFormat format) {}

        @Override
        public boolean ambiguousCharsAreDoubleWidth() {
            return false;
        }
    }
}
