package com.termina;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.termina.pty.ShellLauncher;
import com.termina.term.TerminalSession;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * End-to-end over a real PTY: start the user's shell, run a command, read it back off the emulated
 * screen.
 *
 * <p>This exercises the part of the stack that unit tests structurally cannot — pty4j's native
 * library actually loading and extracting, the shell starting under a PTY, and JediTerm's parser
 * consuming its output. A compile proves none of that, and the failure mode for any of it is a
 * blank window rather than an exception.
 *
 * <p>No JavaFX here: the display below is a bare stub, so this runs headless.
 */
class TerminalSessionPtyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Test
    void runsACommandAndShowsItsOutput() throws Exception {
        assumeTrue(!ShellLauncher.isWindows(), "shell syntax below is POSIX");

        StubDisplay display = new StubDisplay();
        TerminalSession session = new TerminalSession(display, 80, 24);
        try {
            session.start();
            TerminalTextBuffer buffer = session.getTextBuffer();

            // A marker unlikely to appear in a prompt, MOTD, or shell startup noise.
            String marker = "TERMINA_PTY_OK_" + ProcessHandle.current().pid();
            // Wait for the shell to be ready to read before typing at it: a login shell sources the
            // user's profile first, and input sent before then is simply discarded.
            assumeTrue(awaitReady(session), "shell did not start");
            session.sendString("echo " + marker + "\r");

            assertTrue(
                    await(buffer, marker),
                    () -> "marker never appeared on screen. Screen was:\n" + screen(buffer));
        } finally {
            session.close();
        }
    }

    @Test
    void resizeIsAppliedToTheBuffer() throws Exception {
        assumeTrue(!ShellLauncher.isWindows(), "shell syntax below is POSIX");

        StubDisplay display = new StubDisplay();
        TerminalSession session = new TerminalSession(display, 80, 24);
        try {
            session.start();
            assumeTrue(awaitReady(session), "shell did not start");
            session.resize(100, 30);

            TerminalTextBuffer buffer = session.getTextBuffer();
            Instant deadline = Instant.now().plus(TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                if (buffer.getWidth() == 100 && buffer.getHeight() == 30) return;
                Thread.sleep(50);
            }
            assertTrue(false, "buffer stayed " + buffer.getWidth() + "x" + buffer.getHeight());
        } finally {
            session.close();
        }
    }

    /** True once the shell has written anything at all — i.e. it is up and has printed a prompt. */
    private static boolean awaitReady(TerminalSession session) throws InterruptedException {
        TerminalTextBuffer buffer = session.getTextBuffer();
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!screen(buffer).isBlank()) return true;
            if (!session.isRunning()) return false;
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean await(TerminalTextBuffer buffer, String needle) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            // The command echoes once as typed and once as output; two occurrences means the shell
            // actually ran it rather than merely echoing the keystrokes back.
            if (countOccurrences(screen(buffer), needle) >= 2) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }

    private static String screen(TerminalTextBuffer buffer) {
        buffer.lock();
        try {
            return buffer.getScreenLines();
        } finally {
            buffer.unlock();
        }
    }

    /** Everything the emulator calls into, doing nothing. */
    private static final class StubDisplay implements TerminalDisplay {
        @Override
        public void setCursor(int x, int y) {}

        @Override
        public void setCursorShape(CursorShape shape) {}

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
            return "test";
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
