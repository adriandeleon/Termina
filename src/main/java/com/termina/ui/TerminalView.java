package com.termina.ui;

import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.StyledTextConsumer;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.util.CharUtils;
import com.termina.term.TerminalSession;
import java.io.IOException;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * Renders a {@link TerminalSession}'s screen onto a canvas and feeds it keyboard input.
 *
 * <p><b>Repaint policy.</b> The emulator signals a change on its own thread, often once per
 * character written. Repainting on each signal would queue thousands of runLater tasks a second
 * during something like {@code cat} of a large file. Instead a change only sets a dirty flag, and a
 * single {@link AnimationTimer} repaints at most once per frame — the terminal's output rate is
 * decoupled from the render rate, which is what keeps a flood of output from starving the UI.
 *
 * <p>Drawing is per style run, not per character: {@code processScreenLines} hands back each run of
 * identically-styled text, and each becomes one {@code fillText} call.
 */
public final class TerminalView extends Region {

    /** Extra leading, as a fraction of font size. Terminals look cramped at exactly 1.0. */
    private static final double LINE_SPACING = 1.18;

    private static final int MIN_COLUMNS = 20;
    private static final int MIN_ROWS = 4;

    private final Canvas canvas = new Canvas();
    private final FxTerminalDisplay display = new FxTerminalDisplay();

    private TerminalSession session;
    private TerminalTextBuffer buffer;

    private Font font;
    private Font boldFont;
    private Font italicFont;
    private Font boldItalicFont;
    private double fontSize;
    private double charWidth;
    private double lineHeight;
    private double ascent;

    /**
     * Rows scrolled back. 0 is the live screen; negative values reach into history. Named after
     * JediTerm's own term for the coordinate {@code processHistoryAndScreenLines} expects.
     */
    private int scrollOrigin;

    private volatile boolean dirty = true;
    private int columns = 80;
    private int rows = 24;

    /** Alt/Option prefixes ESC rather than composing a character; see {@link KeyEncoding}. */
    private boolean altIsMeta = true;

