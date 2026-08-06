package com.termina.ui;

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.StyledTextConsumer;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.mouse.MouseEventProcessingSettings;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.SelectionUtil;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.util.CharUtils;
import com.termina.config.Settings;
import com.termina.term.TerminalSession;
import java.io.IOException;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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

    /** How opaque the selection wash is over the text beneath it. */
    private static final double SELECTION_ALPHA = 0.30;

    private final Canvas canvas = new Canvas();
    private final FxTerminalDisplay display = new FxTerminalDisplay();

    private TerminalSession session;
    private TerminalTextBuffer buffer;

    /**
     * Theme colours. Held rather than read from a constant so a theme change repaints in place —
     * see {@link #setPalette}.
     */
    private TerminalPalette palette = Theme.EDITORA_DARK.palette();

    /**
     * Selection highlight, painted over the text rather than behind it.
     *
     * <p>Behind would mean threading selection state through every style run and splitting runs at
     * its edges. A translucent wash keeps the run loop untouched and stays legible over any
     * foreground colour, which matters in a terminal where a cell can be any of 16 million. Derived
     * from the palette's blue so it suits a light theme as well as a dark one.
     */
    private Color selectionWash = washFor(Theme.EDITORA_DARK.palette());

    /** Preferred cursor shape. A program can still override it with DECSCUSR while running. */
    private Settings.CursorShape preferredCursor = Settings.CursorShape.BLOCK;

    private boolean bellEnabled = true;

    private String fontFamily = MonospaceFonts.available().get(0);

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
     *
     * <p>Volatile because the history listener adjusts it from the emulator thread — see
     * {@link #onHistoryLineCountChanged()}.
     */
    private volatile int scrollOrigin;

    /**
     * The active selection, in buffer coordinates: x is a column, y is a row where 0 is the top of
     * the live screen and negative reaches into history — the same axis as {@link #scrollOrigin}.
     *
     * <p>Volatile because the emulator reads it through {@code TerminalDisplay.getSelection()} on
     * its own thread while the mouse mutates it on the FX thread.
     */
    private volatile TerminalSelection selection;

    /** Where the current drag began, so a backwards drag extends rather than restarts. */
    private Point selectionAnchor;

    /** Lines already in history, to derive the delta the history listener does not supply. */
    private int knownHistoryLines;

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
        applyFont();
        setFontSize(fontSize);
        getChildren().add(canvas);
        setFocusTraversable(true);
        canvas.setFocusTraversable(false);

        addEventFilter(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
        addEventFilter(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
        addEventFilter(ScrollEvent.SCROLL, this::onScroll);

        display.setOnRepaint(this::markDirty);
        display.setSelectionSupplier(() -> selection);
        // Visual bell. An audible one would mean java.awt.Toolkit.beep(), which initialises AWT —
        // see App.main for why that is off the table.
        display.setOnBell(() -> {
            if (bellEnabled) Platform.runLater(this::flashBell);
        });
        display.setOnAlternateScreenChanged(() -> {
            // Scrollback belongs to the primary buffer; entering or leaving the alternate screen
            // must snap back to the live view or a full-screen program renders at an offset.
            scrollOrigin = 0;
            markDirty();
        });
    }

    private int scrollbackLines = Settings.DEFAULT_SCROLLBACK;
    private String shellOverride = "";

    /**
     * Options fixed at session start. Changing either afterwards cannot affect the running shell —
     * scrollback sizes a buffer that already exists, and the shell is already running — so the
     * settings UI says so rather than pretending otherwise.
     */
    public void setSessionOptions(int scrollbackLines, String shellOverride) {
        this.scrollbackLines = scrollbackLines;
        this.shellOverride = shellOverride == null ? "" : shellOverride;
    }

    /** Starts the shell. Must be called once, after the view is in a scene. */
    public void start() throws IOException {
        session = new TerminalSession(display, columns, rows, scrollbackLines, shellOverride);
        buffer = session.getTextBuffer();
        // Fires on the emulator thread for every buffer mutation — cheap by design.
        buffer.addModelListener(this::markDirty);
        buffer.addHistoryBufferListener(this::onHistoryLineCountChanged);
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
        applyFont();
    }

    /**
     * Sets the face. Resolved through {@link MonospaceFonts} so a family that is blank, or was
     * chosen on a machine that had it installed, falls back rather than silently rendering the grid
     * in a proportional face.
     */
    public void setFontFamily(String family) {
        this.fontFamily = MonospaceFonts.resolve(family);
        applyFont();
    }

    public double getFontSize() {
        return fontSize;
    }

    private void applyFont() {
        String family = fontFamily;
        font = Font.font(family, fontSize);
        boldFont = Font.font(family, FontWeight.BOLD, fontSize);
        italicFont = Font.font(family, FontWeight.NORMAL, javafx.scene.text.FontPosture.ITALIC, fontSize);
        boldItalicFont = Font.font(family, FontWeight.BOLD, javafx.scene.text.FontPosture.ITALIC, fontSize);
        measureFont();
        markDirty();
        requestLayout();
    }

    /** Applies a theme's colours and repaints. */
    public void setPalette(TerminalPalette palette) {
        this.palette = palette;
        this.selectionWash = washFor(palette);
        markDirty();
    }

    private static Color washFor(TerminalPalette palette) {
        Color blue = palette.ansi()[4];
        return Color.color(blue.getRed(), blue.getGreen(), blue.getBlue(), SELECTION_ALPHA);
    }

    public void setPreferredCursor(Settings.CursorShape shape) {
        this.preferredCursor = shape == null ? Settings.CursorShape.BLOCK : shape;
        markDirty();
    }

    public void setBellEnabled(boolean bellEnabled) {
        this.bellEnabled = bellEnabled;
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
        g.setFill(palette.background());
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
            drawSelection(g);
            drawCursor(g);
        } finally {
            buffer.unlock();
        }
    }

    private void drawBackground(GraphicsContext g, int column, int row, int length, TextStyle style) {
        Color bg = backgroundOf(style);
        if (bg.equals(palette.background())) return; // already cleared
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

        if (!bg.equals(palette.background())) {
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
        Color fg = palette.resolve(
                inverse ? style.getBackground() : style.getForeground(),
                inverse ? palette.background() : palette.foreground());
        if (style.hasOption(TextStyle.Option.DIM)) fg = TerminalPalette.dim(fg, backgroundOf(style));
        return fg;
    }

    private Color backgroundOf(TextStyle style) {
        boolean inverse = style.hasOption(TextStyle.Option.INVERSE);
        return palette.resolve(
                inverse ? style.getForeground() : style.getBackground(),
                inverse ? palette.foreground() : palette.background());
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
        g.setFill(palette.foreground());

        switch (effectiveCursorShape()) {
            case UNDERLINE -> g.fillRect(x, y + lineHeight - 2, charWidth, 2);
            case BAR -> g.fillRect(x, y, 2, lineHeight);
            default -> {
                g.fillRect(x, y, charWidth, lineHeight);
                // Re-draw the glyph underneath in the background colour so a block cursor does not
                // hide the character it is sitting on.
                char ch = buffer.getCharAt(column, display.getCursorY() - 1);
                if (ch != '\0' && ch != ' ') {
                    g.setFill(palette.background());
                    g.setFont(font);
                    g.setTextBaseline(VPos.BASELINE);
                    g.fillText(String.valueOf(ch), x, y + ascent);
                }
            }
        }
    }

    /**
     * The shape to draw: whatever a program asked for, else the user's preference.
     *
     * <p>A program's request wins because it is usually communicating mode — vim's insert-mode bar
     * against its normal-mode block — which the user's default should not override.
     */
    private Settings.CursorShape effectiveCursorShape() {
        CursorShape requested = display.getCursorShape();
        if (requested == null) return preferredCursor;
        return switch (requested) {
            case BLINK_UNDERLINE, STEADY_UNDERLINE -> Settings.CursorShape.UNDERLINE;
            case BLINK_VERTICAL_BAR, STEADY_VERTICAL_BAR -> Settings.CursorShape.BAR;
            default -> Settings.CursorShape.BLOCK;
        };
    }

    // ---------------------------------------------------------------- input

    private void onKeyPressed(KeyEvent e) {
        if (session == null || !session.isRunning()) return;

        byte[] encoded = KeyEncoding.encodePressed(e, session::keyCode, altIsMeta);
        if (encoded != null) {
            snapToLive();
            clearSelection();
            session.send(encoded);
            e.consume();
        }
    }

    private void onKeyTyped(KeyEvent e) {
        if (session == null || !session.isRunning()) return;
        byte[] encoded = KeyEncoding.encodeTyped(e, altIsMeta);
        if (encoded != null) {
            snapToLive();
            clearSelection();
            session.send(encoded);
            e.consume();
        }
    }

    // ---------------------------------------------------------------- selection

    /**
     * Keeps the viewport and any selection pinned to their text as lines age out of the screen and
     * into history.
     *
     * <p>Called on the emulator thread. The listener carries no delta, so it is derived from the
     * history line count. Without this, output arriving while the user is scrolled back slides the
     * text under them, and a selection made moments earlier silently comes to refer to different
     * characters — which matters because copying it would then produce the wrong text.
     */
    private void onHistoryLineCountChanged() {
        if (buffer == null) return;
        int now = buffer.getHistoryLinesCount();
        int delta = now - knownHistoryLines;
        knownHistoryLines = now;
        if (delta <= 0) return;

        // At the live view (origin 0) staying pinned to the bottom is what is wanted, so only a
        // scrolled-back viewport moves.
        if (scrollOrigin < 0) scrollOrigin = Math.max(-now, scrollOrigin - delta);

        TerminalSelection current = selection;
        if (current != null) current.shiftY(-delta);
        markDirty();
    }

    /** The buffer cell under a mouse position, clamped to the grid. */
    private Point cellAt(MouseEvent e) {
        int column = (int) Math.floor(e.getX() / charWidth);
        int row = (int) Math.floor(e.getY() / lineHeight);
        column = Math.max(0, Math.min(columns, column));
        row = Math.max(0, Math.min(rows - 1, row));
        return new Point(column, scrollOrigin + row);
    }

    // ---------------------------------------------------------------- mouse reporting

    /**
     * Offers the event to the program running in the terminal.
     *
     * <p>Returns true when it was reported, in which case the local gesture (selection, scrollback)
     * must not also run — otherwise a click in vim both moves its cursor and starts a highlight.
     *
     * <p><b>Shift bypasses reporting entirely.</b> That is the xterm convention and it is the only
     * way out of a full-screen program that has grabbed the mouse: without it, an application like
     * htop makes the text impossible to select.
     */
    private boolean reportMouse(MouseEvent e, com.jediterm.core.input.MouseEvent.Type type) {
        if (session == null || e.isShiftDown()) return false;
        int code = MouseEncoding.buttonCode(e.getButton());
        var event = new com.jediterm.core.input.MouseEvent(type, code, MouseEncoding.modifierFlags(e));
        return session.getTerminal().onMouseEvent(column(e), visibleRow(e), event, mouseSettings());
    }

    private boolean reportWheel(ScrollEvent e) {
        if (session == null || e.isShiftDown()) return false;
        int code = MouseEncoding.wheelButtonCode(e.getDeltaY());
        var event = new com.jediterm.core.input.MouseWheelEvent(
                code, MouseEncoding.modifierFlags(e), MouseEncoding.unitsToScroll(e.getDeltaY(), lineHeight));
        int column = Math.max(0, Math.min(columns - 1, (int) Math.floor(e.getX() / charWidth)));
        int row = Math.max(0, Math.min(rows - 1, (int) Math.floor(e.getY() / lineHeight)));
        return session.getTerminal().onMouseEvent(column, row, event, mouseSettings());
    }

    private MouseEventProcessingSettings mouseSettings() {
        // The third flag turns a wheel scroll into arrow keys while on the alternate screen, so
        // less and man scroll with the wheel even though they never enable mouse reporting.
        return new MouseEventProcessingSettings(true, display.isAlternateScreen(), true);
    }

    private int column(MouseEvent e) {
        return Math.max(0, Math.min(columns - 1, (int) Math.floor(e.getX() / charWidth)));
    }

    /** Row relative to the visible screen — what the reporting protocol addresses. */
    private int visibleRow(MouseEvent e) {
        return Math.max(0, Math.min(rows - 1, (int) Math.floor(e.getY() / lineHeight)));
    }

    private void onMouseReleased(MouseEvent e) {
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.RELEASED)) e.consume();
    }

    private void onMousePressed(MouseEvent e) {
        requestFocus();
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.PRESSED)) {
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY || buffer == null) return;

        Point at = cellAt(e);
        switch (e.getClickCount()) {
            case 2 -> selectWordAt(at);
            case 3 -> selectLineAt(at);
            default -> {
                // A press with no drag clears the selection, matching every terminal: it is how
                // you dismiss a highlight without copying it.
                selectionAnchor = at;
                selection = null;
            }
        }
        markDirty();
    }

    private void onMouseDragged(MouseEvent e) {
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.DRAGGED)) {
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY || selectionAnchor == null) return;
        Point at = cellAt(e);
        TerminalSelection current = selection;
        if (current == null) {
            // Only on the first drag event, so a plain click never leaves an empty selection.
            if (at.x == selectionAnchor.x && at.y == selectionAnchor.y) return;
            current = new TerminalSelection(new Point(selectionAnchor.x, selectionAnchor.y));
            selection = current;
        }
        current.updateEnd(at);
        markDirty();
    }

    /**
     * The selection covering the word under {@code at}.
     *
     * <p>Package-visible and static so it can be tested against a plain buffer — the boundary
     * arithmetic below is exactly the kind that looks right and is wrong by one.
     *
     * <p>{@code getNextSeparator} returns the <em>last character of the word</em>, while a
     * selection's end is exclusive. Passing it through unadjusted drops the final character, so
     * double-clicking "india" yields "indi" — which looks like working selection until someone
     * pastes it.
     */
    static TerminalSelection wordSelection(Point at, TerminalTextBuffer buffer) {
        buffer.lock();
        try {
            Point start = SelectionUtil.getPreviousSeparator(at, buffer);
            Point lastChar = SelectionUtil.getNextSeparator(at, buffer);
            TerminalSelection word = new TerminalSelection(new Point(start.x, start.y));
            word.updateEnd(new Point(lastChar.x + 1, lastChar.y));
            return word;
        } finally {
            buffer.unlock();
        }
    }

    private void selectWordAt(Point at) {
        TerminalSelection word = wordSelection(at, buffer);
        selection = word;
        selectionAnchor = new Point(word.getStart().x, word.getStart().y);
    }

    private void selectLineAt(Point at) {
        TerminalSelection line = new TerminalSelection(new Point(0, at.y));
        line.updateEnd(new Point(columns, at.y));
        selection = line;
        selectionAnchor = new Point(0, at.y);
    }

    public boolean hasSelection() {
        return selection != null;
    }

    /** Copies the selection, returning false when there was nothing to copy. */
    public boolean copySelection() {
        TerminalSelection current = selection;
        if (current == null || buffer == null) return false;

        String text;
        buffer.lock();
        try {
            // JediTerm's extractor, not ours: it understands that a wrapped line is one logical
            // line and must not gain a newline in the middle.
            text = SelectionUtil.getSelectionText(current, buffer);
        } finally {
            buffer.unlock();
        }
        if (text == null || text.isEmpty()) return false;

        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }

    public void clearSelection() {
        if (selection != null) {
            selection = null;
            markDirty();
        }
    }

    /** Paints the selection over the text as a translucent wash. */
    private void drawSelection(GraphicsContext g) {
        TerminalSelection current = selection;
        if (current == null) return;
        g.setFill(selectionWash);
        for (int row = 0; row < rows; row++) {
            kotlin.Pair<Integer, Integer> span = current.intersect(0, scrollOrigin + row, columns);
            if (span == null) continue;
            int from = span.getFirst();
            int length = span.getSecond();
            if (length <= 0) continue;
            g.fillRect(from * charWidth, row * lineHeight, length * charWidth, lineHeight);
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
        if (reportWheel(e)) {
            e.consume();
            return;
        }
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
