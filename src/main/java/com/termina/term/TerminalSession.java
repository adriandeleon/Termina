package com.termina.term;

import java.io.IOException;
import java.util.List;

import com.jediterm.core.typeahead.TerminalTypeAheadManager;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalStarter;
import com.jediterm.terminal.TtyBasedArrayDataStream;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.pty4j.PtyProcess;
import com.termina.pty.PtyTtyConnector;
import com.termina.pty.ShellLauncher;

/**
 * One shell attached to one emulator: PTY process, escape-sequence parser, and screen buffer.
 *
 * <p>The read loop runs on its own thread — {@link TerminalStarter#start()} blocks for the life of
 * the session — and writes into the buffer under its lock. Renderers must take the same lock.
 */
public final class TerminalSession {

    private final PtyProcess process;
    private final PtyTtyConnector connector;
    private final TerminalTextBuffer textBuffer;
    private final JediTerminal terminal;
    private final TerminalStarter starter;
    private final TerminalExecutors executors;
    private final TypeAheadModel typeAheadModel;

    /** What programs have asked of the keyboard with the kitty protocol. */
    private final KeyboardFlags keyboardFlags = new KeyboardFlags();

    private final TerminalTypeAheadManager typeAhead;

    /** Whether predictions are wanted now. Off until the view says otherwise. */
    private volatile java.util.function.BooleanSupplier typeAheadWanted = () -> false;

    private final Thread emulatorThread;

    private volatile boolean closed;
    private Runnable onSessionEnded = () -> {};

    /** Told, on the emulator thread, of each operating-system command in {@link OscSequences#handled}. */
    private volatile OscInterceptor.Listener oscListener = (code, payload) -> {};

    public TerminalSession(
            com.jediterm.terminal.TerminalDisplay display,
            int columns,
            int rows,
            int scrollbackLines,
            String shellOverride)
            throws IOException {
        this(display, columns, rows, scrollbackLines, com.termina.pty.LaunchOptions.ofShell(shellOverride));
    }

    public TerminalSession(
            com.jediterm.terminal.TerminalDisplay display,
            int columns,
            int rows,
            int scrollbackLines,
            com.termina.pty.LaunchOptions options)
            throws IOException {
        // The same list the process was started from, so the connector reports the real command.
        List<String> command = options.hasCommand() ? options.command() : ShellLauncher.shellCommand(options.shell());
        process = ShellLauncher.start(columns, rows, options);
        connector = new PtyTtyConnector(process, command);

        StyleState styleState = new StyleState();
        textBuffer = new TerminalTextBuffer(columns, rows, styleState, Math.max(0, scrollbackLines));
        terminal = new JediTerminal(display, textBuffer, styleState);

        executors = new TerminalExecutors();
        typeAheadModel = new TypeAheadModel(terminal, textBuffer, () -> typeAheadWanted.getAsBoolean());
        typeAhead = new TerminalTypeAheadManager(typeAheadModel);
        // The predictor's own timer, which drops predictions the far end never confirmed. Paired
        // with its constant the way JediTerm's UI pairs them.
        typeAhead.setClearPredictionsDebouncer(new com.jediterm.terminal.model.JediTermDebouncerImpl(
                typeAhead::debounce, TerminalTypeAheadManager.MAX_TERMINAL_DELAY, executors));
        // Output is how a prediction is confirmed or refuted. Cheap when predictions are off: the
        // first thing it asks is whether they are on.
        textBuffer.addModelListener(typeAhead::onTerminalStateChanged);
        // The commands JediTerm leaves unhandled are taken out where the emulator reads, so each is
        // seen at its place in the output — see OscInterceptor.
        OscInterceptor osc = new OscInterceptor(
                new TtyBasedArrayDataStream(connector),
                OscSequences::handled,
                (code, payload) -> oscListener.osc(code, payload));
        // The kitty keyboard protocol's mode requests, likewise taken where the emulator reads, so a
        // program that switches screens and then sets a mode sets it on the screen it meant.
        KeyboardModeInterceptor stream = new KeyboardModeInterceptor(osc, new KeyboardModeInterceptor.Listener() {
            @Override
            public void keyboardMode(char marker, String params) {
                byte[] answer = keyboardFlags.apply(marker, params, textBuffer.isUsingAlternateBuffer());
                if (answer != null) answerProgram(answer);
            }

            @Override
            public void reset() {
                keyboardFlags.reset();
            }
        });
        starter = new TerminalStarter(terminal, connector, stream, typeAhead, executors);

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
            // Reached when the shell exits (the user typed `exit`, or the process died) — but also
            // when we tore the session down ourselves. Only the first is news: firing the callback
            // during our own shutdown races the toolkit, whose listener marshals to the FX thread
            // ("Main Java thread is detached" out of the Glass dispatcher once it has stopped).
            if (!closed) onSessionEnded.run();
        }
    }

    /**
     * Receives the shell-integration, directory and clipboard commands. Called on the emulator
     * thread, at the point in the output where each was written, so the cursor and the screen are
     * as they were when the program sent it. Set before {@link #start()}.
     */
    public void setOscListener(OscInterceptor.Listener listener) {
        this.oscListener = listener == null ? (code, payload) -> {} : listener;
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
        typeAhead.onResize();
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

    /**
     * When keystrokes may be predicted — see {@link TypeAheadModel}. Asked on every keystroke and
     * every output change, so it has to be cheap.
     */
    public void setTypeAheadWanted(java.util.function.BooleanSupplier wanted) {
        this.typeAheadWanted = wanted == null ? () -> false : wanted;
    }

    /**
     * The kitty keyboard protocol flags in force on a screen: 0 until a program asks, which is the
     * legacy encoding every key has always had.
     */
    public int keyboardFlags(boolean alternateScreen) {
        return keyboardFlags.current(alternateScreen);
    }

    /** Sends a reply to a query, the way JediTerm sends its own: not as typing. */
    private void answerProgram(byte[] answer) {
        if (!closed) starter.sendBytes(answer, false);
    }

    /** The predicted line, for the view to draw. */
    public TypeAheadModel typeAhead() {
        return typeAheadModel;
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

    /** The shell's own pid — what the OS is asked about to find the working directory. */
    public long pid() {
        return process.pid();
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
