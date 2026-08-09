package com.termina.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
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

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.StyledTextConsumer;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.mouse.MouseEventProcessingSettings;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.SelectionUtil;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.util.CharUtils;
import com.termina.config.Settings;
import com.termina.pty.CwdWatcher;
import com.termina.pty.ProcessCwd;
import com.termina.term.TerminalSession;

import static com.termina.i18n.Messages.tr;

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

    /**
     * Scrollbar for the scrollback.
     *
     * <p>Its range is in <em>lines of history</em>, with the bottom of the track meaning the live
     * screen — the direction every terminal uses. Value therefore counts up as you scroll back:
     * {@code value = historyLines + scrollOrigin}, since scrollOrigin is 0 at the live screen and
     * negative going up.
     */
    private final javafx.scene.control.ScrollBar scrollBar = new javafx.scene.control.ScrollBar();

    /** Guards the value listener while the bar is being updated from the buffer, not the mouse. */
    private boolean updatingScrollBar;

    private boolean scrollBarEnabled = true;

    /**
     * Whether the press that began the current drag landed on the scrollbar.
     *
     * <p>Latched at the press rather than re-tested per event: what a drag reports as its target is
     * not something to depend on, and a drag that started on the thumb must stay the thumb's for as
     * long as the button is held — including when the pointer leaves the bar, which it does the
     * moment anyone drags at a natural angle.
     */
    private boolean pressedOnScrollBar;

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
                // The first repaint the shell caused, rather than the first frame of an empty grid:
                // a window is on screen well before there is a prompt in it, and timing to the empty
                // one would flatter the measurement by everything the shell takes to start.
                com.termina.perf.Startup.markFirstPaint();
            }
        }
    };

    public TerminalView(double fontSize) {
        applyFont();
        setFontSize(fontSize);
        scrollBar.setOrientation(javafx.geometry.Orientation.VERTICAL);
        scrollBar.getStyleClass().add("terminal-scroll-bar");
        scrollBar.setFocusTraversable(false);
        scrollBar.setVisible(false);
        scrollBar.setManaged(false);
        scrollBar.valueProperty().addListener((o, was, now) -> onScrollBarMoved(now.doubleValue()));
        getChildren().addAll(canvas, scrollBar);
        setFocusTraversable(true);
        canvas.setFocusTraversable(false);

        addEventFilter(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
        addEventFilter(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
        addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        setOnContextMenuRequested(this::onContextMenuRequested);

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
    private com.termina.pty.LaunchOptions launchOptions = com.termina.pty.LaunchOptions.DEFAULTS;

    /**
     * Options fixed at session start. Changing either afterwards cannot affect the running shell —
     * scrollback sizes a buffer that already exists, and the shell is already running — so the
     * settings UI says so rather than pretending otherwise.
     */
    public void setSessionOptions(int scrollbackLines, String shellOverride) {
        setSessionOptions(scrollbackLines, com.termina.pty.LaunchOptions.ofShell(shellOverride));
    }

    public void setSessionOptions(int scrollbackLines, com.termina.pty.LaunchOptions options) {
        this.scrollbackLines = scrollbackLines;
        this.launchOptions = options == null ? com.termina.pty.LaunchOptions.DEFAULTS : options;
    }

    /** Starts the shell. Must be called once, after the view is in a scene. */
    public void start() throws IOException {
        session = new TerminalSession(display, columns, rows, scrollbackLines, launchOptions);
        buffer = session.getTextBuffer();
        // Fires on the emulator thread for every buffer mutation — cheap by design.
        buffer.addModelListener(this::markDirty);
        buffer.addHistoryBufferListener(this::onHistoryLineCountChanged);
        session.setOnSessionEnded(() -> Platform.runLater(() -> {
            if (onSessionEnded != null) onSessionEnded.run();
        }));
        session.start();
        // Started here rather than inside the session: what the directory is *for* is a UI
        // question. The term layer reports the shell's state; it does not decide what a tab is
        // called. Skipped outright where the OS cannot answer, so no thread runs to find out.
        if (ProcessCwd.isSupported()) cwdWatch = CwdWatcher.watch(session.pid(), display::setCwd);
        painter.start();
        markDirty();
    }

    private CwdWatcher cwdWatch;

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
        // Before the session: the watcher polls by pid, and a pid is reused. Left running past the
        // process it was watching, it would eventually start reporting some unrelated process's
        // directory into a tab that no longer exists.
        if (cwdWatch != null) cwdWatch.close();
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
        javafx.animation.PauseTransition clear = new javafx.animation.PauseTransition(javafx.util.Duration.millis(80));
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
        styleScrollBar(palette);
        markDirty();
    }

    /**
     * Colours the scrollbar from the terminal palette rather than the chrome theme.
     *
     * <p>It sits inside the terminal, so a control-themed bar reads as a piece of the window that
     * wandered into the text. The colours are pushed as looked-up variables, because a thumb is a
     * child node an inline style on the bar cannot reach.
     */
    private void styleScrollBar(TerminalPalette palette) {
        Color fg = palette.foreground();
        scrollBar.setStyle("-terminal-thumb: " + web(fg, 0.35) + "; -terminal-thumb-hover: " + web(fg, 0.6) + ";");
    }

    private static String web(Color c, double alpha) {
        return "rgba(%d,%d,%d,%s)"
                .formatted(
                        Math.round(c.getRed() * 255),
                        Math.round(c.getGreen() * 255),
                        Math.round(c.getBlue() * 255),
                        alpha);
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

    /**
     * Shows or hides the scrollbar. This changes how many columns fit, so the shell is resized —
     * the bar takes real width rather than floating over the text, where it would cover the last
     * column of every line.
     */
    public void setScrollBarEnabled(boolean enabled) {
        if (scrollBarEnabled == enabled) return;
        scrollBarEnabled = enabled;
        requestLayout();
        markDirty();
    }

    private void onScrollBarMoved(double value) {
        if (updatingScrollBar || buffer == null) return;
        scrollOrigin = ScrollBarModel.origin(buffer.getHistoryLinesCount(), value);
        markDirty();
    }

    /** Reflects the buffer into the scrollbar. Cheap: property setters no-op on an equal value. */
    private void updateScrollBar() {
        if (buffer == null) return;
        int history = buffer.getHistoryLinesCount();
        // Nothing to scroll on the alternate screen — a full-screen program owns the viewport and
        // has no scrollback of its own.
        boolean useful = ScrollBarModel.useful(scrollBarEnabled, history, display.isAlternateScreen());
        if (scrollBar.isManaged() != useful) {
            scrollBar.setVisible(useful);
            scrollBar.setManaged(useful);
            // Appearing or vanishing changes the width available for columns.
            requestLayout();
        }
        if (!useful) return;
        updatingScrollBar = true;
        try {
            scrollBar.setMin(0);
            scrollBar.setMax(history);
            scrollBar.setVisibleAmount(rows);
            scrollBar.setValue(ScrollBarModel.value(history, scrollOrigin));
        } finally {
            updatingScrollBar = false;
        }
    }

    /** State of the scrollbar, so a capture run can assert on it rather than eyeball the thumb. */
    public String scrollBarReport() {
        return "shown=" + scrollBar.isManaged()
                + " value=" + Math.round(scrollBar.getValue())
                + " max=" + Math.round(scrollBar.getMax())
                + " thumb=" + Math.round(scrollBar.getVisibleAmount())
                + " w=" + Math.round(scrollBarWidth())
                + " cols=" + columns
                + " origin=" + scrollOrigin;
    }

    /** The bar itself, so a capture run can aim events at it the way a real mouse does. */
    public javafx.scene.Node scrollBarNode() {
        return scrollBar;
    }

    /** Moves the bar as a drag would, going through the same listener the mouse uses. */
    public void setScrollBarValue(double value) {
        scrollBar.setValue(value);
    }

    /** Width the scrollbar takes from the terminal, or 0 when it is not shown. */
    private double scrollBarWidth() {
        return scrollBar.isManaged() ? scrollBar.prefWidth(-1) : 0;
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
        double barWidth = scrollBarWidth();
        canvas.setWidth(Math.max(0, w - barWidth));
        canvas.setHeight(h);
        canvas.relocate(0, 0);
        if (barWidth > 0) scrollBar.resizeRelocate(w - barWidth, 0, barWidth, h);

        int newColumns = Math.max(MIN_COLUMNS, (int) Math.floor((w - barWidth) / charWidth));
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
            // x arrives as an index into the line's char[], which is not its column: see
            // RunColumns. Translating here rather than inside drawRun keeps every consumer
            // callback on the same footing, including the background-only one.
            RunColumns columns = new RunColumns();
            buffer.processHistoryAndScreenLines(scrollOrigin, rows, new StyledTextConsumer() {
                @Override
                public void consume(int x, int y, TextStyle style, CharBuffer characters, int startRow) {
                    drawRun(g, columns.columnOf(y, x, characters), y - startRow, style, characters);
                }

                @Override
                public void consumeNul(
                        int x, int y, int nulIndex, TextStyle style, CharBuffer characters, int startRow) {
                    // Cells past the last written column. Only the background is meaningful —
                    // drawing NUL as text would paint boxes across every short line.
                    drawBackground(g, columns.columnOf(y, x, characters), y - startRow, characters.length(), style);
                }

                @Override
                public void consumeQueue(int x, int y, int nulIndex, int startRow) {}
            });
            drawSelection(g);
            drawCursor(g);
            updateScrollBar();
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
        // Cells, not characters: an astral code point is two array slots and one column, so a run
        // holding one would otherwise be painted a column too wide.
        double width = CellWidth.cells(text) * charWidth;

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
     * <p>Array slots are not columns, in either direction: a double-width character
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
            // One column per code point, whatever its UTF-16 length. Advancing by the number of
            // array slots instead gave every astral character — every Nerd Font icon in an `ls`
            // listing — a second, empty column, pushing the rest of the line out of alignment with
            // the lines around it.  A genuinely double-width glyph gets its second column from the
            // DWC placeholder above, not from here.
            cell++;
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
        // Escape closes the context menu rather than reaching the shell. This filter runs before
        // the menu would see the key, and Escape now encodes to a real byte, so without this the
        // menu stays open and the shell gets an ESC nobody asked for.
        if (contextMenu != null && contextMenu.isShowing()) {
            if (e.getCode() == KeyCode.ESCAPE) {
                contextMenu.hide();
                e.consume();
                return;
            }
        }
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

    /**
     * The buffer cell under a mouse position, clamped to the grid.
     *
     * <p>The x it returns is a <b>slot</b>, not the column the pointer is over: a selection is
     * handed to JediTerm, which indexes the line's array. The two differ by one for every astral
     * character to the left — a Nerd Font icon before each name in an `ls` listing — so without
     * the translation a drag selects text a column off from the one it highlighted.
     *
     * <p>Mouse <i>reporting</i> is the opposite case and deliberately does not come through here:
     * xterm's protocol speaks columns, and a program being told where its own cursor should go
     * wants what the user pointed at.
     */
    private Point cellAt(MouseEvent e) {
        int column = (int) Math.floor(e.getX() / charWidth);
        int row = (int) Math.floor(e.getY() / lineHeight);
        column = Math.max(0, Math.min(columns, column));
        row = Math.max(0, Math.min(rows - 1, row));
        int line = scrollOrigin + row;
        return new Point(ColumnMap.slotAt(lineTextAt(line), column), line);
    }

    /** A row's raw characters, or empty when it is not there. Callers already hold the lock. */
    private String lineTextAt(int row) {
        if (buffer == null) return "";
        try {
            var line = buffer.getLine(row);
            return line == null ? "" : line.getText();
        } catch (RuntimeException e) {
            // Out of range: the row scrolled away between the event and this read.
            return "";
        }
    }

    /** Slots in a row — the bound a selection clamps to, which is not the column count. */
    private int slotsInRow(int row) {
        return Math.max(columns, lineTextAt(row).length());
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
        if (pressedOnScrollBar) {
            pressedOnScrollBar = false;
            return;
        }
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.RELEASED)) e.consume();
    }

    // ---------------------------------------------------------------- context menu

    private ContextMenu contextMenu;
    private MenuItem copyItem;
    private MenuItem pasteItem;
    private CheckMenuItem menuBarItem;
    private Runnable onOpenSettings = () -> {};
    private Runnable onNewTab = () -> {};
    private Runnable onNewWindow = () -> {};
    private ZoomMenuRow.Actions zoomActions;
    private ZoomMenuRow zoomRow;
    private BooleanSupplier menuBarShown;
    private Runnable onToggleMenuBar;

    /**
     * Menu actions the view cannot perform itself. A terminal knows nothing about tabs, windows or
     * preferences — those belong to the window that owns it — so they arrive as callbacks.
     */
    public void setOnOpenSettings(Runnable onOpenSettings) {
        this.onOpenSettings = onOpenSettings == null ? () -> {} : onOpenSettings;
    }

    public void setOnNewTab(Runnable onNewTab) {
        this.onNewTab = onNewTab == null ? () -> {} : onNewTab;
    }

    public void setOnNewWindow(Runnable onNewWindow) {
        this.onNewWindow = onNewWindow == null ? () -> {} : onNewWindow;
    }

    /** Zoom is the window's, not the view's: it writes through the settings, which every tab shares. */
    public void setZoomActions(ZoomMenuRow.Actions zoomActions) {
        this.zoomActions = zoomActions;
    }

    /**
     * Offers the menu bar as a checkbox in the right-click menu, reading and writing the same
     * setting the View menu does.
     *
     * <p>Both halves in one call so they cannot be wired half-way — a checkbox that toggles but
     * never reflects, or reflects but never toggles, looks like it works. Left unwired the item is
     * omitted entirely, which is how macOS gets no entry: the menus are in the screen menu bar and
     * there is nothing in the window to show or hide.
     */
    public void setMenuBarToggle(BooleanSupplier shown, Runnable toggle) {
        this.menuBarShown = shown;
        this.onToggleMenuBar = toggle;
    }

    /**
     * Decides between the context menu and the running program.
     *
     * <p>Right-click is genuinely contested: a terminal wants it for Copy and Paste, while a
     * mouse-aware TUI (Midnight Commander, some file pickers) wants it as button 2 — and we report
     * it as such. The rule is the one that already governs selection: while a program has grabbed
     * the mouse it wins, and <b>Shift forces the menu</b>. Without that escape hatch there is no way
     * to copy anything, or reach Settings, from inside such a program.
     */
    /**
     * Whether a context-menu request should open the menu rather than belong to the running program.
     *
     * <p>Extracted and static because it is the whole of the contention rule, and it is the kind of
     * three-way condition that is easy to get backwards and impossible to notice: getting it wrong
     * either makes right-click dead inside every TUI, or makes Copy unreachable from one.
     *
     * @param keyboardTrigger the menu key rather than a click — never contested, since nothing was
     *     reported to the program and the user asked for a menu explicitly
     */
    static boolean shouldShowMenu(MouseMode mode, boolean shift, boolean keyboardTrigger) {
        if (keyboardTrigger || shift) return true;
        return mode == MouseMode.MOUSE_REPORTING_NONE;
    }

    private void onContextMenuRequested(ContextMenuEvent e) {
        // ContextMenuEvent carries no modifier state — only coordinates and whether a key raised
        // it — so shift is read from the press that triggered it.
        if (!shouldShowMenu(display.getMouseMode(), shiftOnLastPress, e.isKeyboardTrigger())) return;

        if (contextMenu == null) contextMenu = buildContextMenu();
        // Refreshed per show: the menu is built once and reused, while everything it reports on
        // moves underneath it. Copy and Paste depend on the selection and the clipboard; the
        // menu-bar checkbox on a setting the View menu, its own chord and the settings window can
        // all change while this menu sits built and invisible.
        copyItem.setDisable(!hasSelection());
        pasteItem.setDisable(!Clipboard.getSystemClipboard().hasString());
        if (menuBarItem != null) menuBarItem.setSelected(menuBarShown.getAsBoolean());
        if (zoomRow != null) zoomRow.refresh();
        contextMenu.show(this, e.getScreenX(), e.getScreenY());
        e.consume();
    }

    /**
     * Shows the menu as a right-click would and returns its scene, or null when the running program
     * owns the mouse and shift was not held. For the development capture hook — a ContextMenu is a
     * popup with its own scene, so the main window's snapshot cannot show it.
     */
    public javafx.scene.Scene showContextMenuForCapture(double screenX, double screenY, boolean shift) {
        shiftOnLastPress = shift;
        ContextMenuEvent event =
                new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, screenX, screenY, false, null);
        onContextMenuRequested(event);
        return contextMenu != null && contextMenu.isShowing() ? contextMenu.getScene() : null;
    }

    /** Whether the context menu is open — for the development capture hook. */
    public boolean isContextMenuShowing() {
        return contextMenu != null && contextMenu.isShowing();
    }

    /**
     * Fires the context-menu item with this label, and reports whether one was found. For the
     * development capture hook: a menu item that renders correctly and does nothing when clicked
     * looks exactly like one that works, and every item here delegates to a callback that could be
     * left unwired.
     */
    public boolean fireContextMenuItemForCapture(String label) {
        if (contextMenu == null) contextMenu = buildContextMenu();
        for (MenuItem item : contextMenu.getItems()) {
            if (label.equals(item.getText())) {
                item.fire();
                return true;
            }
        }
        return false;
    }

    private ContextMenu buildContextMenu() {
        copyItem = item(tr("menu.copy"), MenuIcons.copy(), this::copySelection);
        pasteItem = item(tr("menu.paste"), MenuIcons.paste(), this::paste);

        List<MenuItem> items = new ArrayList<>(List.of(
                item(tr("menu.newTab"), MenuIcons.newTab(), () -> onNewTab.run()),
                item(tr("menu.newWindow"), MenuIcons.newWindow(), () -> onNewWindow.run()),
                new SeparatorMenuItem(),
                copyItem,
                pasteItem,
                item(tr("menu.selectAll"), MenuIcons.selectAll(), this::selectAll),
                new SeparatorMenuItem(),
                item(tr("menu.clearScrollback"), MenuIcons.clear(), this::clearScrollback)));

        if (zoomActions != null) {
            // The same row the View menu carries, built again: a Node cannot be in two menus.
            zoomRow = new ZoomMenuRow(zoomActions);
            items.add(new SeparatorMenuItem());
            items.add(zoomRow.item());
        }

        if (onToggleMenuBar != null && menuBarShown != null) {
            // "Show Menu Bar", checked — not the View menu's "Hide Menu Bar". The two menus are
            // reached from different places: the View menu is inside the bar, so it can only ever
            // be opened while the bar is showing and "Hide" is the only move it has. This menu is
            // the way back once the bar is gone, so it has to state which of the two states you
            // are in rather than name a direction.
            //
            // No leading glyph, against the convention the rest of these follow: a CheckMenuItem
            // draws its tick in the graphic column, and an icon there either fights it or hides it.
            menuBarItem = new CheckMenuItem(tr("menu.showMenuBar"));
            menuBarItem.setOnAction(e -> onToggleMenuBar.run());
            items.add(new SeparatorMenuItem());
            items.add(menuBarItem);
        }

        items.add(new SeparatorMenuItem());
        items.add(item(tr("menu.settings"), MenuIcons.settings(), () -> onOpenSettings.run()));

        ContextMenu menu = new ContextMenu(items.toArray(MenuItem[]::new));
        menu.setAutoHide(true);
        return menu;
    }

    private static MenuItem item(String text, Node icon, Runnable action) {
        MenuItem menuItem = new MenuItem(text, icon);
        menuItem.setOnAction(e -> action.run());
        return menuItem;
    }

    /** Selects the whole buffer: all of the scrollback plus the live screen. */
    public void selectAll() {
        if (buffer == null) return;
        buffer.lock();
        try {
            int history = buffer.getHistoryLinesCount();
            TerminalSelection all = new TerminalSelection(new Point(0, -history));
            all.updateEnd(new Point(slotsInRow(rows - 1), rows - 1));
            selection = all;
            selectionAnchor = new Point(0, -history);
        } finally {
            buffer.unlock();
        }
        markDirty();
    }

    /**
     * Drops the scrollback while keeping the visible screen — clearing history without losing the
     * prompt you are standing on.
     */
    public void clearScrollback() {
        if (buffer == null) return;
        buffer.lock();
        try {
            buffer.clearHistory();
        } finally {
            buffer.unlock();
        }
        // Everything measured against the history that just went away has to be reset with it, or
        // the viewport scrolls into rows that no longer exist.
        selection = null;
        selectionAnchor = null;
        scrollOrigin = 0;
        knownHistoryLines = 0;
        markDirty();
    }

    /** Shift state of the most recent press, for the context-menu event that may follow it. */
    private boolean shiftOnLastPress;

    /** Whether an event is headed for the scrollbar, i.e. the bar or one of its skin's parts. */
    private boolean fromScrollBar(MouseEvent e) {
        javafx.scene.Node node = e.getTarget() instanceof javafx.scene.Node n ? n : null;
        for (; node != null; node = node.getParent()) {
            if (node == scrollBar) return true;
        }
        return false;
    }

    private void onMousePressed(MouseEvent e) {
        // A click anywhere in the terminal dismisses the menu instead of acting on the terminal.
        // ContextMenu's own auto-hide covers clicks outside the window, but this filter runs first
        // for clicks inside it, which would otherwise start a selection under the open menu.
        if (contextMenu != null && contextMenu.isShowing()) {
            contextMenu.hide();
            e.consume();
            return;
        }
        // The mouse filters are on this Region, and a filter runs in the capturing phase — so a
        // press on the scrollbar reaches the terminal *before* the bar itself. Without this the
        // terminal starts a selection under every scroll drag.
        pressedOnScrollBar = fromScrollBar(e);
        if (pressedOnScrollBar) return; // deliberately not consumed: the bar still needs it

        requestFocus();
        shiftOnLastPress = e.isShiftDown();
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
        if (pressedOnScrollBar) return;
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
        line.updateEnd(new Point(slotsInRow(at.y), at.y));
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
            int line = scrollOrigin + row;
            kotlin.Pair<Integer, Integer> span = current.intersect(0, line, slotsInRow(line));
            if (span == null) continue;
            int from = span.getFirst();
            int length = span.getSecond();
            if (length <= 0) continue;
            // The span is in slots; the rectangle is in columns.
            String text = lineTextAt(line);
            double x = ColumnMap.columnAt(text, from);
            double to = ColumnMap.columnAt(text, from + length);
            g.fillRect(x * charWidth, row * lineHeight, (to - x) * charWidth, lineHeight);
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
