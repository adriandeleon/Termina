package com.termina.term;

import java.util.Locale;
import java.util.function.BooleanSupplier;

import com.jediterm.core.typeahead.TypeAheadTerminalModel;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What JediTerm's type-ahead predictor edits: a copy of the cursor's line with the keystrokes the
 * remote end has not echoed yet, which the view draws over the real one.
 *
 * <p>JediTerm's core ships the predictor — it watches latency, learns what echoes, notices a
 * password prompt and backs off on a misprediction — but not this half. The one its Swing UI uses
 * writes the prediction into a package-private field of {@code TerminalLine} and has the line draw
 * itself; across a module boundary that field is out of reach, so the prediction is kept here and
 * the view paints it.
 *
 * <p><b>Only over SSH.</b> A local shell echoes within a frame, so predicting it can only ever be
 * wrong, and visibly — which is why this was off entirely before. While {@code ssh} is the shell's
 * foreground program the predictor is consulted, and it still shows nothing until it has measured
 * echoes slower than {@link #LATENCY_THRESHOLD_NANOS}: on a fast link it stays out of the way.
 *
 * <p>Every method is called with the buffer lock held — the predictor takes it through {@link
 * #lock} — and the view reads the prediction under the same lock.
 */
public final class TypeAheadModel implements TypeAheadTerminalModel {

    /** JediTerm's own default: echoes slower than this, by median, turn predictions on. */
    static final long LATENCY_THRESHOLD_NANOS = 100_000_000L;

    /** Dim and underlined, as mosh draws its predictions: plainly not yet confirmed. */
    private static final TextStyle PREDICTED = new TextStyle.Builder()
            .setOption(TextStyle.Option.DIM, true)
            .setOption(TextStyle.Option.UNDERLINED, true)
            .build();

    private final JediTerminal terminal;
    private final TerminalTextBuffer buffer;
    private final BooleanSupplier enabled;
    private volatile Runnable onRedraw = () -> {};

    /** The cursor's line with the predictions applied, or null when nothing is predicted. */
    private volatile @Nullable TerminalLine predicted;

    private volatile int predictedRow;
    private volatile int predictedCursor = -1;

    /** @param enabled whether predictions are wanted now — SSH in the foreground, and the setting */
    TypeAheadModel(JediTerminal terminal, TerminalTextBuffer buffer, BooleanSupplier enabled) {
        this.terminal = terminal;
        this.buffer = buffer;
        this.enabled = enabled;
    }

    /** Whether a foreground program is one whose echo crosses a network: {@code ssh}. */
    public static boolean predictsFor(String program) {
        if (program == null) return false;
        String name = program.toLowerCase(Locale.ROOT);
        return name.equals("ssh") || name.equals("ssh.exe");
    }

    /** Told when the prediction changed and the screen should show it. */
    public void setOnRedraw(Runnable onRedraw) {
        this.onRedraw = onRedraw == null ? () -> {} : onRedraw;
    }

    /** What to draw, or null. Read under the buffer lock. */
    public @Nullable Prediction prediction() {
        TerminalLine line = predicted;
        return line == null ? null : new Prediction(predictedRow, line, predictedCursor);
    }

    /**
     * A predicted line.
     *
     * @param row the screen row it replaces, 0 at the top of the live screen
     * @param cursor the column the cursor is predicted at, or -1 to leave it where the shell put it
     */
    public record Prediction(int row, TerminalLine line, int cursor) {}

    private TerminalLine predictedLine() {
        int row = terminal.getCursorY() - 1;
        TerminalLine line = predicted;
        if (line == null || predictedRow != row) {
            line = buffer.getLine(row).copy();
            predictedRow = row;
        }
        return line;
    }

    @Override
    public void insertCharacter(char ch, int index) {
        TerminalLine line = predictedLine();
        line.insertString(index, new CharBuffer(ch, 1), PREDICTED);
        predicted = line;
    }

    @Override
    public void removeCharacters(int from, int count) {
        TerminalLine line = predictedLine();
        line.deleteCharacters(from, count, TextStyle.EMPTY);
        predicted = line;
    }

    @Override
    public void moveCursor(int index) {
        predictedLine();
        predictedCursor = index;
    }

    @Override
    public void forceRedraw() {
        onRedraw.run();
    }

    @Override
    public void clearPredictions() {
        boolean had = predicted != null;
        predicted = null;
        predictedCursor = -1;
        if (had) onRedraw.run();
    }

    @Override
    public void lock() {
        buffer.lock();
    }

    @Override
    public void unlock() {
        buffer.unlock();
    }

    @Override
    public boolean isUsingAlternateBuffer() {
        return buffer.isUsingAlternateBuffer();
    }

    @Override
    public LineWithCursorX getCurrentLineWithCursor() {
        TerminalLine line = buffer.getLine(terminal.getCursorY() - 1);
        return new LineWithCursorX(new StringBuffer(line.getText()), terminal.getCursorX() - 1);
    }

    @Override
    public int getTerminalWidth() {
        return buffer.getWidth();
    }

    @Override
    public boolean isTypeAheadEnabled() {
        return enabled.getAsBoolean();
    }

    @Override
    public long getLatencyThreshold() {
        return LATENCY_THRESHOLD_NANOS;
    }

    /** Unknown: the shell is on the other machine, and its word boundaries are the default's. */
    @Override
    public ShellType getShellType() {
        return ShellType.Unknown;
    }
}
