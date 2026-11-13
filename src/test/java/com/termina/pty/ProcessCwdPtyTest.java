package com.termina.pty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.termina.term.TerminalSession;
import com.termina.testing.Signal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The working-directory read, against a real shell on a real PTY.
 *
 * <p>Structurally this cannot be unit-tested: the whole point is that we are asking the operating
 * system about a process we did not write, through an interface that differs per platform. A
 * compile proves nothing, and the failure mode is a tab that quietly says "Termina" forever.
 *
 * <p>No JavaFX here — the display is a stub, so this runs headless like its sibling PTY test.
 */
class ProcessCwdPtyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Test
    void readsTheShellsDirectoryAndFollowsItAcrossACd() throws Exception {
        assumeTrue(ProcessCwd.isSupported(), "no way to read a cwd on this platform");
        assumeTrue(!ShellLauncher.isWindows(), "shell syntax below is POSIX");

        // A directory of our own, so the assertion cannot pass by coincidence with $HOME or /tmp.
        Path target = Files.createTempDirectory("termina-cwd").toRealPath();

        StubDisplay display = new StubDisplay();
        TerminalSession session = new TerminalSession(display, 80, 24, 5000, "");
        AtomicReference<String> reported = new AtomicReference<>();
        // Fired by the watcher's every report, and by the buffer's every change: what the waits
        // below look again on, instead of a clock (QUAL-07, Pauta 6.0.1).
        Signal told = new Signal();
        Signal reports = new Signal(); // the watcher's alone
        try {
            session.start();
            session.getTextBuffer().addModelListener(told::fire);
            assumeTrue(awaitReady(session, told), "shell did not start");

            // The shell is started in the user's home, and that is what a fresh tab should say.
            assertEquals(
                    Path.of(System.getProperty("user.home")).toRealPath().toString(),
                    ProcessCwd.of(session.pid()).orElse(null),
                    "a new session should report the directory its shell was started in");

            try (CwdWatcher watcher = CwdWatcher.watch(session.pid(), directory -> {
                reported.set(directory);
                told.fire();
                reports.fire();
            })) {
                assertTrue(told.await(() -> reported.get() != null, TIMEOUT), "the watcher never reported at all");

                // Login startup output can precede input readiness. Retry this idempotent command
                // until the watcher observes the actual directory, rather than trusting first output.
                assertTrue(
                        awaitDirectory(session, reported, told, target),
                        () -> "the watcher did not follow the cd. Last reported: " + reported.get());
            }

            // And it stops when closed: whatever it reported last stays put through another cd.
            // The shell is seen to have moved first, by a word it prints once it has; then two of
            // the watcher's periods pass with no report, and the first report would end the wait.
            String afterClose = reported.get();
            TerminalTextBuffer buffer = session.getTextBuffer();
            session.sendString("cd / && printf 'moved-%s\\n' away\r");
            assertTrue(told.await(() -> screen(buffer).contains("moved-away"), TIMEOUT), "the shell never moved");
            assertTrue(reports.quietFor(Duration.ofMillis(2 * 750)), "a closed watcher went on polling");
            assertEquals(afterClose, reported.get(), "a closed watcher went on polling");
        } finally {
            session.close();
            Files.deleteIfExists(target);
        }
    }

    @Test
    void aPidThatIsNotOursAnswersNothingRatherThanThrowing() {
        // Closing a tab races the last poll, so this is an ordinary occurrence, not an error path.
        assertTrue(ProcessCwd.of(-1).isEmpty());
        assertTrue(ProcessCwd.of(0).isEmpty());
        // Comfortably above /proc/sys/kernel/pid_max on any default configuration.
        assertTrue(ProcessCwd.of(Integer.MAX_VALUE).isEmpty());
    }

    private static boolean awaitDirectory(
            TerminalSession session, AtomicReference<String> reported, Signal told, Path target)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            session.sendString("cd '" + target.toString().replace("'", "'\"'\"'") + "'\r");
            // Sent again each second it has not been followed: see the caller.
            if (told.await(() -> target.toString().equals(reported.get()), Duration.ofSeconds(1))) return true;
        }
        return false;
    }

    private static boolean awaitReady(TerminalSession session, Signal told) throws InterruptedException {
        TerminalTextBuffer buffer = session.getTextBuffer();
        return told.await(() -> !screen(buffer).isBlank(), TIMEOUT);
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
