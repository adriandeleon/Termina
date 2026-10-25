package com.termina.term;

import java.util.concurrent.atomic.AtomicBoolean;

import com.jediterm.core.typeahead.TerminalTypeAheadManager;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JediTerm's own predictor driven against this model, with an echo slow enough to switch it on — the
 * whole loop a slow SSH link would exercise, without one.
 */
class TypeAheadModelTest {

    private final StyleState style = new StyleState();
    private final TerminalTextBuffer buffer = new TerminalTextBuffer(80, 24, style, 100);
    private final JediTerminal terminal = new JediTerminal(new NoDisplay(), buffer, style);
    private final AtomicBoolean wanted = new AtomicBoolean(true);
    private final TypeAheadModel model = new TypeAheadModel(terminal, buffer, wanted::get);
    private final TerminalTypeAheadManager predictor = new TerminalTypeAheadManager(model);

    /** Types a key, then echoes it after a delay, as a far-away shell would. */
    private void typeWithSlowEcho(char c) throws InterruptedException {
        predictor.onKeyEvent(TerminalTypeAheadManager.TypeAheadEvent.fromChar(c));
        Thread.sleep(150);
        terminal.writeCharacters(String.valueOf(c));
        predictor.onTerminalStateChanged();
    }

    private String predictedText() {
        TypeAheadModel.Prediction prediction = model.prediction();
        return prediction == null ? null : prediction.line().getText().strip();
    }

    @Test
    void aSlowEchoTurnsPredictionsOnAndAKeystrokeShowsAtOnce() throws InterruptedException {
        terminal.writeCharacters("$ ");
        predictor.onTerminalStateChanged();
        typeWithSlowEcho('l');
        typeWithSlowEcho('s');
        typeWithSlowEcho(' ');

        predictor.onKeyEvent(TerminalTypeAheadManager.TypeAheadEvent.fromChar('-'));
        // Shown before the far end has said anything: that is the whole feature.
        assertEquals("$ ls -", predictedText());
        assertEquals(0, model.prediction().row());
        assertEquals(6, model.prediction().cursor());

        // The echo arrives and matches: the prediction has nothing left to add.
        terminal.writeCharacters("-");
        predictor.onTerminalStateChanged();
        String after = predictedText();
        assertTrue(after == null || after.equals("$ ls -"), after);
    }

    @Test
    void aFastEchoNeverShowsAPrediction() {
        terminal.writeCharacters("$ ");
        for (char c : "echo hi".toCharArray()) {
            predictor.onKeyEvent(TerminalTypeAheadManager.TypeAheadEvent.fromChar(c));
            terminal.writeCharacters(String.valueOf(c));
            predictor.onTerminalStateChanged();
        }
        predictor.onKeyEvent(TerminalTypeAheadManager.TypeAheadEvent.fromChar('!'));
        assertNull(model.prediction());
    }

    @Test
    void nothingIsPredictedWhenNotWanted() throws InterruptedException {
        wanted.set(false);
        terminal.writeCharacters("$ ");
        typeWithSlowEcho('a');
        typeWithSlowEcho('b');
        typeWithSlowEcho('c');
        predictor.onKeyEvent(TerminalTypeAheadManager.TypeAheadEvent.fromChar('d'));
        assertNull(model.prediction());
    }

    @Test
    void predictsOnlyForSsh() {
        assertTrue(TypeAheadModel.predictsFor("ssh"));
        assertTrue(TypeAheadModel.predictsFor("SSH.EXE"));
        assertFalse(TypeAheadModel.predictsFor("vim"));
        assertFalse(TypeAheadModel.predictsFor("mosh-client"));
        assertFalse(TypeAheadModel.predictsFor(""));
        assertFalse(TypeAheadModel.predictsFor(null));
    }

    @Test
    void clearingTellsTheViewOnlyWhenSomethingWasShown() {
        int[] redraws = {0};
        model.setOnRedraw(() -> redraws[0]++);
        model.clearPredictions();
        assertEquals(0, redraws[0]);
        buffer.lock();
        try {
            model.insertCharacter('x', 0);
        } finally {
            buffer.unlock();
        }
        assertNotNull(model.prediction());
        model.clearPredictions();
        assertEquals(1, redraws[0]);
        assertNull(model.prediction());
    }

    /** The emulator needs a display; nothing here looks at one. */
    private static final class NoDisplay implements TerminalDisplay {
        @Override
        public void setCursor(int x, int y) {}

        @Override
        public void setCursorShape(CursorShape shape) {}

        @Override
        public void beep() {}

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
