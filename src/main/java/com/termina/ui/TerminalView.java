package com.termina.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
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
import javafx.util.Duration;

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
import com.termina.link.LinkGesture;
import com.termina.link.LinkMatch;
import com.termina.link.LinkScanner;
import com.termina.pty.CwdWatcher;
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

    private String fontFamily = MonospaceFonts.defaultFamily();

    private Font font;
    private Font boldFont;
    private Font italicFont;
    private Font boldItalicFont;
    /** Faces for private-use code points, null when the terminal face should draw them: see IconFont. */
    private Font iconFont;

    private Font boldIconFont;
    private Font italicIconFont;
    private Font boldItalicIconFont;
    /**
     * The bundled icon face sized from the cell (see {@link IconFont#iconSize}), and how far below
     * the text baseline to draw it so it sits centred in the row. Null when the icons come from a
     * Nerd build of the terminal face, whose icons are already drawn to its cell.
     */
    private Font scaledIconFont;

    private double scaledIconShift;
    private double fontSize;
    private double charWidth;
    private double lineHeight;
    private double ascent;

    /**
     * Rows scrolled back. 0 is the live screen; negative values reach into history. Named after
     * JediTerm's own term for the coordinate {@code processHistoryAndScreenLines} expects.
     *
     * <p>Volatile because the model listener adjusts it from the emulator thread — see
     * {@link #onModelChanged()}.
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

    /**
     * How far the text moved on each change, so the selection and viewport can follow it. When the
     * history goes, everything measured against it goes too, or the viewport scrolls into rows
     * that no longer exist.
     */
    private final HistoryFollow follow = new HistoryFollow(() -> {
        selection = null;
        scrollOrigin = 0;
    });

    /** Fractions of a line the wheel has scrolled but not yet shown. */
    private final WheelScroll wheel = new WheelScroll();

    private volatile boolean dirty = true;
    private int columns = 80;
    private int rows = 24;

    /**
     * Holds the terminal resize back until the window has stopped moving.
     *
     * <p>The canvas follows every layout pulse — the window must look live while it is dragged —
     * but reflowing the whole scrollback under the buffer lock and ioctl-ing the PTY on every pulse
     * is work the shell cannot use, since it redraws for the last size only. Each change restarts
     * the pause; the handler reads {@link #columns} and {@link #rows} when it fires, so whatever
     * size the drag ended at is the one applied.
     */
    private final PauseTransition resizeSettle = new PauseTransition(Duration.millis(60));

    /** Set while a flash is queued, so a thousand BELs in one read are one flash and not a thousand. */
    private final AtomicBoolean bellPending = new AtomicBoolean();

    /** Clears the bell wash. One instance, restarted, rather than one per BEL. */
    private final PauseTransition bellClear = new PauseTransition(Duration.millis(80));

    /**
     * Asked before a paste that contains a newline; the window supplies the dialog and the setting.
     * Accepts everything until it is wired, so a paste is never silently dropped.
     */
    private Predicate<String> pasteConfirmer = text -> true;

    /** The cell last reported as pointer motion, so mode 1003 hears about cells and not pixels. */
    private int reportedMotionColumn = -1;

    private int reportedMotionRow = -1;

    /** Alt/Option prefixes ESC rather than composing a character; see {@link KeyEncoding}. */
    private boolean altIsMeta = true;

    /** Told once per frame in which the shell wrote something; the window marks a background tab. */
    private Runnable onOutput = () -> {};

    /** Told on the FX thread once per coalesced bell, whether or not the flash is on. */
    private Runnable onBellRung = () -> {};

    /** Set on the emulator thread by any model change; consumed by the frame that follows. */
    private volatile boolean outputPending;

    public void setOnOutput(Runnable onOutput) {
        this.onOutput = onOutput == null ? () -> {} : onOutput;
    }

    public void setOnBellRung(Runnable onBellRung) {
        this.onBellRung = onBellRung == null ? () -> {} : onBellRung;
    }

    /**
     * Frames with nothing to do before the painter stops. A little hysteresis, so output arriving a
     * few frames apart does not start and stop the timer on every burst.
     */
    private static final int IDLE_FRAMES_BEFORE_STOP = 10;

    /** When the painter runs — only while there is something to draw. See {@link FrameGate}. */
    private final FrameGate frames = new FrameGate(IDLE_FRAMES_BEFORE_STOP);

    /** Set once the view is closed, so a late emulator callback cannot restart the timer. */
    private volatile boolean viewClosed;

    private final AnimationTimer painter = new AnimationTimer() {
        @Override
        public void handle(long now) {
            boolean worked = false;
            // Per frame, not per write — a flood of output is one piece of news. Delivered whether
            // or not the view is on screen: marking a background tab is the whole point of it.
            if (outputPending) {
                outputPending = false;
                onOutput.run();
                worked = true;
            }
            // Drawn only where it can be seen. A tab that is not selected keeps its dirty flag and
            // is painted when it is shown again — rendering it per frame took the buffer lock and
            // spent the FX thread on a canvas nobody could see, while a build ran in it.
            if (dirty && isOnScreen()) {
                dirty = false;
                render();
                worked = true;
                // The first repaint the shell caused, rather than the first frame of an empty grid:
                // a window is on screen well before there is a prompt in it, and timing to the empty
                // one would flatter the measurement by everything the shell takes to start.
                if (session != null) com.termina.perf.Startup.markFirstPaint();
            }
            if (frames.frame(worked)) {
                stop();
                if (frames.release(() -> outputPending || (dirty && isOnScreen()))) start();
            }
        }
    };

    /**
     * Makes sure the painter is running. Safe from any thread, and cheap when it already is.
     *
     * <p>Public because the view cannot see every way it comes back on screen: a tab being selected
     * makes its content visible without laying it out, so the window says so.
     */
    public void wake() {
        if (viewClosed || !frames.claim()) return;
        if (Platform.isFxApplicationThread()) painter.start();
        else Platform.runLater(painter::start);
    }

    /**
     * Whether anything drawn now would be seen: in a showing, un-minimised window, with no hidden
     * ancestor. The ancestor walk is what catches a tab that is not selected — TabPane hides the
     * content of every other tab rather than removing it.
     */
    private boolean isOnScreen() {
        javafx.scene.Scene scene = getScene();
        if (scene == null) return false;
        javafx.stage.Window window = scene.getWindow();
        if (window == null || !window.isShowing()) return false;
        if (window instanceof javafx.stage.Stage stage && stage.isIconified()) return false;
        for (Node node = this; node != null; node = node.getParent()) {
            if (!node.isVisible()) return false;
        }
        return true;
    }

    /** Wakes the painter when the window it is in is shown or restored, which no layout announces. */
    private final javafx.beans.InvalidationListener wakeOnWindowChange = o -> wake();

    private javafx.stage.Window watchedWindow;

    private void watchWindowOf(javafx.scene.Scene scene) {
        if (watchedWindow != null) {
            watchedWindow.showingProperty().removeListener(wakeOnWindowChange);
            if (watchedWindow instanceof javafx.stage.Stage stage) {
                stage.iconifiedProperty().removeListener(wakeOnWindowChange);
            }
        }
        watchedWindow = scene == null ? null : scene.getWindow();
        if (watchedWindow != null) {
            watchedWindow.showingProperty().addListener(wakeOnWindowChange);
            if (watchedWindow instanceof javafx.stage.Stage stage) {
                stage.iconifiedProperty().addListener(wakeOnWindowChange);
            }
        }
        wake();
    }

    private final javafx.beans.value.ChangeListener<javafx.stage.Window> onWindowChanged =
            (o, was, now) -> watchWindowOf(getScene());

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
        addEventFilter(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
        addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            // Forgotten, not kept: a modifier pressed with the pointer elsewhere must not underline
            // whatever happened to be under the last point it was seen at in here.
            lastMouseX = -1;
            lastMouseY = -1;
            updateHover(-1, -1, false);
        });
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        // The modifier can go down and up without the mouse moving at all, and the underline has to
        // follow it either way — that is how the gesture announces itself to someone who has not
        // been told about it. Registered as its own filter because KEY_RELEASED is otherwise
        // unhandled here, and it never consumes.
        addEventFilter(KeyEvent.KEY_RELEASED, this::onKeyReleased);
        addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
        addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        setOnContextMenuRequested(this::onContextMenuRequested);
        // A scene's window can be set after the view joins it, so both moves are followed.
        sceneProperty().addListener((o, was, now) -> {
            if (was != null) was.windowProperty().removeListener(onWindowChanged);
            if (now != null) now.windowProperty().addListener(onWindowChanged);
            watchWindowOf(now);
        });

        display.setOnRepaint(this::markDirty);
        display.setSelectionSupplier(() -> selection);
        // Visual bell. An audible one would mean java.awt.Toolkit.beep(), which initialises AWT —
        // see App.main for why that is off the table.
        //
        // Coalesced: `cat` of a binary file rings the bell thousands of times in one read, and each
        // used to queue its own runLater and its own transition. One flash is pending at a time;
        // the rest are the same flash.
        display.setOnBell(() -> {
            if (bellPending.compareAndSet(false, true)) Platform.runLater(this::onBell);
        });
        bellClear.setOnFinished(e -> markDirty());
        resizeSettle.setOnFinished(e -> {
            if (session != null) session.resize(columns, rows);
        });
        display.setOnAlternateScreenChanged(() -> {
            // Scrollback belongs to the primary buffer; entering or leaving the alternate screen
            // must snap back to the live view or a full-screen program renders at an offset. The
            // selection goes with it: its coordinates named text on the other screen, and left in
            // place it was drawn over vim and copied vim's cells.
            scrollOrigin = 0;
            selection = null;
            markDirty();
        });
    }

    private int scrollbackLines = Settings.DEFAULT_SCROLLBACK;
    private com.termina.pty.LaunchOptions launchOptions = com.termina.pty.LaunchOptions.DEFAULTS;

    /** What this tab was started with — for duplicating it, and for reopening it once closed. */
    public com.termina.pty.LaunchOptions launchOptions() {
        return launchOptions;
    }

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
        // Both fire on the emulator thread for every buffer mutation — cheap by design. The
        // changes listener sees what moved; the model listener is where the view catches up.
        buffer.addChangesListener(follow);
        buffer.addModelListener(this::onModelChanged);
        session.setOnSessionEnded(() -> Platform.runLater(() -> {
            if (onSessionEnded != null) onSessionEnded.run();
        }));
        session.start();
        // Started here rather than inside the session: what the directory and the program are
        // *for* is a UI question. The term layer reports the shell's state; it does not decide what
        // a tab is called. On every platform, since the program half needs only ProcessHandle; the
        // watcher skips the directory where the OS cannot answer.
        cwdWatch = CwdWatcher.watch(session.pid(), display::setCwd, display::setProgram);
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
        viewClosed = true;
        painter.stop();
        resizeSettle.stop();
        // Before the session: the watcher polls by pid, and a pid is reused. Left running past the
        // process it was watching, it would eventually start reporting some unrelated process's
        // directory into a tab that no longer exists.
        if (cwdWatch != null) cwdWatch.close();
        if (session != null) session.close();
    }

    private void markDirty() {
        dirty = true;
        wake();
    }

    /** One coalesced bell: the flash if it is on, and the tab's mark either way. */
    private void onBell() {
        bellPending.set(false);
        if (bellEnabled) flashBell();
        onBellRung.run();
    }

    /** Visual bell: a brief wash over the canvas, cleared by the next frame. */
    private void flashBell() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.color(1, 1, 1, 0.18));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        bellClear.playFromStart();
    }

    // ---------------------------------------------------------------- font & layout

    public void setFontSize(double size) {
        double next = Math.max(6, size);
        // Every settings change re-applies every setting to every tab, so most calls here change
        // nothing — and an unchanged font rebuilt four faces, re-measured and re-laid out the view.
        if (next == fontSize && font != null) return;
        this.fontSize = next;
        applyFont();
    }

    /**
     * Sets the face. Resolved through {@link MonospaceFonts} so a family that is blank, or was
     * chosen on a machine that had it installed, falls back rather than silently rendering the grid
     * in a proportional face.
     */
    public void setFontFamily(String family) {
        String next = MonospaceFonts.resolve(family);
        if (next.equals(fontFamily) && font != null) return; // see setFontSize
        this.fontFamily = next;
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
        applyIconFont(IconFont.familyFor(family).orElse(null));
        markDirty();
        requestLayout();
    }

    /** After {@link #measureFont}: the bundled face is sized from the cell. */
    private void applyIconFont(String icons) {
        scaledIconFont = null;
        if (icons == null) {
            iconFont = boldIconFont = italicIconFont = boldItalicIconFont = null;
        } else if (icons.equals(Fonts.ICONS)) {
            // One file, no weights. Its icons are 1 em squares — a symbols-only font has no cell
            // of its own to fit them to — so at text size each spilled two-thirds of a cell into
            // the name after it. Sized from the cell instead, like the NF builds' icons.
            iconFont = boldIconFont = italicIconFont = boldItalicIconFont = Font.font(icons, fontSize);
            double size = IconFont.iconSize(charWidth);
            scaledIconFont = Font.font(icons, size);
            scaledIconShift = IconFont.iconBaselineShift(ascent, lineHeight, size);
        } else {
            iconFont = Font.font(icons, fontSize);
            boldIconFont = Font.font(icons, FontWeight.BOLD, fontSize);
            italicIconFont = Font.font(icons, FontWeight.NORMAL, javafx.scene.text.FontPosture.ITALIC, fontSize);
            boldItalicIconFont = Font.font(icons, FontWeight.BOLD, javafx.scene.text.FontPosture.ITALIC, fontSize);
        }
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
        // The taller of the leading we want and the height the face needs. A fixed ratio alone
        // clipped faces with deep descenders — JetBrains Mono wants about 1.32 em — and the row
        // below, drawn after, painted its background over the tails of every g and y above it.
        double measured = Math.ceil(probe.getLayoutBounds().getHeight());
        lineHeight = Math.max(Math.ceil(fontSize * LINE_SPACING), measured > 0 ? measured : 0);
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
        boolean resized = canvas.getWidth() != renderedWidth || canvas.getHeight() != renderedHeight;
        if (newColumns != columns || newRows != rows) {
            columns = newColumns;
            rows = newRows;
            // The canvas has already taken the new size above; the shell hears about it once the
            // size has held still — see resizeSettle. Before start() there is no session to tell,
            // and start() reads the fields directly.
            if (session != null) resizeSettle.playFromStart();
        }
        // In this pass rather than the next frame's, when the canvas changed size: AnimationTimers
        // run before layout in a pulse, so leaving it to the painter would put one frame of a
        // resized, unpainted canvas on screen. Otherwise only if something is waiting to be drawn
        // — any requestLayout lands here, and redrawing for each was a second render in the same
        // frame as the painter's. Clearing the flag here is what keeps the painter from repeating
        // the work.
        if ((resized || dirty) && isOnScreen()) {
            dirty = false;
            render();
        } else if (resized) {
            dirty = true;
        }
    }

    /** The canvas size {@link #render} last drew at, so a layout pass knows whether it changed. */
    private double renderedWidth = -1;

    private double renderedHeight = -1;

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
        renderedWidth = w;
        renderedHeight = h;
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
                    // One copy of the run per frame, shared by the column bookkeeping and the draw.
                    String text = characters.toString();
                    int column = columns.columnOf(y, x, text);
                    drawRun(g, column, y - startRow, style, text, columns.lastCells());
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
            drawLinkUnderline(g);
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

    private void drawRun(GraphicsContext g, int column, int row, TextStyle style, String text, int cells) {
        if (row < 0 || row >= rows) return;
        if (text.isEmpty()) return;

        Color bg = backgroundOf(style);
        Color fg = foregroundOf(style, bg);

        double x = column * charWidth;
        double y = row * lineHeight;
        // Cells, not characters: an astral code point is two array slots and one column, so a run
        // holding one would otherwise be painted a column too wide.
        double width = cells * charWidth;

        if (!bg.equals(palette.background())) {
            g.setFill(bg);
            g.fillRect(x, y, width, lineHeight);
        }

        if (style.hasOption(TextStyle.Option.HIDDEN)) return;

        g.setFont(fontFor(style));
        g.setFill(fg);
        g.setTextBaseline(VPos.BASELINE);
        drawGlyphs(g, text, x, y + ascent, iconFontFor(style));

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
     * <p>The one-cell-per-char fast path is the one that matters for throughput: it is what almost
     * every run is — including every box-drawing border and braille bar a TUI draws, which an
     * earlier ASCII-only test sent down the slow path — and it stays a single draw call. The one
     * thing it trusts is that the face, or its fallback, gives those glyphs a one-cell advance; a
     * fallback that does not would walk a long border off the grid, and per-glyph drawing is the
     * answer for that face rather than for every run.
     *
     * <p>A run with a private-use code point in it is drawn per glyph too when {@code iconFace} is
     * set, so the icon can be drawn in that face and the text around it in the run's own.
     *
     * @param iconFace the face for private-use code points, or null to leave them to the run's
     */
    private void drawGlyphs(GraphicsContext g, String text, double x, double baseline, Font iconFace) {
        if (CellWidth.isOneCellPerChar(text) && (iconFace == null || !IconFont.containsPrivateUse(text))) {
            g.fillText(text, x, baseline);
            return;
        }
        Font face = g.getFont();
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
                drawCodePoint(g, codePoint, x + cell * charWidth, baseline, face, iconFace);
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

    /**
     * Draws one code point in {@code face}, which the context is already set to — or, for a
     * private-use one when {@code iconFace} is set, in the icon face, leaving {@code face} set after.
     */
    private void drawCodePoint(GraphicsContext g, int codePoint, double x, double baseline, Font face, Font iconFace) {
        String glyph = new String(Character.toChars(codePoint));
        if (iconFace == null || !IconFont.isPrivateUse(codePoint)) {
            g.fillText(glyph, x, baseline);
            return;
        }
        boolean scaled = scaledIconFont != null && !IconFont.isPowerline(codePoint);
        g.setFont(scaled ? scaledIconFont : iconFace);
        g.fillText(glyph, x, scaled ? baseline + scaledIconShift : baseline);
        g.setFont(face);
    }

    private Font fontFor(TextStyle style) {
        boolean bold = style.hasOption(TextStyle.Option.BOLD);
        boolean italic = style.hasOption(TextStyle.Option.ITALIC);
        if (bold && italic) return boldItalicFont;
        if (bold) return boldFont;
        if (italic) return italicFont;
        return font;
    }

    /** The face for private-use code points in this style, or null to draw them in {@link #fontFor}. */
    private Font iconFontFor(TextStyle style) {
        if (iconFont == null) return null;
        boolean bold = style.hasOption(TextStyle.Option.BOLD);
        boolean italic = style.hasOption(TextStyle.Option.ITALIC);
        if (bold && italic) return boldItalicIconFont;
        if (bold) return boldIconFont;
        if (italic) return italicIconFont;
        return iconFont;
    }

    /** @param bg the run's resolved background, which dim blends toward */
    private Color foregroundOf(TextStyle style, Color bg) {
        boolean inverse = style.hasOption(TextStyle.Option.INVERSE);
        Color fg = palette.resolve(
                inverse ? style.getBackground() : style.getForeground(),
                inverse ? palette.background() : palette.foreground());
        if (style.hasOption(TextStyle.Option.DIM)) fg = TerminalPalette.dim(fg, bg);
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
        int cursorRow = display.getCursorY() - 1;
        int row = cursorRow - scrollOrigin;
        // After a line is written to exactly its full width the cursor is *at* the width, pending
        // the wrap that the next character will cause. xterm draws it on the last column; bailing
        // on it made the cursor vanish on every exactly-full line.
        int column = Math.min(display.getCursorX(), columns - 1);
        if (row < 0 || row >= rows || column < 0) return;

        double x = column * charWidth;
        double y = row * lineHeight;
        g.setFill(palette.foreground());

        switch (effectiveCursorShape()) {
            case UNDERLINE -> g.fillRect(x, y + lineHeight - 2, charWidth, 2);
            case BAR -> g.fillRect(x, y, 2, lineHeight);
            default -> {
                g.fillRect(x, y, charWidth, lineHeight);
                // Re-draw the glyph underneath in the cell's background colour, so a block cursor
                // does not hide the character it is sitting on. By slot, not column — the two
                // differ past any astral character on the line — and in the cell's own font, or a
                // bold cursor character came back regular for the duration.
                String text = lineTextAt(cursorRow);
                int slot = ColumnMap.slotAt(text, column);
                if (slot >= text.length()) return;
                int codePoint = text.codePointAt(slot);
                if (codePoint == 0 || codePoint == ' ' || codePoint == CharUtils.DWC) return;
                TextStyle style = buffer.getStyleAt(slot, cursorRow);
                g.setFill(style == null ? palette.background() : backgroundOf(style));
                Font face = style == null ? font : fontFor(style);
                g.setFont(face);
                g.setTextBaseline(VPos.BASELINE);
                drawCodePoint(g, codePoint, x, y + ascent, face, style == null ? iconFont : iconFontFor(style));
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
        // The link underline follows the modifier down as well as up. Only modifier keys, so an
        // ordinary keystroke never pays for a hover test.
        if (e.getCode().isModifierKey()) onLinkModifierChanged(e);

        // The view's own keys come before the encoder, or the shell gets CSI 5;2~ for Shift+PageUp
        // and the scrollback never moves.
        if (buffer != null && handleViewKey(e)) {
            e.consume();
            return;
        }
        if (session == null || !session.isRunning()) return;

        byte[] encoded = KeyEncoding.encodePressed(e, session::keyCode, altIsMeta);
        if (KeyTrace.enabled()) KeyTrace.log(e, encoded);
        if (encoded != null) {
            snapToLive();
            clearSelection();
            session.send(encoded);
            e.consume();
        }
    }

    /** Performs a {@link ViewKeys} action, returning false when the key is not one. */
    private boolean handleViewKey(KeyEvent e) {
        ViewKeys.Action action = ViewKeys.of(e, display.isAlternateScreen());
        switch (action) {
            case NONE -> {
                return false;
            }
            case PASTE -> paste();
            case COPY -> copySelection();
            default -> {
                int next = ViewKeys.scrolledOrigin(action, scrollOrigin, rows, buffer.getHistoryLinesCount());
                if (next != scrollOrigin) {
                    scrollOrigin = next;
                    markDirty();
                }
            }
        }
        return true;
    }

    private void onKeyTyped(KeyEvent e) {
        if (session == null || !session.isRunning()) return;
        byte[] encoded = KeyEncoding.encodeTyped(e, altIsMeta);
        if (KeyTrace.enabled()) KeyTrace.log(e, encoded);
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
     * <p>Called on the emulator thread for every buffer mutation. Without this, output arriving
     * while the user is scrolled back slides the text under them, and a selection made moments
     * earlier silently comes to refer to different characters — which matters because copying it
     * would then produce the wrong text. {@link HistoryFollow} says how far things moved and
     * whether JediTerm has already moved the selection itself, which it does on a resize.
     */
    private void onModelChanged() {
        if (buffer == null) return;
        int history = buffer.getHistoryLinesCount();
        HistoryFollow.Change change = follow.sync(history, buffer.getHeight());
        if (change.resize()) {
            scrollOrigin = HistoryFollow.origin(scrollOrigin, 0, history);
        } else if (change.delta() != 0) {
            scrollOrigin = HistoryFollow.origin(scrollOrigin, change.delta(), history);
            TerminalSelection current = selection;
            if (current != null) current.shiftY(-change.delta());
        }
        outputPending = true;
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
        return new Point(ColumnMap.slotAt(lockedLineTextAt(line), column), line);
    }

    /**
     * The cell boundary nearest a mouse position, as a slot — the end of a drag, where "past the
     * middle of a cell" means the cell is in. See {@link SelectionGeometry#drag}.
     */
    private Point boundaryAt(MouseEvent e) {
        int column = (int) Math.round(e.getX() / charWidth);
        int row = (int) Math.floor(e.getY() / lineHeight);
        column = Math.max(0, Math.min(columns, column));
        row = Math.max(0, Math.min(rows - 1, row));
        int line = scrollOrigin + row;
        return new Point(ColumnMap.slotAt(lockedLineTextAt(line), column), line);
    }

    /**
     * {@link #lineTextAt} for a caller that does not hold the lock — the mouse handlers, which run
     * on the FX thread while the emulator writes the same lines on its own.
     */
    private String lockedLineTextAt(int row) {
        if (buffer == null) return "";
        buffer.lock();
        try {
            return lineTextAt(row);
        } finally {
            buffer.unlock();
        }
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

    // ---------------------------------------------------------------- links

    /**
     * The link under the pointer, with everything the click and the underline need.
     *
     * <p>{@code file} is null for a URL and non-null for a path that was found on disk — a path
     * that was not is never a hover at all, so nothing here can be opened that does not exist.
     *
     * @param spans the run split across the rows it occupies, since a wrapped URL is one link drawn
     *     on two lines
     */
    private record Hover(LinkMatch match, java.nio.file.Path file, java.util.List<LinkSpan> spans) {}

    /** One row's worth of an underline: a line index and the slots it covers. */
    private record LinkSpan(int line, int fromSlot, int toSlot) {}

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");

    /**
     * How far to look either side for the rest of a wrapped line.
     *
     * <p>A URL that fills eight rows is not a URL anyone is going to click, and the bound is what
     * keeps the hover check O(1) rather than O(scrollback) on a screen full of wrapped output.
     */
    private static final int MAX_WRAP_ROWS = 8;

    private com.termina.link.LinkActions linkActions;
    private Hover hover;
    private Hover menuLink;
    private double lastMouseX = -1;
    private double lastMouseY = -1;

    /** Where a link goes once clicked. Supplied by the window; without it links are inert. */
    public void setLinkActions(com.termina.link.LinkActions linkActions) {
        this.linkActions = linkActions;
    }

    /**
     * Recomputes what is under the pointer.
     *
     * <p>Only while the modifier is held, which is what keeps this off the ordinary mouse-move
     * path: with no modifier the answer is "nothing" after one boolean, and the filesystem is never
     * asked. Terminals that underline on hover regardless have to decorate text you are only
     * reading, and pay for it on every move.
     */
    private void updateHover(double x, double y, boolean modifierDown) {
        Hover previous = hover;
        hover = modifierDown ? linkAt(x, y) : null;
        setCursor(hover == null ? javafx.scene.Cursor.DEFAULT : javafx.scene.Cursor.HAND);
        if (!sameHover(previous, hover)) markDirty();
    }

    private static boolean sameHover(Hover a, Hover b) {
        if (a == null || b == null) return a == b;
        return a.spans().equals(b.spans()) && a.match().equals(b.match());
    }

    /** The link at a point in the view, or null. Reads the buffer, so it takes the lock. */
    private Hover linkAt(double x, double y) {
        if (buffer == null || linkActions == null) return null;
        if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) return null;

        int row = (int) Math.floor(y / lineHeight);
        if (row < 0 || row >= rows) return null;
        int line = scrollOrigin + row;
        int column = (int) Math.floor(x / charWidth);
        if (column < 0 || column >= columns) return null;

        buffer.lock();
        try {
            LogicalLine logical = logicalLineAt(line);
            if (logical == null) return null;
            int slot = ColumnMap.slotAt(lineTextAt(line), column);
            int index = logical.offsetOf(line) + slot;
            LinkMatch match = LinkScanner.at(logical.text(), index);
            if (match == null) return null;

            // Moving along a link with the modifier held asks the same question of the filesystem
            // for every pixel of travel. The answer cannot change within one hover, so it is asked
            // once per link rather than once per event.
            Hover previous = hover;
            java.nio.file.Path file =
                    previous != null && previous.match().equals(match) ? previous.file() : fileFor(match);
            if (match.kind() == LinkMatch.Kind.PATH && file == null) return null;
            return new Hover(match, file, logical.spansOf(match.start(), match.end()));
        } catch (RuntimeException e) {
            // The rows moved under us between the event and the read. There is no link here now.
            return null;
        } finally {
            buffer.unlock();
        }
    }

    /**
     * The file a match names, or null when it names none.
     *
     * <p>This is the whole safety story for paths: a candidate is any word under the pointer, and
     * what makes it a link is that it resolves — against <em>this tab's</em> shell directory — to
     * something that is actually there. A pattern cannot tell a filename from a word; the
     * filesystem can.
     */
    private java.nio.file.Path fileFor(LinkMatch match) {
        String target = match.target();
        if (match.kind() == LinkMatch.Kind.URL) {
            java.nio.file.Path fromUri = com.termina.link.LinkPaths.fromFileUri(target);
            return fromUri != null && java.nio.file.Files.exists(fromUri) ? fromUri : null;
        }
        String cwd = display.getCwd();
        java.nio.file.Path resolved = com.termina.link.LinkPaths.resolve(
                target, cwd == null || cwd.isBlank() ? null : java.nio.file.Path.of(cwd));
        return resolved != null && java.nio.file.Files.exists(resolved) ? resolved : null;
    }

    /** Opens the hovered link. */
    private void openLink(Hover target) {
        if (target == null || linkActions == null) return;
        if (target.file() != null) {
            linkActions.openFile(
                    target.file(), target.match().line(), target.match().column());
        } else if (target.match().isUrl()) {
            linkActions.openUrl(target.match().target());
        }
    }

    /** What the link points at, for the clipboard: the URL, or the resolved file. */
    private String linkAddress(Hover target) {
        if (target == null) return "";
        if (target.match().isUrl() && target.file() == null)
            return target.match().target();
        return target.file() == null ? target.match().target() : target.file().toString();
    }

    /**
     * A logical line — one row, or the several a soft wrap spread it over.
     *
     * <p>Joined because a URL that reaches the right edge continues on the next row and is still
     * one URL. Terminals that scan a row at a time cut it in half, which fails on exactly the long
     * URLs most worth clicking.
     */
    private record LogicalLine(int firstLine, java.util.List<String> rowTexts, String text) {

        int offsetOf(int line) {
            int offset = 0;
            for (int i = 0; i < line - firstLine; i++) offset += rowTexts.get(i).length();
            return offset;
        }

        /** The range split into the rows it crosses, so the underline can be drawn per row. */
        java.util.List<LinkSpan> spansOf(int from, int to) {
            java.util.List<LinkSpan> spans = new ArrayList<>();
            int offset = 0;
            for (int i = 0; i < rowTexts.size(); i++) {
                int length = rowTexts.get(i).length();
                int start = Math.max(from, offset);
                int end = Math.min(to, offset + length);
                if (start < end) spans.add(new LinkSpan(firstLine + i, start - offset, end - offset));
                offset += length;
            }
            return spans;
        }
    }

    /** Builds the logical line containing a row. Caller holds the buffer lock. */
    private LogicalLine logicalLineAt(int line) {
        // Bounded by the history that exists as well as by MAX_WRAP_ROWS: asking for row -1 with an
        // empty scrollback is not an exception but an ERROR line in JediTerm's own log, once per
        // mouse move, which turns a hover into a log flood.
        int floor = -buffer.getHistoryLinesCount();
        int first = line;
        int back = 0;
        while (back < MAX_WRAP_ROWS && first - 1 >= floor && isWrapped(first - 1)) {
            first--;
            back++;
        }
        java.util.List<String> texts = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        int row = first;
        while (true) {
            String text = lineTextAt(row);
            texts.add(text);
            joined.append(text);
            if (!isWrapped(row) || row - first >= MAX_WRAP_ROWS) break;
            row++;
        }
        if (line < first || line > row) return null;
        return new LogicalLine(first, texts, joined.toString());
    }

    /** Whether a row continues onto the next one. Caller holds the buffer lock. */
    private boolean isWrapped(int row) {
        if (buffer == null) return false;
        try {
            var line = buffer.getLine(row);
            return line != null && line.isWrapped();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Hovers a point as though the modifier were held, and describes what is there.
     *
     * <p>For the development capture hook. What a link resolves to depends on the shell's output,
     * the tab's own directory and the filesystem — none of which a unit test has, so the only
     * honest check is against a real one.
     */
    public String hoverLinkForCapture(double x, double y) {
        updateHover(x, y, true);
        Hover target = hover;
        if (target == null) return "none at " + x + "," + y;
        LinkMatch match = target.match();
        return match.kind() + " text=<" + match.text() + "> opens=<" + linkAddress(target) + ">"
                + (match.line() > 0 ? " line=" + match.line() : "")
                + (match.column() > 0 ? " column=" + match.column() : "")
                + " rows=" + target.spans().size();
    }

    /** Opens whatever {@link #hoverLinkForCapture} last found. For the development capture hook. */
    public String openHoveredLinkForCapture() {
        Hover target = hover;
        if (target == null) return "nothing hovered";
        openLink(target);
        return linkAddress(target);
    }

    /** Underlines the hovered link, in the text's own colour. */
    private void drawLinkUnderline(GraphicsContext g) {
        Hover target = hover;
        if (target == null) return;
        g.setStroke(palette.foreground());
        g.setLineWidth(1);
        for (LinkSpan span : target.spans()) {
            int row = span.line() - scrollOrigin;
            if (row < 0 || row >= rows) continue;
            String text = lineTextAt(span.line());
            double from = ColumnMap.columnAt(text, span.fromSlot()) * charWidth;
            double to = ColumnMap.columnAt(text, span.toSlot()) * charWidth;
            // Half a pixel down the grid line, or a 1px stroke straddles it and paints two grey rows
            // instead of one solid one.
            double y = Math.floor((row + 1) * lineHeight) - 1.5;
            g.strokeLine(from, y, to, y);
        }
    }

    // ---------------------------------------------------------------- context menu

    private ContextMenu contextMenu;
    private MenuItem openLinkItem;
    private MenuItem copyLinkItem;
    private SeparatorMenuItem linkSeparator;
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
        // The link items are the menu's answer to a gesture nobody was told about: they need no
        // modifier and they say what is there. Computed for the right-clicked cell, not the last
        // hovered one — the menu can be raised from the keyboard, and the pointer may be elsewhere.
        menuLink = linkAt(e.getX(), e.getY());
        boolean onLink = menuLink != null;
        openLinkItem.setVisible(onLink);
        copyLinkItem.setVisible(onLink);
        linkSeparator.setVisible(onLink);
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
        // Shown first, and left open: the row's buttons are pressed with the menu on screen, which
        // is a different thing from firing a MenuItem and is exactly the gesture being tested.
        if (!contextMenu.isShowing()) contextMenu.show(this, 300, 200);
        for (MenuItem item : contextMenu.getItems()) {
            if (label.equals(item.getText())) {
                item.fire();
                return true;
            }
            // A CustomMenuItem is a row of controls; its buttons carry the label in their tooltip.
            if (item instanceof javafx.scene.control.CustomMenuItem custom
                    && custom.getContent() != null
                    && fireButtonIn(custom.getContent(), label)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fireButtonIn(javafx.scene.Node node, String label) {
        if (node instanceof javafx.scene.control.Button button
                && button.getTooltip() != null
                && label.equals(button.getTooltip().getText())) {
            button.fire();
            return true;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                if (fireButtonIn(child, label)) return true;
            }
        }
        return false;
    }

    private ContextMenu buildContextMenu() {
        copyItem = item(tr("menu.copy"), MenuIcons.copy(), this::copySelection);
        pasteItem = item(tr("menu.paste"), MenuIcons.paste(), this::paste);
        openLinkItem = item(tr("menu.openLink"), MenuIcons.openLink(), () -> openLink(menuLink));
        copyLinkItem = item(tr("menu.copyLinkAddress"), MenuIcons.copy(), () -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(linkAddress(menuLink));
            Clipboard.getSystemClipboard().setContent(content);
        });

        // Hidden together with the items they divide: a separator left behind when the pointer was
        // not over a link is a rule across the top of the menu with nothing above it.
        linkSeparator = new SeparatorMenuItem();

        List<MenuItem> items = new ArrayList<>(List.of(
                openLinkItem,
                copyLinkItem,
                linkSeparator,
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
        // The model listener has already dropped the selection and the scroll offset, since the
        // buffer announces a cleared history; the anchor is the view's own and goes here.
        selectionAnchor = null;
        wheel.reset();
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

        // Before mouse reporting, deliberately. A program that has grabbed the mouse would
        // otherwise swallow the click, and the modifier is precisely the gesture that means "this
        // one is the terminal's". Only when something is actually under the pointer, so the
        // modifier is not taken away from the program the rest of the time.
        if (e.getButton() == MouseButton.PRIMARY && LinkGesture.opensLink(MAC, e.isMetaDown(), e.isControlDown())) {
            Hover target = linkAt(e.getX(), e.getY());
            if (target != null) {
                openLink(target);
                e.consume();
                return;
            }
        }

        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.PRESSED)) {
            // The program has this press, so nothing local may grow out of it. In mode 1000 the
            // program hears press and release only, the drag between them is not reported, and a
            // drag that falls through to selection must not find an anchor from some earlier
            // plain click waiting for it.
            selectionAnchor = null;
            e.consume();
            return;
        }
        if (buffer == null) return;
        if (e.getButton() == MouseButton.MIDDLE) {
            // Middle-click pastes, as on every X11 terminal — but the system clipboard, not the
            // PRIMARY selection, because JavaFX has no API for PRIMARY: its Clipboard is the
            // CLIPBOARD selection and nothing else. Selecting text here does not fill PRIMARY
            // either, so pasting it would be the one thing this could not do anyway. Reached only
            // when the program did not take the press, or Shift bypassed it.
            paste();
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY) return;

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

    private void onMouseMoved(MouseEvent e) {
        lastMouseX = e.getX();
        lastMouseY = e.getY();
        updateHover(lastMouseX, lastMouseY, LinkGesture.opensLink(MAC, e.isMetaDown(), e.isControlDown()));
        reportMotion(e);
    }

    /**
     * Pointer motion for a program in any-motion mode (1003), by cell rather than by pixel.
     *
     * <p>Gated on the mode first, so the common case is one comparison and no call into the
     * encoder. Repeated within a cell it would be noise the protocol does not expect — xterm sends
     * motion when the cell changes, not when the pointer does.
     */
    private void reportMotion(MouseEvent e) {
        if (session == null || display.getMouseMode() != MouseMode.MOUSE_REPORTING_ALL_MOTION) return;
        int column = column(e);
        int row = visibleRow(e);
        if (column == reportedMotionColumn && row == reportedMotionRow) return;
        reportedMotionColumn = column;
        reportedMotionRow = row;
        reportMouse(e, com.jediterm.core.input.MouseEvent.Type.MOVED);
    }

    /** Follows the modifier going down or up while the pointer sits still. */
    private void onLinkModifierChanged(KeyEvent e) {
        if (lastMouseX < 0) return;
        updateHover(lastMouseX, lastMouseY, LinkGesture.opensLink(MAC, e.isMetaDown(), e.isControlDown()));
    }

    private void onKeyReleased(KeyEvent e) {
        onLinkModifierChanged(e);
    }

    private void onMouseDragged(MouseEvent e) {
        if (pressedOnScrollBar) return;
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.DRAGGED)) {
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY || selectionAnchor == null) return;
        Point to = boundaryAt(e);
        // Nothing until the pointer has left the cell it pressed in, so a plain click never leaves
        // a one-cell selection behind — unless one exists already, in which case the drag is
        // extending it and every movement counts.
        if (selection == null && !SelectionGeometry.leftPressCell(selectionAnchor, to.y, to.x)) return;
        selection = SelectionGeometry.drag(selectionAnchor, to.y, to.x);
        markDirty();
    }

    /**
     * The selection covering the word under {@code at}.
     *
     * <p>Package-visible and static so it can be tested against a plain buffer — the boundary
     * arithmetic below is exactly the kind that looks right and is wrong by one.
     *
     * <p>{@code getNextSeparator} returns the <em>last character of the word</em>, while a
     * selection's end is exclusive — the one convention, see {@link SelectionGeometry}. Passing it
     * through unadjusted drops the final character, so double-clicking "india" yields "indi" —
     * which looks like working selection until someone pastes it.
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
        line.updateEnd(new Point(Math.max(columns, lockedLineTextAt(at.y).length()), at.y));
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
            // Ours, not TerminalSelection.intersect: that one counts the end column in, and the
            // copy does not, so the highlight was a cell wider than the text it stood for.
            int[] span = SelectionGeometry.onRow(current, line, slotsInRow(line));
            if (span == null) continue;
            // The span is in slots; the rectangle is in columns.
            String text = lineTextAt(line);
            double x = ColumnMap.columnAt(text, span[0]);
            double to = ColumnMap.columnAt(text, span[1]);
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

        // By distance, with the remainder carried — see WheelScroll. The event is the view's
        // either way; it is consumed even when it did not amount to a whole line yet.
        int lines = wheel.lines(e.getDeltaY(), lineHeight);
        e.consume();
        if (lines == 0) return;
        int history = buffer.getHistoryLinesCount();
        int next = Math.max(-history, Math.min(0, scrollOrigin - lines));
        if (next != scrollOrigin) {
            scrollOrigin = next;
            markDirty();
        }
    }

    /**
     * Supplies the question asked before a multi-line paste — the dialog, and the setting that
     * turns it off. Returns true to go ahead. See {@link PasteSafety}.
     */
    public void setPasteConfirmer(Predicate<String> confirmer) {
        this.pasteConfirmer = confirmer == null ? text -> true : confirmer;
    }

    /**
     * Pastes the clipboard, honouring bracketed-paste mode if the shell enabled it.
     *
     * <p>Sanitised first and confirmed second, in that order, so what the user is asked about is
     * what will be sent.
     */
    public void paste() {
        String text = KeyEncoding.sanitise(Clipboard.getSystemClipboard().getString());
        if (text.isEmpty() || session == null) return;
        if (PasteSafety.needsConfirmation(text) && !pasteConfirmer.test(text)) return;
        snapToLive();
        session.send(KeyEncoding.encodePaste(text, display.isBracketedPaste()));
    }

    public void setAltIsMeta(boolean altIsMeta) {
        this.altIsMeta = altIsMeta;
    }
}
