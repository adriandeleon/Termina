package com.termina.ui;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.TerminalSelection;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * The emulator's view of the display: JediTerm calls into this whenever an escape sequence changes
 * something the UI owns (cursor position, window title, alternate screen, mouse reporting).
 *
 * <p><b>Threading:</b> every method here is invoked on the emulator thread, never on the JavaFX
 * application thread. State the renderer reads is therefore held in volatile fields, and anything
 * that touches the scene graph (the window title) is bounced through {@link Platform#runLater}.
 */
public final class FxTerminalDisplay implements TerminalDisplay {

    /** Cursor column, 0-based. */
    private volatile int cursorX;
    /** Cursor row as JediTerm reports it: <b>1-based</b>, relative to the top of the screen. */
    private volatile int cursorY = 1;

    private volatile boolean cursorVisible = true;
    private volatile CursorShape cursorShape = CursorShape.BLINK_BLOCK;
    private volatile boolean alternateScreen;
    private volatile MouseMode mouseMode = MouseMode.MOUSE_REPORTING_NONE;
    private volatile MouseFormat mouseFormat = MouseFormat.MOUSE_FORMAT_XTERM;
    private volatile boolean bracketedPaste;

    private final StringProperty windowTitle = new SimpleStringProperty("Termina");
    private volatile String titleValue = "Termina";

    private Runnable onBell = () -> {};
    private Runnable onRepaint = () -> {};
    private Runnable onAlternateScreenChanged = () -> {};

    public void setOnBell(Runnable onBell) {
        this.onBell = onBell;
    }

    /** Invoked (off the FX thread) whenever something changed that the renderer should reflect. */
    public void setOnRepaint(Runnable onRepaint) {
        this.onRepaint = onRepaint;
    }

    public void setOnAlternateScreenChanged(Runnable listener) {
        this.onAlternateScreenChanged = listener;
    }

    @Override
    public void setCursor(int x, int y) {
        cursorX = x;
        cursorY = y;
        onRepaint.run();
    }

    @Override
    public void setCursorShape(CursorShape shape) {
        if (shape != null) cursorShape = shape;
        onRepaint.run();
    }

    @Override
    public void beep() {
        onBell.run();
    }

    @Override
    public void onResize(TermSize newSize, RequestOrigin origin) {
        onRepaint.run();
    }

    @Override
    public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {
        onRepaint.run();
    }

    @Override
    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        onRepaint.run();
    }

    @Override
    public void useAlternateScreenBuffer(boolean enabled) {
        alternateScreen = enabled;
        // Entering the alternate screen (vim, less, htop) must also pin the view to the live
        // screen: scrollback belongs to the primary buffer, and leaving a scroll offset applied
        // would render the full-screen program at an offset it knows nothing about.
        onAlternateScreenChanged.run();
        onRepaint.run();
    }

    @Override
    public String getWindowTitle() {
        return titleValue;
    }

    @Override
    public void setWindowTitle(String title) {
        String value = title == null || title.isBlank() ? "Termina" : title;
        titleValue = value;
        Platform.runLater(() -> windowTitle.set(value));
    }

    public StringProperty windowTitleProperty() {
        return windowTitle;
    }

    @Override
    public TerminalSelection getSelection() {
        // Selection is a UI concept the emulator only consults to keep a highlight consistent
        // across a scroll. Not implemented yet — see the deferred list in README.
        return null;
    }

    @Override
    public void terminalMouseModeSet(MouseMode mode) {
        mouseMode = mode;
    }

    @Override
    public void setMouseFormat(MouseFormat format) {
        mouseFormat = format;
    }

    @Override
    public boolean ambiguousCharsAreDoubleWidth() {
        // East-Asian "ambiguous width" characters rendered narrow, matching xterm's default and
        // every mainstream terminal. Rendering them wide desynchronises column arithmetic against
        // any program that assumes narrow.
        return false;
    }

    @Override
    public void setBracketedPasteMode(boolean enabled) {
        bracketedPaste = enabled;
    }

    public int getCursorX() {
        return cursorX;
    }

    /** 1-based, as JediTerm reports it. Subtract 1 to index a row. */
    public int getCursorY() {
        return cursorY;
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    public CursorShape getCursorShape() {
        return cursorShape;
    }

    public boolean isAlternateScreen() {
        return alternateScreen;
    }

    public MouseMode getMouseMode() {
        return mouseMode;
    }

    public MouseFormat getMouseFormat() {
        return mouseFormat;
    }

    public boolean isBracketedPaste() {
        return bracketedPaste;
    }
}