    private final AnimationTimer painter = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (dirty) {
                dirty = false;
                render();
            }
        }
    };

    public TerminalView(double fontSize) {
        setFontSize(fontSize);
        getChildren().add(canvas);
        setFocusTraversable(true);
        canvas.setFocusTraversable(false);

        setOnMousePressed(e -> requestFocus());
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
        addEventFilter(ScrollEvent.SCROLL, this::onScroll);

        display.setOnRepaint(this::markDirty);
        // Visual bell. An audible one would mean java.awt.Toolkit.beep(), which initialises AWT —
        // see App.main for why that is off the table.
        display.setOnBell(() -> Platform.runLater(this::flashBell));
        display.setOnAlternateScreenChanged(() -> {
            // Scrollback belongs to the primary buffer; entering or leaving the alternate screen
            // must snap back to the live view or a full-screen program renders at an offset.
            scrollOrigin = 0;
            markDirty();
        });
    }

    /** Starts the shell. Must be called once, after the view is in a scene. */
    public void start() throws IOException {
        session = new TerminalSession(display, columns, rows);
        buffer = session.getTextBuffer();
        // Fires on the emulator thread for every buffer mutation — cheap by design.
        buffer.addModelListener(this::markDirty);
        session.setOnSessionEnded(() -> Platform.runLater(() -> {
            if (onSessionEnded != null) onSessionEnded.run();
        }));
        session.start();
        painter.start();
        markDirty();
    }

    private Runnable onSessionEnded;

    public void setOnSessionEnded(Runnable onSessionEnded) {
        this.onSessionEnded = onSessionEnded;
    }

    public FxTerminalDisplay getDisplay() {
        return display;
    }

    public TerminalSession getSession() {
        return session;
    }

    public void close() {
        painter.stop();
        if (session != null) session.close();
    }

    private void markDirty() {
        dirty = true;
    }

    /** Visual bell: a brief wash over the canvas, cleared by the next frame. */
    private void flashBell() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.color(1, 1, 1, 0.18));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        javafx.animation.PauseTransition clear =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(80));
        clear.setOnFinished(e -> markDirty());
        clear.play();
    }

    // ---------------------------------------------------------------- font & layout

    public void setFontSize(double size) {
        this.fontSize = Math.max(6, size);
        String family = pickMonospaceFamily();
        font = Font.font(family, this.fontSize);
        boldFont = Font.font(family, FontWeight.BOLD, this.fontSize);
        italicFont = Font.font(family, FontWeight.NORMAL, javafx.scene.text.FontPosture.ITALIC, this.fontSize);
        boldItalicFont = Font.font(family, FontWeight.BOLD, javafx.scene.text.FontPosture.ITALIC, this.fontSize);
        measureFont();
        markDirty();
        requestLayout();
    }

    /**
     * A real monospace face, preferred per platform, falling back to JavaFX's logical
     * "Monospaced" family, which always resolves to something fixed-pitch.
     */
    private static String pickMonospaceFamily() {
        List<String> families = Font.getFamilies();
        for (String candidate : List.of("JetBrains Mono", "SF Mono", "Menlo", "Cascadia Mono",
                "Consolas", "DejaVu Sans Mono", "Liberation Mono", "Ubuntu Mono", "Monospaced")) {
            if (families.contains(candidate)) return candidate;
        }
        return "Monospaced";
    }

    private void measureFont() {
        Text probe = new Text("M");
        probe.setFont(font);
        // Cell width is the advance of a single glyph. Valid only because the face is monospace;
        // it is why a proportional fallback would misalign every column.
        charWidth = probe.getLayoutBounds().getWidth();
        ascent = probe.getBaselineOffset();
        lineHeight = Math.ceil(fontSize * LINE_SPACING);
        if (charWidth <= 0) charWidth = fontSize * 0.6;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        canvas.setWidth(w);
        canvas.setHeight(h);
        canvas.relocate(0, 0);

        int newColumns = Math.max(MIN_COLUMNS, (int) Math.floor(w / charWidth));
        int newRows = Math.max(MIN_ROWS, (int) Math.floor(h / lineHeight));
        if (newColumns != columns || newRows != rows) {
            columns = newColumns;
            rows = newRows;
            if (session != null) session.resize(columns, rows);
        }
        render();
    }

    @Override
    protected double computePrefWidth(double height) {
        return charWidth * 80;
    }

    @Override
    protected double computePrefHeight(double width) {
        return lineHeight * 24;
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        g.setFontSmoothingType(FontSmoothingType.GRAY);
        g.setFill(AnsiPalette.DEFAULT_BACKGROUND);
        g.fillRect(0, 0, w, h);

        if (buffer == null) return;

        // The emulator writes into this buffer from its own thread; the lock is what makes a frame
        // internally consistent rather than a mix of two states.
        buffer.lock();
        try {
            buffer.processHistoryAndScreenLines(scrollOrigin, rows, new StyledTextConsumer() {
                @Override
                public void consume(int x, int y, TextStyle style, CharBuffer characters, int startRow) {
                    drawRun(g, x, y - startRow, style, characters);
                }

                @Override
                public void consumeNul(int x, int y, int nulIndex, TextStyle style,
                        CharBuffer characters, int startRow) {
                    // Cells past the last written column. Only the background is meaningful —
                    // drawing NUL as text would paint boxes across every short line.
                    drawBackground(g, x, y - startRow, characters.length(), style);
                }

                @Override
                public void consumeQueue(int x, int y, int nulIndex, int startRow) {}
            });
            drawCursor(g);
        } finally {
            buffer.unlock();
        }
    }

    private void drawBackground(GraphicsContext g, int column, int row, int length, TextStyle style) {
        Color bg = backgroundOf(style);
        if (bg.equals(AnsiPalette.DEFAULT_BACKGROUND)) return; // already cleared
        g.setFill(bg);
        g.fillRect(column * charWidth, row * lineHeight, length * charWidth, lineHeight);
    }

    private void drawRun(GraphicsContext g, int column, int row, TextStyle style, CharBuffer characters) {
        if (row < 0 || row >= rows) return;
        String text = characters.toString();
        if (text.isEmpty()) return;

        Color fg = foregroundOf(style);
        Color bg = backgroundOf(style);

        double x = column * charWidth;
        double y = row * lineHeight;
        double width = text.length() * charWidth;

        if (!bg.equals(AnsiPalette.DEFAULT_BACKGROUND)) {
            g.setFill(bg);
            g.fillRect(x, y, width, lineHeight);
        }

        if (style.hasOption(TextStyle.Option.HIDDEN)) return;

        g.setFont(fontFor(style));
        g.setFill(fg);
        g.setTextBaseline(VPos.BASELINE);
        drawGlyphs(g, text, x, y + ascent);

        if (style.hasOption(TextStyle.Option.UNDERLINED)) {
            double underlineY = Math.floor(y + ascent + Math.max(1, fontSize * 0.12)) + 0.5;
            g.setStroke(fg);
            g.setLineWidth(1);
            g.strokeLine(x, underlineY, x + width, underlineY);
        }
    }

    /**
     * Draws a style run onto the cell grid.
     *
     * <p>Every element of the buffer's char array is exactly one cell. A double-width character
     * (CJK, most emoji) therefore occupies two: the glyph itself, followed by {@link CharUtils#DWC}
     * — a private-use placeholder that must never be drawn. Rendering it produces a stray box or
     * bar between every pair of CJK characters, and drawing the run with one {@code fillText} also
     * lets the wide glyph's natural advance push the rest of the line off the grid.
     *
     * <p>The all-ASCII fast path is the one that matters for throughput: it is what almost every
     * run is, and it stays a single draw call.
     */
    private void drawGlyphs(GraphicsContext g, String text, double x, double baseline) {
        if (isPlainAscii(text)) {
            g.fillText(text, x, baseline);
            return;
        }
        int cell = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == CharUtils.DWC) {
                // Continuation of the preceding wide glyph: consumes a cell, draws nothing.
                cell++;
                i++;
                continue;
            }
            int codePoint = text.codePointAt(i);
            int slots = Character.charCount(codePoint);
            if (codePoint != 0 && c != ' ') {
                g.fillText(new String(Character.toChars(codePoint)), x + cell * charWidth, baseline);
            }
            cell += slots;
            i += slots;
        }
    }

    private static boolean isPlainAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    private Font fontFor(TextStyle style) {
        boolean bold = style.hasOption(TextStyle.Option.BOLD);
        boolean italic = style.hasOption(TextStyle.Option.ITALIC);
        if (bold && italic) return boldItalicFont;
        if (bold) return boldFont;
        if (italic) return italicFont;
        return font;
    }

    private Color foregroundOf(TextStyle style) {
        boolean inverse = style.hasOption(TextStyle.Option.INVERSE);
        Color fg = AnsiPalette.resolve(
                inverse ? style.getBackground() : style.getForeground(),
                inverse ? AnsiPalette.DEFAULT_BACKGROUND : AnsiPalette.DEFAULT_FOREGROUND);
        if (style.hasOption(TextStyle.Option.DIM)) fg = AnsiPalette.dim(fg, backgroundOf(style));
        return fg;
    }

    private Color backgroundOf(TextStyle style) {
        boolean inverse = style.hasOption(TextStyle.Option.INVERSE);
        return AnsiPalette.resolve(
                inverse ? style.getForeground() : style.getBackground(),
                inverse ? AnsiPalette.DEFAULT_FOREGROUND : AnsiPalette.DEFAULT_BACKGROUND);
    }

    private void drawCursor(GraphicsContext g) {
        if (!display.isCursorVisible()) return;
        // JediTerm reports the cursor row 1-based against the live screen; scrolling back moves it
        // out of view rather than dragging it along.
        int row = display.getCursorY() - 1 - scrollOrigin;
        int column = display.getCursorX();
        if (row < 0 || row >= rows || column < 0 || column >= columns) return;

        double x = column * charWidth;
        double y = row * lineHeight;
        CursorShape shape = display.getCursorShape();
        g.setFill(AnsiPalette.DEFAULT_FOREGROUND);

        switch (shape) {
            case BLINK_UNDERLINE, STEADY_UNDERLINE ->
                g.fillRect(x, y + lineHeight - 2, charWidth, 2);
            case BLINK_VERTICAL_BAR, STEADY_VERTICAL_BAR -> g.fillRect(x, y, 2, lineHeight);
            default -> {
                g.fillRect(x, y, charWidth, lineHeight);
                // Re-draw the glyph underneath in the background colour so a block cursor does not
                // hide the character it is sitting on.
                char ch = buffer.getCharAt(column, display.getCursorY() - 1);
                if (ch != '\0' && ch != ' ') {
                    g.setFill(AnsiPalette.DEFAULT_BACKGROUND);
                    g.setFont(font);
                    g.setTextBaseline(VPos.BASELINE);
                    g.fillText(String.valueOf(ch), x, y + ascent);
                }
            }
        }
    }

    // ---------------------------------------------------------------- input

    private void onKeyPressed(KeyEvent e) {
        if (session == null || !session.isRunning()) return;

        byte[] encoded = KeyEncoding.encodePressed(e, session::keyCode, altIsMeta);
        if (encoded != null) {
            snapToLive();
            session.send(encoded);
            e.consume();
        }
    }

    private void onKeyTyped(KeyEvent e) {
        if (session == null || !session.isRunning()) return;
        byte[] encoded = KeyEncoding.encodeTyped(e, altIsMeta);
        if (encoded != null) {
            snapToLive();
            session.send(encoded);
            e.consume();
        }
    }

    /** Any keystroke returns the view to the live screen, as every terminal does. */
    private void snapToLive() {
        if (scrollOrigin != 0) {
            scrollOrigin = 0;
            markDirty();
        }
    }

    private void onScroll(ScrollEvent e) {
        if (buffer == null) return;
        // No scrollback exists on the alternate screen; a full-screen program owns the viewport.
        if (display.isAlternateScreen()) return;

        int lines = (int) Math.signum(e.getDeltaY()) * 3;
        if (lines == 0) return;
        int history = buffer.getHistoryLinesCount();
        int next = Math.max(-history, Math.min(0, scrollOrigin - lines));
        if (next != scrollOrigin) {
            scrollOrigin = next;
            markDirty();
        }
        e.consume();
    }

    /** Pastes the clipboard, honouring bracketed-paste mode if the shell enabled it. */
    public void paste() {
        String text = Clipboard.getSystemClipboard().getString();
        if (text == null || text.isEmpty() || session == null) return;
        snapToLive();
        session.send(KeyEncoding.encodePaste(text, display.isBracketedPaste()));
    }

    public void setAltIsMeta(boolean altIsMeta) {
        this.altIsMeta = altIsMeta;
    }
}
