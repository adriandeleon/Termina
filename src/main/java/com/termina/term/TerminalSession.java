package com.termina.term;

import com.jediterm.core.typeahead.TerminalTypeAheadManager;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalStarter;
import com.jediterm.terminal.TtyBasedArrayDataStream;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.termina.pty.PtyTtyConnector;
import com.termina.pty.ShellLauncher;
import com.pty4j.PtyProcess;
import java.io.IOException;
import java.util.List;

/**
 * One shell attached to one emulator: PTY process, escape-sequence parser, and screen buffer.
 *
 * <p>The read loop runs on its own thread — {@link TerminalStarter#start()} blocks for the life of
 * the session — and writes into the buffer under its lock. Renderers must take the same lock.
 */
public final class TerminalSession {

    /** Lines retained above the screen. 5000 is roughly a terminal's worth of `make` output. */
    private static final int SCROLLBACK_LINES = 5000;

    private final PtyProcess process;
    private final PtyTtyConnector connector;
    private final TerminalTextBuffer textBuffer;
    private final JediTerminal terminal;
    private final TerminalStarter starter;
    private final TerminalExecutors executors;
    private final Thread emulatorThread;

    private volatile boolean closed;
    private Runnable onSessionEnded = () -> {};

    public TerminalSession(
            com.jediterm.terminal.TerminalDisplay display, int columns, int rows) throws IOException {
        List<String> command = ShellLauncher.shellCommand();
        process = ShellLauncher.start(columns, rows);
        connector = new PtyTtyConnector(process, command);

        StyleState styleState = new StyleState();
        textBuffer = new TerminalTextBuffer(columns, rows, styleState, SCROLLBACK_LINES);
        terminal = new JediTerminal(display, textBuffer, styleState);

        executors = new TerminalExecutors();
        TerminalTypeAheadManager typeAhead = new TerminalTypeAheadManager(new DisabledTypeAhead());
        starter = new TerminalStarter(
                terminal, connector, new TtyBasedArrayDataStream(connector), typeAhead, executors);

        emulatorThread = new Thread(this::runEmulator, "termina-emulator");
        emulatorThread.setDaemon(true);
    }

    public void start() {
        emulatorThread.start();
    }

    private void runEmulator() {
        try {
            starter.start();
        } catch (Exception e) {
            if (!closed) {
                System.getLogger(TerminalSession.class.getName())
                        .log(System.Logger.Level.ERROR, "terminal emulator stopped", e);
            }
        } finally {
            // Reached when the shell exits (the user typed `exit`, or the process died).
            Runnable ended = onSessionEnded;
            ended.run();
        }
    }

    /** Called off the FX thread when the shell exits. */
    public void setOnSessionEnded(Runnable onSessionEnded) {
        this.onSessionEnded = onSessionEnded;
    }

    /**
     * Applies a new terminal size. Reflows the buffer and issues the PTY ioctl, which is what
     * makes the child receive SIGWINCH and redraw.
     */
    public void resize(int columns, int rows) {
        if (closed || columns <= 0 || rows <= 0) return;
        starter.postResize(new TermSize(columns, rows), RequestOrigin.User);
    }

    /** Sends raw bytes to the shell (already-encoded key sequences, pasted text). */
    public void send(byte[] bytes) {
        if (closed || bytes == null || bytes.length == 0) return;
        starter.sendBytes(bytes, true);
    }

    public void sendString(String text) {
        if (closed || text == null || text.isEmpty()) return;
        starter.sendString(text, true);
    }

    /** Encodes a key using the emulator's current modes (application vs ANSI cursor keys). */
    public byte[] keyCode(int awtKeyCode, int awtModifiers) {
        return starter.getCode(awtKeyCode, awtModifiers);
    }

    public TerminalTextBuffer getTextBuffer() {
        return textBuffer;
    }

    public JediTerminal getTerminal() {
        return terminal;
    }

    public boolean isRunning() {
        return !closed && process.isAlive();
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            starter.requestEmulatorStop();
        } catch (Exception ignored) {
            // Best effort: we are shutting down and the connector may already be gone.
        }
        try {
            connector.close();
        } catch (Exception ignored) {
        }
        executors.shutdownNow();
    }
}
