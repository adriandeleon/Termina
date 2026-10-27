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
import org.jspecify.annotations.Nullable;

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
     * Whether the press that began the current drag landed on the scrollbar — or on the find bar,
     * which is owed the same treatment: its drag and release are its own.
     *
     * <p>Latched at the press rather than re-tested per event: what a drag reports as its target is
     * not something to depend on, and a drag that started on the thumb must stay the thumb's for as
     * long as the button is held — including when the pointer leaves the bar, which it does the
     * moment anyone drags at a natural angle.
     */
    private boolean pressedOnScrollBar;

    private final FxTerminalDisplay display = new FxTerminalDisplay();

    private @Nullable TerminalSession session;
    private @Nullable TerminalTextBuffer buffer;

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
    private @Nullable Font iconFont;

    private @Nullable Font boldIconFont;
    private @Nullable Font italicIconFont;
    private @Nullable Font boldItalicIconFont;
    /**
     * The bundled icon face sized from the cell (see {@link IconFont#iconSize}), and how far below
     * the text baseline to draw it so it sits centred in the row. Null when the icons come from a
     * Nerd build of the terminal face, whose icons are already drawn to its cell.
     */
    private @Nullable Font scaledIconFont;

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
    private volatile @Nullable TerminalSelection selection;

    /**
     * Where the current drag began, so a backwards drag extends rather than restarts.
     *
     * <p>Moved with its text as output scrolls, like the selection: a drag held while a build runs
     * would otherwise extend from whatever row has since scrolled under where it began — and with
     * autoscroll a drag routinely lasts long enough for that. Volatile for the same reason the
     * selection is: the emulator thread moves it.
     */
    private volatile @Nullable Point selectionAnchor;

    /**
     * Whether {@link #selection} is a block — the same columns on every row — rather than a stream.
     * A block's x is a <b>column</b>, not a slot; see {@link SelectionGeometry#blockDrag}.
     */
    private volatile boolean rectangular;

    /** The column the current drag was pressed in, which a block needs and a stream does not. */
    private int anchorColumn;

    /** Whether the current drag makes a block, decided at the press and kept for the drag. */
    private boolean blockDrag;

    /** Whether the button is down on a drag of our own (not the scrollbar's, not a program's). */
    private boolean dragging;

    /** Where the pointer last was during the drag, for the frames that autoscroll with it still. */
    private double dragX;

    private double dragY;

    private final AutoScroll autoScroll = new AutoScroll();

    /** Whether the pointer is past the top or bottom edge with the button down. */
    private boolean autoScrolling;

    /**
     * The selection's text as it was when the selection was made, so a later change to that text
     * drops the highlight. A keystroke no longer does: typing a command under a selection of the
     * output above it should leave that selection alone. What must not survive is a highlight over
     * text that has been redrawn in place — a line editor rewriting the prompt — because copying it
     * would then produce something the user never selected.
     */
    private volatile @Nullable String settledText;

    /** Set by any buffer change while there is a selection; the next frame compares the text. */
    private volatile boolean selectionToVerify;

    /** Past this many rows the comparison is skipped: a Select All over a long history is not redrawn. */
    private static final int VERIFIED_ROWS = 1000;

    /** Copies each selection to the clipboard as it is made. A setting. */
    private boolean copyOnSelect;

    /** Set by a press with the pane-drag chord (see {@link PaneDrag}); the drag is then the pane's. */
    private boolean paneDragArmed;

    /** Told when this pane is picked up, so the window knows which pane a drop is carrying. */
    private java.util.function.@Nullable Consumer<TerminalView> onPaneDragStarted;

    public void setOnPaneDragStarted(java.util.function.Consumer<TerminalView> onPaneDragStarted) {
        this.onPaneDragStarted = onPaneDragStarted;
    }

    /**
     * Every row the text has moved up since the session began — the sum of {@link HistoryFollow}'s
     * deltas. Anything that remembers a line can record this beside it and work out later where the
     * line has gone: {@code now = then - (linesScrolled - recordedScrolled)}. Search matches use it,
     * and so would anything else positioned in the buffer, without each having to be shifted on
     * every change the way the selection is.
     *
     * <p>Written on the emulator thread under the buffer lock, read under the same lock.
     */
    private volatile long linesScrolled;

    /**
     * Bumped when the rows were rebuilt rather than moved — a reflow, or a cleared history — after
     * which a recorded line number means nothing and whatever held one has to be worked out again.
     */
    private volatile int layoutEpoch;

    /**
     * How far the text moved on each change, so the selection and viewport can follow it. When the
     * history goes, everything measured against it goes too, or the viewport scrolls into rows
     * that no longer exist.
     */
    private final HistoryFollow follow = new HistoryFollow(() -> {
        selection = null;
        selectionAnchor = null;
        scrollOrigin = 0;
        layoutEpoch++;
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

    /** The kitty keyboard protocol's view of the keys: repeats, and the releases owed. */
    private final KittyKeyInput kittyInput = new KittyKeyInput();

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
            // Output under an open search: the results are refreshed, but not per frame — a build
            // streaming into a searched tab would otherwise re-run the regex over the scrollback
            // sixty times a second. Until then the matches ride along with their text.
            if (searchOutdated && searchRefresh.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                searchRefresh.playFromStart();
            }
            // Counted as work whether or not it scrolled this frame, so the painter keeps running
            // while the pointer is held still outside the pane: no event will arrive to restart it.
            if (autoScrolling) {
                stepAutoScroll(now);
                worked = true;
            }
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

    private javafx.stage.@Nullable Window watchedWindow;

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
        addEventHandler(MouseEvent.DRAG_DETECTED, this::onDragDetected);
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
        // Keys held when focus leaves are released somewhere else; forgetting them keeps the next
        // press of one from being reported as a repeat, and no release from being owed for it.
        focusedProperty().addListener((o, was, now) -> {
            if (!now) kittyInput.reset();
        });
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
            if (session == null) return;
            // Taken now, against the rows as they are: once the resize has reflowed them, a mark's
            // row means nothing. See LogicalLines.
            marksBeforeResize = logicalMarks();
            session.resize(columns, rows);
        });
        searchRefresh.setOnFinished(e -> {
            if (!searchOpen || !searchOutdated) return;
            searchOutdated = false;
            runSearch(true, false);
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
        // Both before the emulator starts reading: the first prompt can already carry a mark.
        session.getTerminal().setUrlHyperlinkFilter(ExplicitLink.FILTER);
        session.setOscListener(this::onOsc);
        session.setTypeAheadWanted(
                () -> typeAheadAllowed && com.termina.term.TypeAheadModel.predictsFor(display.getProgram()));
        session.typeAhead().setOnRedraw(this::markDirty);
        session.setOnSessionEnded(() -> Platform.runLater(() -> {
            if (onSessionEnded != null) onSessionEnded.run();
        }));
        session.start();
        // Started here rather than inside the session: what the directory and the program are
        // *for* is a UI question. The term layer reports the shell's state; it does not decide what
        // a tab is called. On every platform, since the program half needs only ProcessHandle; the
        // watcher skips the directory where the OS cannot answer.
        // The OS's answer stands until the shell reports one where a report wins (Windows), and is
        // not asked for at all where the process is not the shell (WSL's launcher). See ProcessCwd.
        boolean osKnows = com.termina.pty.ProcessCwd.osKnowsDirectoryOf(
                launchOptions.hasCommand() ? launchOptions.command().getFirst() : launchOptions.shell());
        cwdWatch = CwdWatcher.watch(
                session.pid(),
                cwd -> {
                    if (osKnows && !directoryReported) display.setCwd(cwd);
                },
                display::setProgram);
        markDirty();
    }

    private @Nullable CwdWatcher cwdWatch;

    private @Nullable Runnable onSessionEnded;

    public void setOnSessionEnded(Runnable onSessionEnded) {
        this.onSessionEnded = onSessionEnded;
    }

    public FxTerminalDisplay getDisplay() {
        return display;
    }

    /** The shell's session. Only once {@link #start} has run; before that it fails, saying so. */
    public TerminalSession getSession() {
        TerminalSession started = session;
        if (started == null) throw new IllegalStateException("the shell has not started");
        return started;
    }

    /** Whether {@link #start} has run, so that {@link #getSession} has a session to give. */
    public boolean isStarted() {
        return session != null;
    }

    /** The session's buffer, on the same terms as {@link #getSession}. */
    private TerminalTextBuffer buffer() {
        TerminalTextBuffer started = buffer;
        if (started == null) throw new IllegalStateException("the shell has not started");
        return started;
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
    private void applyIconFont(@Nullable String icons) {
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
        // A scheme's own selection colour is meant to sit behind the text, so it is laid over it
        // more strongly than the blue wash is; still translucent, since it is drawn on top.
        if (palette.selection() != null) {
            Color own = palette.selection();
            return Color.color(own.getRed(), own.getGreen(), own.getBlue(), 0.55);
        }
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
        layoutSearchBar(w - barWidth);
        layoutLinkStatus(w - barWidth, h);

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
            buffer.processHistoryAndScreenLines(scrollOrigin, rows, runDrawer(g, new RunColumns()));
            if (selectionToVerify && !dragging) {
                selectionToVerify = false;
                dropSelectionIfRewritten();
            }
            drawPrediction(g, w);
            drawPromptMarks(g);
            drawSearchMatches(g);
            drawSelection(g);
            drawLinkUnderline(g);
            drawCursor(g);
            drawPaneFocus(g, w, h);
            updateScrollBar();
        } finally {
            buffer.unlock();
        }
    }

    /** Draws each style run it is handed at its row: {@code y - startRow}, against the scroll origin. */
    private StyledTextConsumer runDrawer(GraphicsContext g, RunColumns columns) {
        return new StyledTextConsumer() {
            @Override
            public void consume(int x, int y, TextStyle style, CharBuffer characters, int startRow) {
                // One copy of the run per frame, shared by the column bookkeeping and the draw.
                String text = characters.toString();
                int column = columns.columnOf(y, x, text);
                drawRun(g, column, y - startRow, style, text, columns.lastCells());
            }

            @Override
            public void consumeNul(int x, int y, int nulIndex, TextStyle style, CharBuffer characters, int startRow) {
                // Cells past the last written column. Only the background is meaningful —
                // drawing NUL as text would paint boxes across every short line.
                drawBackground(g, columns.columnOf(y, x, characters), y - startRow, characters.length(), style);
            }

            @Override
            public void consumeQueue(int x, int y, int nulIndex, int startRow) {}
        };
    }

    /**
     * The cursor's line as the type-ahead predictor expects it to look once the far end echoes, drawn
     * over the real one. Its own column bookkeeping, since the row has already been drawn once this
     * frame. Caller holds the lock.
     */
    private void drawPrediction(GraphicsContext g, double width) {
        com.termina.term.TypeAheadModel.Prediction prediction = predictionToDraw();
        if (prediction == null) return;
        int row = prediction.row() - scrollOrigin;
        if (row < 0 || row >= rows) return;
        g.setFill(palette.background());
        g.fillRect(0, row * lineHeight, width, lineHeight);
        prediction.line().process(prediction.row(), runDrawer(g, new RunColumns()), scrollOrigin);
    }

    /** The prediction, unless there is no session or a full-screen program owns the screen. */
    private com.termina.term.TypeAheadModel.@Nullable Prediction predictionToDraw() {
        if (session == null || display.isAlternateScreen()) return null;
        return session.typeAhead().prediction();
    }

    /** Whether this is the focused one of several panes, and outlined to say so. */
    private boolean paneFocusShown;

    /** Set by the pane group: true only while the tab is split and this is the pane in use. */
    void setPaneFocusShown(boolean shown) {
        if (paneFocusShown == shown) return;
        paneFocusShown = shown;
        markDirty();
    }

    /**
     * A one-pixel outline in the palette's blue around the pane being typed into. Drawn on the
     * canvas rather than as a CSS border, which would sit under the canvas and never be seen.
     */
    private void drawPaneFocus(GraphicsContext g, double w, double h) {
        if (!paneFocusShown) return;
        g.setStroke(palette.ansi()[4]);
        g.setLineWidth(1);
        g.strokeRect(0.5, 0.5, w - 1, h - 1);
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
    private void drawGlyphs(GraphicsContext g, String text, double x, double baseline, @Nullable Font iconFace) {
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
    private void drawCodePoint(
            GraphicsContext g, int codePoint, double x, double baseline, Font face, @Nullable Font iconFace) {
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
    private @Nullable Font iconFontFor(TextStyle style) {
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
        // Where the predictor expects the cursor once the far end catches up.
        com.termina.term.TypeAheadModel.Prediction prediction = predictionToDraw();
        if (prediction != null && prediction.row() == cursorRow && prediction.cursor() >= 0) {
            column = Math.min(prediction.cursor(), columns - 1);
        }
        if (row < 0 || row >= rows || column < 0) return;

        double x = column * charWidth;
        double y = row * lineHeight;
        // An imported scheme may name its cursor colour; the built-ins draw it in the foreground.
        g.setFill(palette.cursor() != null ? palette.cursor() : palette.foreground());

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
                TextStyle style = buffer().getStyleAt(slot, cursorRow);
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
        // The find bar is a child of this view, so its keys pass through this filter on the way to
        // it. They are the bar's: encoded here, every letter of a query would go to the shell.
        if (fromSearchBar(e)) return;
        // Escape closes the context menu rather than reaching the shell. This filter runs before
        // the menu would see the key, and Escape now encodes to a real byte, so without this the
        // menu stays open and the shell gets an ESC nobody asked for.
        ContextMenu shownMenu = shownContextMenu();
        if (shownMenu != null) {
            if (e.getCode() == KeyCode.ESCAPE) {
                shownMenu.hide();
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

        // A program that asked for the kitty keyboard protocol gets it; everything else, and every
        // key the protocol leaves alone — plain typing, Enter at a shell — takes the legacy route.
        int kittyFlags = session.keyboardFlags(display.isAlternateScreen());
        if (kittyFlags != 0) {
            byte[] kitty = kittyInput.pressed(e, kittyFlags, altIsMeta);
            if (kitty != null) {
                if (KeyTrace.enabled()) KeyTrace.log(e, kitty);
                if (kitty.length > 0) {
                    snapToLive();
                    session.send(kitty);
                }
                e.consume();
                return;
            }
        }

        // The selection is not cleared: it survives a keystroke, and goes only if the text under it
        // changes. See settledText.
        byte[] encoded = KeyEncoding.encodePressed(e, session::keyCode, altIsMeta);
        if (KeyTrace.enabled()) KeyTrace.log(e, encoded);
        if (encoded != null) {
            snapToLive();
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
                int next = ViewKeys.scrolledOrigin(action, scrollOrigin, rows, buffer().getHistoryLinesCount());
                if (next != scrollOrigin) {
                    scrollOrigin = next;
                    markDirty();
                }
            }
        }
        return true;
    }

    private void onKeyTyped(KeyEvent e) {
        if (fromSearchBar(e)) return;
        if (session == null || !session.isRunning()) return;
        int kittyFlags = session.keyboardFlags(display.isAlternateScreen());
        if (kittyFlags != 0) {
            byte[] kitty = kittyInput.typed(e, kittyFlags);
            if (kitty != null) {
                if (kitty.length > 0) {
                    snapToLive();
                    session.send(kitty);
                }
                e.consume();
                return;
            }
        }
        byte[] encoded = KeyEncoding.encodeTyped(e, altIsMeta);
        if (KeyTrace.enabled()) KeyTrace.log(e, encoded);
        if (encoded != null) {
            snapToLive();
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
            layoutEpoch++;
            remapMarksAfterResize();
            // JediTerm moved the endpoints as if they bounded a stream, by slot. A block is in
            // columns, and its rows have just been rewrapped into other rows: nothing it covered
            // is where it was.
            if (rectangular) selection = null;
        } else if (change.delta() != 0) {
            scrollOrigin = HistoryFollow.origin(scrollOrigin, change.delta(), history);
            linesScrolled += change.delta();
            TerminalSelection current = selection;
            if (current != null) current.shiftY(-change.delta());
            Point anchor = selectionAnchor;
            if (anchor != null) selectionAnchor = new Point(anchor.x, anchor.y - change.delta());
        }
        if (selection != null) selectionToVerify = true;
        outputPending = true;
        if (searchOpen) searchOutdated = true;
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
    private Point boundaryAt(double x, double y) {
        Point cell = boundaryColumnAt(x, y);
        return new Point(ColumnMap.slotAt(lockedLineTextAt(cell.y), cell.x), cell.y);
    }

    /** {@link #boundaryAt} as a column — what a block is measured in. */
    private Point boundaryColumnAt(double x, double y) {
        int column = (int) Math.round(x / charWidth);
        int row = (int) Math.floor(y / lineHeight);
        column = Math.max(0, Math.min(columns, column));
        row = Math.max(0, Math.min(rows - 1, row));
        return new Point(column, scrollOrigin + row);
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
        if (paneDragArmed) {
            paneDragArmed = false;
            e.consume();
            return;
        }
        boolean dragged = dragging;
        dragging = false;
        stopAutoScroll();
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.RELEASED)) {
            e.consume();
            return;
        }
        // The end of a drag is when the selection is made; a double or triple click was made at
        // its press. Anything else — a plain click — left no selection to announce.
        if (dragged && selection != null) settleSelection();
    }

    // ---------------------------------------------------------------- links

    /**
     * The link under the pointer, with everything the click and the underline need.
     *
     * <p>{@code file} is null for a URL and non-null for a path that was found on disk — a path
     * that was not is never a hover at all, so nothing here can be opened that does not exist. A
     * {@code file:} URL is a path in this sense: one that does not name an existing file on this
     * machine is no link, and is never handed to the desktop as a URL.
     *
     * @param spans the run split across the rows it occupies, since a wrapped URL is one link drawn
     *     on two lines
     * @param scrolledAt {@link #linesScrolled} when the spans were measured, so the underline rides
     *     with its text as output scrolls rather than staying on the screen row
     * @param epoch {@link #layoutEpoch} then; after a reflow the spans name nothing
     */
    private record Hover(
            LinkMatch match,
            java.nio.file.@Nullable Path file,
            java.util.List<LinkSpan> spans,
            boolean declared,
            long scrolledAt,
            int epoch) {}

    /**
     * What the buffer says is under the pointer, before the filesystem has been asked anything —
     * copied out under the lock, so the stat that decides it can run after the lock is released.
     *
     * @param file the path the link would open, not yet known to exist; null for a web URL
     * @param needsFile whether the link is nothing unless {@code file} exists: a path, or any
     *     {@code file:} URL
     */
    private record LinkCandidate(
            LinkMatch match,
            java.nio.file.@Nullable Path file,
            boolean needsFile,
            java.util.List<LinkSpan> spans,
            boolean declared,
            long scrolledAt,
            int epoch) {}

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

    private com.termina.link.@Nullable LinkActions linkActions;
    private @Nullable Hover hover;
    private @Nullable Hover menuLink;
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
        // The remembered answer lasts for one hover gesture: a file created since is found on the
        // next press of the modifier.
        if (!modifierDown) statPath = null;
        hover = modifierDown ? linkAt(x, y) : null;
        setCursor(hover == null ? javafx.scene.Cursor.DEFAULT : javafx.scene.Cursor.HAND);
        if (!sameHover(previous, hover)) {
            showLinkTarget(hover);
            markDirty();
        }
    }

    /**
     * Where a declared link goes, in a line at the bottom of the terminal while it is hovered — the
     * browser's status bubble. A link found in the text is its own address; a declared one can say
     * anything, and its target should be visible before the click rather than after.
     */
    private void showLinkTarget(@Nullable Hover target) {
        String address = target != null && target.declared() ? target.match().target() : null;
        boolean show = address != null;
        if (address != null) {
            if (linkStatus == null) {
                linkStatus = new javafx.scene.control.Label();
                linkStatus.getStyleClass().add("link-status");
                linkStatus.setMouseTransparent(true);
                // The middle goes, not the end: the host and the file name are what identify it.
                linkStatus.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
                linkStatus.setManaged(false);
                getChildren().add(linkStatus);
            }
            linkStatus.setText(address);
        }
        if (linkStatus != null && linkStatus.isVisible() != show) linkStatus.setVisible(show);
        requestLayout();
    }

    private javafx.scene.control.@Nullable Label linkStatus;

    /** Bottom left, inset, and cut to the terminal's width with an ellipsis in the middle. */
    private void layoutLinkStatus(double width, double height) {
        if (linkStatus == null || !linkStatus.isVisible()) return;
        double w = Math.min(linkStatus.prefWidth(-1), Math.max(0, width - 2 * SEARCH_BAR_INSET));
        double h = linkStatus.prefHeight(w);
        linkStatus.resizeRelocate(SEARCH_BAR_INSET, height - h - SEARCH_BAR_INSET, w, h);
    }

    private static boolean sameHover(@Nullable Hover a, @Nullable Hover b) {
        if (a == null || b == null) return a == b;
        return a.spans().equals(b.spans()) && a.match().equals(b.match());
    }

    /**
     * The link at a point in the view, or null.
     *
     * <p>Two halves. Under the buffer lock, only what the buffer says: the text, the declared link,
     * the path it would name. The filesystem is asked after the lock is released — a hung network
     * mount under the pointer would otherwise freeze the emulator along with the UI — and its answer
     * is remembered for the target, since moving along one link asks the same question for every
     * pixel of travel.
     */
    private @Nullable Hover linkAt(double x, double y) {
        LinkCandidate candidate = linkCandidateAt(x, y);
        if (candidate == null) return null;
        java.nio.file.Path file = candidate.file();
        if (file != null && !existsRemembered(file)) file = null;
        if (candidate.needsFile() && file == null) return null;
        return new Hover(
                candidate.match(),
                file,
                candidate.spans(),
                candidate.declared(),
                candidate.scrolledAt(),
                candidate.epoch());
    }

    /** The path {@link #existsRemembered} last asked about, and the answer. FX thread only. */
    private java.nio.file.@Nullable Path statPath;

    private boolean statExists;

    /** {@code Files.exists}, asked once per target for as long as the hover lasts. */
    private boolean existsRemembered(java.nio.file.Path file) {
        if (!file.equals(statPath)) {
            statExists = java.nio.file.Files.exists(file);
            statPath = file;
        }
        return statExists;
    }

    /** The buffer half of {@link #linkAt}: everything but the filesystem's answer. Takes the lock. */
    private @Nullable LinkCandidate linkCandidateAt(double x, double y) {
        if (buffer == null || linkActions == null) return null;
        if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) return null;

        int row = (int) Math.floor(y / lineHeight);
        if (row < 0 || row >= rows) return null;
        int column = (int) Math.floor(x / charWidth);
        if (column < 0 || column >= columns) return null;
        // Read once, here: the directory is the shell's and changes on its own.
        java.nio.file.Path cwd = shellDirectory();

        buffer.lock();
        try {
            int line = scrollOrigin + row;
            LogicalLine logical = logicalLineAt(line);
            if (logical == null) return null;
            int slot = ColumnMap.slotAt(lineTextAt(line), column);
            // A link the program declared outranks anything read off the text: it is the
            // program's own statement of where this goes, and its text need not look like a link.
            LinkCandidate declared = declaredLinkAt(logical, line, slot);
            if (declared != null) return declared;
            int index = logical.offsetOf(line) + slot;
            LinkMatch match = LinkScanner.at(logical.text(), index);
            if (match == null) return null;
            // This is the whole safety story for paths: a candidate is any word under the pointer,
            // and what makes it a link is that it resolves — against this tab's shell directory —
            // to something that is actually there. A pattern cannot tell a filename from a word;
            // the filesystem can, once the lock is released.
            String target = match.target();
            boolean path = match.kind() == LinkMatch.Kind.PATH;
            boolean fileUrl = !path && com.termina.link.LinkPaths.isFileUri(target);
            java.nio.file.Path file = path
                    ? com.termina.link.LinkPaths.resolve(target, cwd)
                    : fileUrl ? com.termina.link.LinkPaths.fromFileUri(target) : null;
            boolean needsFile = path || fileUrl;
            if (needsFile && file == null) return null;
            return new LinkCandidate(
                    match,
                    file,
                    needsFile,
                    logical.spansOf(match.start(), match.end()),
                    false,
                    linesScrolled,
                    layoutEpoch);
        } catch (RuntimeException e) {
            // The rows moved under us between the event and the read. There is no link here now.
            return null;
        } finally {
            buffer.unlock();
        }
    }

    /** The shell's directory as a path, or null when it is unknown or not one this platform reads. */
    private java.nio.file.@Nullable Path shellDirectory() {
        String cwd = display.getCwd();
        if (cwd == null || cwd.isBlank()) return null;
        try {
            return java.nio.file.Path.of(cwd);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The OSC 8 link on a cell, or null. Caller holds the lock. */
    private @Nullable ExplicitLink explicitLinkAt(int slot, int line) {
        try {
            return buffer().getStyleAt(slot, line) instanceof com.jediterm.terminal.HyperlinkStyle style
                            && style.getLinkInfo() instanceof ExplicitLink link
                    ? link
                    : null;
        } catch (RuntimeException outOfRange) {
            return null;
        }
    }

    /**
     * The OSC 8 link under a cell, as a hover: every cell on the logical line carrying the same
     * link is underlined, so a declared link wrapped onto a second row lights up as one. Caller
     * holds the lock, which is why nothing here asks the filesystem.
     */
    private @Nullable LinkCandidate declaredLinkAt(LogicalLine logical, int line, int slot) {
        ExplicitLink link = explicitLinkAt(slot, line);
        if (link == null) return null;
        List<LinkSpan> spans = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int start = -1;
        int end = -1;
        int offset = 0;
        for (int i = 0; i < logical.rowTexts().size(); i++) {
            int row = logical.firstLine() + i;
            String rowText = logical.rowTexts().get(i);
            int from = -1;
            for (int x = 0; x <= rowText.length(); x++) {
                boolean in = x < rowText.length() && explicitLinkAt(x, row) == link;
                if (in && from < 0) from = x;
                if (!in && from >= 0) {
                    spans.add(new LinkSpan(row, from, x));
                    text.append(rowText, from, x);
                    if (start < 0) start = offset + from;
                    end = offset + x;
                    from = -1;
                }
            }
            offset += rowText.length();
        }
        if (spans.isEmpty()) return null;
        String uri = link.uri();
        LinkMatch match = new LinkMatch(LinkMatch.Kind.URL, text.toString(), start, end, uri, 0, 0);
        // Held to the same rule as a file: URL read off the text — a file on this machine that
        // exists, or nothing. Another host's, or a share's, is never a link at all.
        boolean fileUrl = com.termina.link.LinkPaths.isFileUri(uri);
        java.nio.file.Path file = fileUrl ? com.termina.link.LinkPaths.fromFileUri(uri) : null;
        if (fileUrl && file == null) return null;
        return new LinkCandidate(match, file, fileUrl, spans, true, linesScrolled, layoutEpoch);
    }

    /**
     * Opens the hovered link.
     *
     * <p>A {@code file:} URL goes as a file or not at all. Handed to the desktop as a URL it is
     * ShellExecute on Windows, which fetches {@code file://host/share/x} over SMB and may run it.
     * The hover already refuses one that names no local file; this is the second lock on that door.
     */
    private void openLink(@Nullable Hover target) {
        if (target == null || linkActions == null) return;
        if (target.file() != null) {
            linkActions.openFile(
                    target.file(), target.match().line(), target.match().column());
        } else if (target.match().isUrl()
                && !com.termina.link.LinkPaths.isFileUri(target.match().target())) {
            linkActions.openUrl(target.match().target());
        }
    }

    /** What the link points at, for the clipboard: the URL, or the resolved file. */
    private String linkAddress(@Nullable Hover target) {
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
    private @Nullable LogicalLine logicalLineAt(int line) {
        // Bounded by the history that exists as well as by MAX_WRAP_ROWS: asking for row -1 with an
        // empty scrollback is not an exception but an ERROR line in JediTerm's own log, once per
        // mouse move, which turns a hover into a log flood.
        int floor = -buffer().getHistoryLinesCount();
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
        return match.kind() + (target.declared() ? "(osc8)" : "") + " text=<" + match.text() + "> opens=<"
                + linkAddress(target) + ">"
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
        if (target == null || target.epoch() != layoutEpoch) return;
        g.setStroke(palette.foreground());
        g.setLineWidth(1);
        // With its text, not on the screen row it was measured on: output arriving under a hover
        // moves the link up, and an underline left behind would mark whatever scrolled into place.
        int moved = (int) (linesScrolled - target.scrolledAt());
        for (LinkSpan span : target.spans()) {
            int line = span.line() - moved;
            int row = line - scrollOrigin;
            if (row < 0 || row >= rows) continue;
            String text = lineTextAt(line);
            double from = ColumnMap.columnAt(text, span.fromSlot()) * charWidth;
            double to = ColumnMap.columnAt(text, span.toSlot()) * charWidth;
            // Half a pixel down the grid line, or a 1px stroke straddles it and paints two grey rows
            // instead of one solid one.
            double y = Math.floor((row + 1) * lineHeight) - 1.5;
            g.strokeLine(from, y, to, y);
        }
    }

    // ---------------------------------------------------------------- context menu

    /**
     * The right-click menu and the items it brings up to date on every show. Built together on the
     * first right-click, so they are present together or not at all.
     */
    private record ContextItems(
            ContextMenu menu,
            MenuItem openLink,
            MenuItem copyLink,
            SeparatorMenuItem linkSeparator,
            MenuItem copy,
            MenuItem paste,
            @Nullable CheckMenuItem menuBar) {}

    private @Nullable ContextItems contextItems;
    private Runnable onOpenSettings = () -> {};
    private Runnable onNewTab = () -> {};
    private Runnable onNewWindow = () -> {};
    private Runnable onShowPalette = () -> {};
    private Runnable onShowAbout = () -> {};
    private ZoomMenuRow.@Nullable Actions zoomActions;
    private @Nullable ZoomMenuRow zoomRow;
    private @Nullable BooleanSupplier menuBarShown;
    private @Nullable Runnable onToggleMenuBar;

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

    public void setOnShowPalette(Runnable onShowPalette) {
        this.onShowPalette = onShowPalette == null ? () -> {} : onShowPalette;
    }

    public void setOnShowAbout(Runnable onShowAbout) {
        this.onShowAbout = onShowAbout == null ? () -> {} : onShowAbout;
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

        ContextItems items = contextItems();
        // Refreshed per show: the menu is built once and reused, while everything it reports on
        // moves underneath it. Copy and Paste depend on the selection and the clipboard; the
        // menu-bar checkbox on a setting the View menu, its own chord and the settings window can
        // all change while this menu sits built and invisible.
        items.copy().setDisable(!hasSelection());
        items.paste().setDisable(!Clipboard.getSystemClipboard().hasString());
        // The link items are the menu's answer to a gesture nobody was told about: they need no
        // modifier and they say what is there. Computed for the right-clicked cell, not the last
        // hovered one — the menu can be raised from the keyboard, and the pointer may be elsewhere.
        menuLink = linkAt(e.getX(), e.getY());
        boolean onLink = menuLink != null;
        items.openLink().setVisible(onLink);
        items.copyLink().setVisible(onLink);
        items.linkSeparator().setVisible(onLink);
        CheckMenuItem menuBarItem = items.menuBar();
        if (menuBarItem != null && menuBarShown != null) menuBarItem.setSelected(menuBarShown.getAsBoolean());
        if (zoomRow != null) zoomRow.refresh();
        items.menu().show(this, e.getScreenX(), e.getScreenY());
        e.consume();
    }

    private ContextItems contextItems() {
        ContextItems items = contextItems;
        if (items == null) contextItems = items = buildContextMenu();
        return items;
    }

    /** The context menu when it is on screen, or null. */
    private @Nullable ContextMenu shownContextMenu() {
        ContextItems items = contextItems;
        return items != null && items.menu().isShowing() ? items.menu() : null;
    }

    /**
     * Shows the menu as a right-click would and returns its scene, or null when the running program
     * owns the mouse and shift was not held. For the development capture hook — a ContextMenu is a
     * popup with its own scene, so the main window's snapshot cannot show it.
     */
    public javafx.scene.@Nullable Scene showContextMenuForCapture(double screenX, double screenY, boolean shift) {
        shiftOnLastPress = shift;
        ContextMenuEvent event =
                new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, screenX, screenY, false, null);
        onContextMenuRequested(event);
        ContextMenu shown = shownContextMenu();
        return shown == null ? null : shown.getScene();
    }

    /** Whether the context menu is open — for the development capture hook. */
    public boolean isContextMenuShowing() {
        return shownContextMenu() != null;
    }

    /**
     * Fires the context-menu item with this label, and reports whether one was found. For the
     * development capture hook: a menu item that renders correctly and does nothing when clicked
     * looks exactly like one that works, and every item here delegates to a callback that could be
     * left unwired.
     */
    public boolean fireContextMenuItemForCapture(String label) {
        ContextMenu contextMenu = contextItems().menu();
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

    private ContextItems buildContextMenu() {
        MenuItem copyItem = item(tr("menu.copy"), MenuIcons.copy(), this::copySelection);
        MenuItem pasteItem = item(tr("menu.paste"), MenuIcons.paste(), this::paste);
        MenuItem openLinkItem = item(tr("menu.openLink"), MenuIcons.openLink(), () -> openLink(menuLink));
        MenuItem copyLinkItem = item(tr("menu.copyLinkAddress"), MenuIcons.copy(), () -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(linkAddress(menuLink));
            Clipboard.getSystemClipboard().setContent(content);
        });

        // Hidden together with the items they divide: a separator left behind when the pointer was
        // not over a link is a rule across the top of the menu with nothing above it.
        SeparatorMenuItem linkSeparator = new SeparatorMenuItem();

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

        Runnable toggleMenuBar = onToggleMenuBar;
        CheckMenuItem menuBarItem = null;
        if (toggleMenuBar != null && menuBarShown != null) {
            // "Show Menu Bar", checked — not the View menu's "Hide Menu Bar". The two menus are
            // reached from different places: the View menu is inside the bar, so it can only ever
            // be opened while the bar is showing and "Hide" is the only move it has. This menu is
            // the way back once the bar is gone, so it has to state which of the two states you
            // are in rather than name a direction.
            //
            // No leading glyph, against the convention the rest of these follow: a CheckMenuItem
            // draws its tick in the graphic column, and an icon there either fights it or hides it.
            menuBarItem = new CheckMenuItem(tr("menu.showMenuBar"));
            menuBarItem.setOnAction(e -> toggleMenuBar.run());
            items.add(new SeparatorMenuItem());
            items.add(menuBarItem);
        }

        items.add(new SeparatorMenuItem());
        items.add(item(tr("menu.commandPalette"), MenuIcons.commandPalette(), () -> onShowPalette.run()));
        items.add(item(tr("menu.settings"), MenuIcons.settings(), () -> onOpenSettings.run()));
        items.add(item(tr("menu.about", com.termina.AppInfo.NAME), MenuIcons.about(), () -> onShowAbout.run()));

        ContextMenu menu = new ContextMenu(items.toArray(MenuItem[]::new));
        menu.setAutoHide(true);
        return new ContextItems(menu, openLinkItem, copyLinkItem, linkSeparator, copyItem, pasteItem, menuBarItem);
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
            rectangular = false;
            selection = all;
            selectionAnchor = new Point(0, -history);
        } finally {
            buffer.unlock();
        }
        settleSelection();
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
        ContextMenu shownMenu = shownContextMenu();
        if (shownMenu != null) {
            shownMenu.hide();
            e.consume();
            return;
        }
        // The mouse filters are on this Region, and a filter runs in the capturing phase — so a
        // press on the scrollbar reaches the terminal *before* the bar itself. Without this the
        // terminal starts a selection under every scroll drag.
        pressedOnScrollBar = fromScrollBar(e);
        if (pressedOnScrollBar) return; // deliberately not consumed: the bar still needs it
        // The same for the find bar, whose field and buttons take their own clicks. Latched with
        // the scrollbar's flag, which is what keeps the drag and release that follow away too.
        if (fromSearchBar(e)) {
            pressedOnScrollBar = true;
            return;
        }

        requestFocus();
        shiftOnLastPress = e.isShiftDown();

        // Before links and mouse reporting, for the reason the link gesture goes before reporting:
        // the chord is precisely the one that means "this is the terminal's, not the program's".
        paneDragArmed = e.getButton() == MouseButton.PRIMARY
                && onPaneDragStarted != null
                && PaneDrag.starts(MAC, e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown());
        if (paneDragArmed) {
            // No anchor left for a drag event that slips past the gesture to find.
            selectionAnchor = null;
            e.consume();
            return;
        }

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
            // Middle-click pastes the PRIMARY selection, as on every X11 terminal — read through
            // wl-paste or xclip, since JavaFX's Clipboard is CLIPBOARD and nothing else. Where there
            // is no PRIMARY (macOS, Windows) or no tool to read it, the clipboard. Reached only when
            // the program did not take the press, or Shift bypassed it.
            PrimarySelection.get(this::pasteText, this::paste);
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY) return;

        Point at = cellAt(e);
        switch (e.getClickCount()) {
            case 2 -> {
                selectWordAt(at);
                settleSelection();
            }
            case 3 -> {
                selectLineAt(at);
                settleSelection();
            }
            default -> {
                // A press with no drag clears the selection, matching every terminal: it is how
                // you dismiss a highlight without copying it.
                selectionAnchor = at;
                selection = null;
                settledText = null;
                // Option on macOS, as in Terminal and iTerm2; Alt elsewhere, as in Windows Terminal
                // and Konsole. Ctrl+Alt works too, and is the one to use where the window manager
                // takes Alt+drag to move windows, as GNOME and KDE do.
                blockDrag = e.isAltDown();
                anchorColumn = Math.max(0, Math.min(columns - 1, (int) Math.floor(e.getX() / charWidth)));
                dragging = true;
                autoScroll.reset();
            }
        }
        markDirty();
    }

    private void onMouseMoved(MouseEvent e) {
        if (fromSearchBar(e)) return;
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
        if (fromSearchBar(e)) return;
        onLinkModifierChanged(e);
        // Always, flags or not: the release is what takes the key out of the held set, and a key
        // left in it would make its next press look like a repeat.
        if (session != null && session.isRunning()) {
            byte[] kitty = kittyInput.released(e, session.keyboardFlags(display.isAlternateScreen()), altIsMeta);
            if (kitty != null) session.send(kitty);
        }
    }

    private void onMouseDragged(MouseEvent e) {
        if (pressedOnScrollBar) return;
        if (paneDragArmed) {
            e.consume();
            return;
        }
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.DRAGGED)) {
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY || selectionAnchor == null) return;
        dragX = e.getX();
        dragY = e.getY();
        extendDragTo(dragX, dragY);
        double past = pastEdge(dragY);
        if (past != 0 && !autoScrolling) {
            autoScrolling = true;
            autoScroll.reset();
            wake();
        } else if (past == 0) {
            stopAutoScroll();
        }
    }

    /** Starts carrying the pane, once a press with the pane-drag chord has moved far enough. */
    private void onDragDetected(MouseEvent e) {
        if (!paneDragArmed || onPaneDragStarted == null) return;
        paneDragArmed = false;
        var board = startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
        ClipboardContent content = new ClipboardContent();
        content.put(PaneDrag.FORMAT, "pane");
        board.setContent(content);
        // A small picture of the pane under the pointer: what is being moved follows the pointer.
        var parameters = new javafx.scene.SnapshotParameters();
        double scale = Math.min(1, 240 / Math.max(1, getWidth()));
        parameters.setTransform(javafx.scene.transform.Transform.scale(scale, scale));
        board.setDragView(snapshot(parameters, null));
        onPaneDragStarted.accept(this);
        e.consume();
    }

    /** Extends the drag's selection to a pointer position, clamped to the pane. */
    private void extendDragTo(double x, double y) {
        Point anchor = selectionAnchor;
        if (anchor == null) return;
        if (blockDrag) {
            Point to = boundaryColumnAt(x, y);
            if (selection == null && !SelectionGeometry.leftPressCell(new Point(anchorColumn, anchor.y), to.y, to.x))
                return;
            rectangular = true;
            selection = SelectionGeometry.blockDrag(anchorColumn, anchor.y, to.y, to.x);
        } else {
            Point to = boundaryAt(x, y);
            // Nothing until the pointer has left the cell it pressed in, so a plain click never
            // leaves a one-cell selection behind — unless one exists already, in which case the
            // drag is extending it and every movement counts.
            if (selection == null && !SelectionGeometry.leftPressCell(anchor, to.y, to.x)) return;
            rectangular = false;
            selection = SelectionGeometry.drag(anchor, to.y, to.x);
        }
        markDirty();
    }

    /**
     * How far past the top (negative) or bottom (positive) edge the pointer is, in pixels; zero
     * inside. The bottom edge is the last whole row's, not the pane's, which can have a sliver below.
     */
    private double pastEdge(double y) {
        if (y < 0) return y;
        double bottom = rows * lineHeight;
        return y >= bottom ? y - bottom + 1 : 0;
    }

    /** One frame of a drag held past the edge: scroll by time and distance, and follow the pointer. */
    private void stepAutoScroll(long now) {
        if (!dragging || buffer == null || selectionAnchor == null) {
            stopAutoScroll();
            return;
        }
        double past = pastEdge(dragY);
        if (past == 0) {
            stopAutoScroll();
            return;
        }
        int lines = autoScroll.step(now, Math.abs(past), lineHeight);
        if (lines == 0) return;
        int history = buffer.getHistoryLinesCount();
        int next = Math.max(-history, Math.min(0, scrollOrigin + (past < 0 ? -lines : lines)));
        if (next == scrollOrigin) return;
        scrollOrigin = next;
        extendDragTo(dragX, dragY);
    }

    private void stopAutoScroll() {
        autoScrolling = false;
        autoScroll.reset();
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
        TerminalSelection word = wordSelection(at, buffer());
        rectangular = false;
        selection = word;
        selectionAnchor = new Point(word.getStart().x, word.getStart().y);
    }

    private void selectLineAt(Point at) {
        TerminalSelection line = new TerminalSelection(new Point(0, at.y));
        line.updateEnd(new Point(Math.max(columns, lockedLineTextAt(at.y).length()), at.y));
        rectangular = false;
        selection = line;
        selectionAnchor = new Point(0, at.y);
    }

    public boolean hasSelection() {
        return selection != null;
    }

    /** The text a selection stands for, a stream's or a block's. The caller holds the lock. */
    private String selectedText(TerminalSelection current) {
        if (rectangular) return BlockSelection.text(current, buffer());
        // JediTerm's extractor, not ours: it understands that a wrapped line is one logical line
        // and must not gain a newline in the middle.
        return SelectionUtil.getSelectionText(current, buffer);
    }

    /** {@link #selectedText} for a caller without the lock; null when there is no selection. */
    private @Nullable String lockedSelectedText() {
        TerminalSelection current = selection;
        if (current == null || buffer == null) return null;
        buffer.lock();
        try {
            return selectedText(current);
        } finally {
            buffer.unlock();
        }
    }

    /** Copies the selection, returning false when there was nothing to copy. */
    public boolean copySelection() {
        String text = lockedSelectedText();
        if (text == null || text.isEmpty()) return false;
        toClipboard(text);
        return true;
    }

    private static void toClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * A selection has been made — at the end of a drag, on a double or triple click, by Select All.
     * Its text is remembered (see {@link #settledText}), offered as PRIMARY where there is one, and
     * copied to the clipboard if copy-on-select is on.
     */
    private void settleSelection() {
        String text = lockedSelectedText();
        settledText = text;
        if (text == null || text.isEmpty()) return;
        PrimarySelection.set(text);
        if (copyOnSelect) toClipboard(text);
    }

    /**
     * Drops the selection when the text under it is no longer the text that was selected. Under the
     * lock, from the frame, so the comparison and the highlight agree.
     */
    private void dropSelectionIfRewritten() {
        TerminalSelection current = selection;
        String settled = settledText;
        if (current == null || settled == null) return;
        int top = Math.min(current.getStart().y, current.getEnd().y);
        int bottom = Math.max(current.getStart().y, current.getEnd().y);
        if (bottom - top > VERIFIED_ROWS) return;
        if (!settled.equals(selectedText(current))) {
            selection = null;
            settledText = null;
        }
    }

    public void setCopyOnSelect(boolean copyOnSelect) {
        this.copyOnSelect = copyOnSelect;
    }

    public void clearSelection() {
        settledText = null;
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
        if (rectangular) {
            for (int row = 0; row < rows; row++) {
                int[] span = SelectionGeometry.blockOnRow(current, scrollOrigin + row);
                if (span == null) continue;
                g.fillRect(span[0] * charWidth, row * lineHeight, (span[1] - span[0]) * charWidth, lineHeight);
            }
            return;
        }
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
        if (buffer == null || fromSearchBar(e)) return;
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
        pasteText(Clipboard.getSystemClipboard().getString());
    }

    /** Pastes text from wherever it came — the clipboard, or PRIMARY on a middle-click. */
    private void pasteText(String raw) {
        String text = KeyEncoding.sanitise(raw);
        if (text.isEmpty() || session == null) return;
        if (PasteSafety.needsConfirmation(text) && !pasteConfirmer.test(text)) return;
        snapToLive();
        session.send(KeyEncoding.encodePaste(text, display.isBracketedPaste()));
    }

    public void setAltIsMeta(boolean altIsMeta) {
        this.altIsMeta = altIsMeta;
    }

    // ---------------------------------------------------------------- operating-system commands

    /** What the shell has said about its commands with OSC 133. */
    private final PromptMarks marks = new PromptMarks();

    /** The marks as logical positions, taken just before a resize the view asked for. */
    private volatile java.util.@Nullable Map<PromptMarks.Pos, int[]> marksBeforeResize;

    /** Whether a program may set the clipboard with OSC 52. A setting; see {@link #onOsc}. */
    private volatile boolean clipboardWrites = true;

    public void setClipboardWrites(boolean allowed) {
        this.clipboardWrites = allowed;
    }

    /** Whether keystrokes may be predicted over SSH. A setting; see {@link com.termina.term.TypeAheadModel}. */
    private volatile boolean typeAheadAllowed = true;

    public void setTypeAheadAllowed(boolean allowed) {
        this.typeAheadAllowed = allowed;
    }

    /**
     * One of the commands the session takes out of the stream, on the emulator thread, at the point
     * in the output where it was written.
     */
    private void onOsc(int code, String payload) {
        switch (code) {
            case com.termina.term.OscSequences.SEMANTIC_PROMPT ->
                com.termina.term.OscSequences.promptMark(payload).ifPresent(this::onPromptMark);
            case com.termina.term.OscSequences.CURRENT_DIRECTORY -> onDirectoryReported(payload);
            case com.termina.term.OscSequences.NOTIFY, com.termina.term.OscSequences.NOTIFY_RXVT -> {
                if (code == com.termina.term.OscSequences.NOTIFY) {
                    com.termina.term.OscSequences.conEmuDirectory(payload).ifPresent(this::onWindowsDirectoryReported);
                }
                com.termina.term.OscSequences.notification(code, payload).ifPresent(this::onNotification);
            }
            case com.termina.term.OscSequences.CLIPBOARD -> {
                if (!clipboardWrites) return;
                com.termina.term.OscSequences.clipboardText(payload)
                        .ifPresent(text -> Platform.runLater(() -> {
                            ClipboardContent content = new ClipboardContent();
                            content.putString(text);
                            Clipboard.getSystemClipboard().setContent(content);
                        }));
            }
            default -> {}
        }
    }

    /** Set once the shell has reported a directory where a report wins; the OS's answers then stop. */
    private volatile boolean directoryReported;

    /**
     * Where the shell says it is — believed only on Windows.
     *
     * <p>On Linux and macOS {@code ProcessCwd} reads the shell's real directory, which a prompt
     * cannot get wrong and which every shell has whether or not it was set up to report one — and an
     * OSC 7 from a shell inside {@code ssh} would otherwise claim this tab for another machine's
     * directory. On Windows the OS can be asked too, but a WSL tab's process is {@code wsl.exe},
     * whose directory is not the Linux shell's; there the shell's own report wins once it has made
     * one. See {@link com.termina.pty.ProcessCwd#reportedDirectoryWins}.
     */
    private void onDirectoryReported(String payload) {
        if (!com.termina.pty.ProcessCwd.reportedDirectoryWins()) return;
        com.termina.term.OscSequences.localDirectory(
                        payload,
                        com.termina.link.LocalHost::isLocal,
                        path -> java.nio.file.Files.isDirectory(java.nio.file.Path.of(path)))
                .ifPresent(this::reportedDirectory);
    }

    private void reportedDirectory(String path) {
        directoryReported = true;
        display.setCwd(path);
    }

    /**
     * The directory an OSC {@code 9;9} reports, ConEmu's form, which Windows shells set up for
     * Windows Terminal emit instead of OSC 7. Held to the same rules: a directory on this machine
     * that exists, and only where a report wins.
     */
    private void onWindowsDirectoryReported(String path) {
        if (!com.termina.pty.ProcessCwd.reportedDirectoryWins()) return;
        try {
            if (java.nio.file.Files.isDirectory(java.nio.file.Path.of(path))) reportedDirectory(path);
        } catch (RuntimeException invalid) {
            // Not a path this platform can read.
        }
    }

    /** Told on the FX thread when a program asks for a notification; see {@link #onNotification}. */
    private java.util.function.BiConsumer<com.termina.term.OscSequences.Notification, Boolean> onNotify =
            (n, post) -> {};

    public void setOnNotify(
            java.util.function.BiConsumer<com.termina.term.OscSequences.Notification, Boolean> onNotify) {
        this.onNotify = onNotify == null ? (n, post) -> {} : onNotify;
    }

    /** A program cannot post more than one notification per pane in this long; the rest only mark. */
    static final long NOTIFY_INTERVAL_MS = 2_000;

    /** When this pane last posted one. Written on the FX thread only. */
    private long lastNotifiedMs = Long.MIN_VALUE / 2;

    /**
     * A program asked for a notification. The window decides whether it reaches the desktop — not
     * while you are looking at the pane — but the pane decides how often: a loop printing OSC 9
     * would otherwise fill the screen with bubbles. Every one still marks the tab.
     */
    private void onNotification(com.termina.term.OscSequences.Notification notification) {
        Platform.runLater(() -> {
            long now = System.currentTimeMillis();
            boolean post = now - lastNotifiedMs >= NOTIFY_INTERVAL_MS;
            if (post) lastNotifiedMs = now;
            onNotify.accept(notification, post);
        });
    }

    /** Records a mark at the cursor, in coordinates that survive scrolling. */
    private void onPromptMark(com.termina.term.OscSequences.PromptMark mark) {
        if (buffer == null || display.isAlternateScreen()) return;
        PromptMarks.Pos at;
        buffer.lock();
        try {
            // The emulator's own cursor, which is exactly where the program's output has reached —
            // the display's copy is told of moves, and this is the thread doing the moving.
            var terminal = getSession().getTerminal();
            int row = terminal.getCursorY() - 1;
            int slot = ColumnMap.slotAt(lineTextAt(row), terminal.getCursorX() - 1);
            at = new PromptMarks.Pos(row + linesScrolled, slot);
            marks.dropAbove(linesScrolled - buffer.getHistoryLinesCount());
        } finally {
            buffer.unlock();
        }
        switch (mark.kind()) {
            case 'A' -> marks.prompt(at);
            case 'C' -> marks.outputStarts(at);
            case 'D' -> {
                marks.finished(at, mark.exitCode());
                markDirty();
            }
            default -> {}
        }
    }

    /** Whether the shell has marked any prompts — what the jump and select commands need. */
    public boolean hasPromptMarks() {
        return !marks.isEmpty();
    }

    /**
     * Scrolls to the previous or next prompt the shell marked: the top of the view lands on it, as
     * in iTerm2 and Windows Terminal. Past the newest one, back to the live screen.
     */
    public void jumpToPrompt(boolean previous) {
        if (buffer == null || display.isAlternateScreen()) return;
        int next;
        buffer.lock();
        try {
            long top = scrollOrigin + linesScrolled;
            List<PromptMarks.Command> all = marks.all();
            PromptMarks.Command target = previous ? PromptMarks.previous(all, top) : PromptMarks.next(all, top);
            if (target == null) {
                if (previous) return;
                next = 0;
            } else {
                next = (int) (target.prompt().line() - linesScrolled);
            }
            next = Math.max(-buffer.getHistoryLinesCount(), Math.min(0, next));
        } finally {
            buffer.unlock();
        }
        if (next != scrollOrigin) {
            scrollOrigin = next;
            markDirty();
        }
    }

    /**
     * Selects what the last finished command printed, from where its output began to where the
     * shell said it ended — ready for Copy.
     *
     * @return false when no command has both marks, so the caller can say why nothing happened
     */
    public boolean selectLastOutput() {
        if (buffer == null || display.isAlternateScreen()) return false;
        PromptMarks.Command command = PromptMarks.lastWithOutput(marks.all());
        if (command == null) return false;
        // lastWithOutput picks a command that has both; the type does not say so.
        PromptMarks.Pos outputAt = command.output();
        PromptMarks.Pos endAt = command.end();
        if (outputAt == null || endAt == null) return false;
        buffer.lock();
        try {
            int from = (int) (outputAt.line() - linesScrolled);
            int to = (int) (endAt.line() - linesScrolled);
            if (to < -buffer.getHistoryLinesCount()) return false;
            from = Math.max(from, -buffer.getHistoryLinesCount());
            TerminalSelection output = new TerminalSelection(new Point(outputAt.column(), from));
            output.updateEnd(new Point(endAt.column(), to));
            rectangular = false;
            selection = output;
            selectionAnchor = new Point(outputAt.column(), from);
            if (from < scrollOrigin || from >= scrollOrigin + rows) {
                scrollOrigin = Math.max(-buffer.getHistoryLinesCount(), Math.min(0, from));
            }
        } finally {
            buffer.unlock();
        }
        settleSelection();
        markDirty();
        return true;
    }

    /**
     * A command that failed gets a short bar in the left margin of its prompt — enough to find the
     * one that broke in a screen of output, and nothing at all for the ones that worked.
     */
    private void drawPromptMarks(GraphicsContext g) {
        if (marks.isEmpty() || display.isAlternateScreen()) return;
        Color red = palette.ansi()[1];
        for (PromptMarks.Command command : marks.all()) {
            if (!command.failed()) continue;
            int row = (int) (command.prompt().line() - linesScrolled) - scrollOrigin;
            if (row < 0 || row >= rows) continue;
            g.setFill(red);
            g.fillRect(0, row * lineHeight + 2, FAILED_MARK_WIDTH, lineHeight - 4);
        }
    }

    private static final double FAILED_MARK_WIDTH = 2;

    /** Every logical position a mark has, against the rows as they are now. Caller is on the FX thread. */
    private java.util.@Nullable Map<PromptMarks.Pos, int[]> logicalMarks() {
        if (buffer == null || marks.isEmpty()) return null;
        buffer.lock();
        try {
            LogicalLines lines = logicalLines();
            java.util.Map<PromptMarks.Pos, int[]> out = new java.util.HashMap<>();
            for (PromptMarks.Command command : marks.all()) {
                for (PromptMarks.Pos pos : new PromptMarks.Pos[] {command.prompt(), command.output(), command.end()}) {
                    if (pos == null) continue;
                    int[] logical = lines.toLogical((int) (pos.line() - linesScrolled), pos.column());
                    if (logical != null) out.put(pos, logical);
                }
            }
            return out;
        } finally {
            buffer.unlock();
        }
    }

    /**
     * Puts the marks back on their text after a reflow. On the emulator thread, under the lock,
     * from the model listener. A resize the view did not ask for has no snapshot, and its marks
     * cannot be placed: they are dropped rather than left pointing at other text.
     */
    private void remapMarksAfterResize() {
        java.util.Map<PromptMarks.Pos, int[]> before = marksBeforeResize;
        marksBeforeResize = null;
        if (marks.isEmpty()) return;
        if (before == null) {
            marks.clear();
            return;
        }
        LogicalLines after = logicalLines();
        marks.remap(pos -> {
            int[] logical = before.get(pos);
            int[] at = logical == null ? null : after.fromLogical(logical[0], logical[1]);
            return at == null ? null : new PromptMarks.Pos(at[0] + linesScrolled, at[1]);
        });
    }

    /** The whole buffer's rows grouped into logical lines. Caller holds the lock. */
    private LogicalLines logicalLines() {
        int history = buffer().getHistoryLinesCount();
        int height = buffer().getHeight();
        boolean[] wrapped = new boolean[history + height];
        for (int i = 0; i < wrapped.length; i++) wrapped[i] = isWrapped(i - history);
        return LogicalLines.of(-history, wrapped, buffer().getWidth());
    }

    /** The face and size this pane draws in — for the development capture hook. */
    public String fontReportForCapture() {
        return font == null ? "-" : font.getFamily() + "@" + fontSize;
    }

    /** The selection's text, without touching the clipboard — for the development capture hook. */
    public String selectionTextForCapture() {
        String text = lockedSelectedText();
        return text == null ? "<none>" : text;
    }

    /**
     * Plants a prediction at the cursor, as the predictor would on a slow link — for the development
     * capture hook, which has no slow link to make one appear by itself.
     */
    public void predictForCapture(String text) {
        if (session == null) return;
        buffer().lock();
        try {
            int at = session.getTerminal().getCursorX() - 1;
            for (int i = 0; i < text.length(); i++) session.typeAhead().insertCharacter(text.charAt(i), at + i);
            session.typeAhead().moveCursor(at + text.length());
        } finally {
            buffer().unlock();
        }
        markDirty();
    }

    /**
     * Fires whole keystrokes at the view — press, then the typed character a keyboard would produce,
     * then release — by {@link KeyChord} token ({@code C-a}, {@code S-enter}, {@code escape}), or a
     * bare key by {@code key:NAME} ({@code key:SHIFT}). For the development capture hook: the kitty
     * protocol is about all three events and their order, which one synthetic press cannot show.
     */
    public String fireKeystrokesForCapture(String tokens) {
        StringBuilder out = new StringBuilder();
        for (String raw : tokens.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            KeyCode code;
            boolean shift = false, control = false, alt = false, meta = false;
            if (token.startsWith("key:")) {
                code = KeyCode.valueOf(token.substring(4));
                shift = code == KeyCode.SHIFT;
                control = code == KeyCode.CONTROL;
            } else {
                var combination = (javafx.scene.input.KeyCodeCombination) KeyChord.toCombination(token);
                if (combination == null) {
                    out.append(token).append("=? ");
                    continue;
                }
                code = combination.getCode();
                shift = combination.getShift() == javafx.scene.input.KeyCombination.ModifierValue.DOWN;
                control = combination.getControl() == javafx.scene.input.KeyCombination.ModifierValue.DOWN;
                alt = combination.getAlt() == javafx.scene.input.KeyCombination.ModifierValue.DOWN;
                meta = combination.getMeta() == javafx.scene.input.KeyCombination.ModifierValue.DOWN;
            }
            String text = switch (code) {
                case ENTER -> "\r";
                case TAB -> "\t";
                case SPACE -> " ";
                default ->
                    code.isLetterKey()
                                    || code.isDigitKey()
                                    || code.getChar().length() == 1
                                            && code.getChar().charAt(0) < 0x7f
                            ? (shift
                                    ? code.getChar().toUpperCase(java.util.Locale.ROOT)
                                    : code.getChar().toLowerCase(java.util.Locale.ROOT))
                            : "";
            };
            if (code.isModifierKey()
                    || code.ordinal() >= KeyCode.F1.ordinal() && code.ordinal() <= KeyCode.F24.ordinal()) text = "";
            String typed = control && code.isLetterKey()
                    ? String.valueOf((char) (code.getChar().charAt(0) - 'A' + 1))
                    : text;
            javafx.event.Event.fireEvent(
                    this, new KeyEvent(KeyEvent.KEY_PRESSED, "", text, code, shift, control, alt, meta));
            if (!typed.isEmpty()) {
                javafx.event.Event.fireEvent(
                        this,
                        new KeyEvent(KeyEvent.KEY_TYPED, typed, "", KeyCode.UNDEFINED, shift, control, alt, meta));
            }
            javafx.event.Event.fireEvent(
                    this, new KeyEvent(KeyEvent.KEY_RELEASED, "", text, code, false, false, false, false));
            out.append(token).append(' ');
        }
        return out.toString().trim();
    }

    /** The kitty keyboard flags a program has set on the screen in front — for the capture hook. */
    public int keyboardFlagsForCapture() {
        return session == null ? 0 : session.keyboardFlags(display.isAlternateScreen());
    }

    /** Where the view is scrolled to — for the development capture hook. */
    public int scrollOriginForCapture() {
        return scrollOrigin;
    }

    /** What the marks are, for the development capture hook. */
    public String promptMarkReport() {
        StringBuilder out = new StringBuilder("marks=" + marks.all().size());
        buffer().lock();
        try {
            for (PromptMarks.Command command : marks.all()) {
                out.append(" [prompt@").append(command.prompt().line() - linesScrolled);
                if (command.output() != null)
                    out.append(" out@").append(command.output().line() - linesScrolled);
                if (command.end() != null)
                    out.append(" end@")
                            .append(command.end().line() - linesScrolled)
                            .append(" exit=")
                            .append(command.exitCode());
                out.append(']');
            }
        } finally {
            buffer().unlock();
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- search

    /**
     * The results of the last search, as they were when it ran.
     *
     * <p>Immutable and replaced whole, so the emulator thread never has to touch it: the lines in it
     * are where the matches were at {@code scrolledAt}, and {@link #linesScrolled} says how far they
     * have moved since. A reflow or a cleared history ({@code epoch}) makes them meaningless rather
     * than merely moved, and they are recomputed.
     *
     * @param current the match the user is on, or -1 before one is chosen
     */
    private record SearchState(List<ScrollbackSearch.Match> matches, int current, long scrolledAt, int epoch) {
        static final SearchState NONE = new SearchState(List.of(), -1, 0, -1);
    }

    private @Nullable SearchBar searchBar;

    private volatile SearchState search = SearchState.NONE;

    /** Read by the model listener on the emulator thread: only an open search needs refreshing. */
    private volatile boolean searchOpen;

    /** Output arrived, or the rows were rebuilt, since the results were computed. */
    private volatile boolean searchOutdated;

    /** The pause between output arriving under an open search and the search running again. */
    private final PauseTransition searchRefresh = new PauseTransition(Duration.millis(250));

    /**
     * Opens the find bar, or puts the caret back in it.
     *
     * <p>A short selection on one line becomes the query, as in every editor: selecting the word and
     * pressing the chord is the quickest way to ask where else it is.
     */
    public void showSearch() {
        if (searchBar == null) {
            searchBar = new SearchBar(new SearchBar.Listener() {
                @Override
                public void queryChanged(String query, boolean regex) {
                    runSearch(false, true);
                }

                @Override
                public void step(boolean older) {
                    stepSearch(older);
                }

                @Override
                public void closed() {
                    hideSearch();
                }
            });
            searchBar.setManaged(false);
            getChildren().add(searchBar);
        }
        String seed = selectionForSearch();
        boolean wasOpen = searchOpen;
        searchOpen = true;
        searchBar.setVisible(true);
        requestLayout();
        // Setting a different query runs the search through the field's listener; reopening on the
        // same one has to run it here, since the results were dropped when the bar closed.
        if (seed != null && !seed.equals(searchBar.query())) {
            searchBar.setQuery(seed, false);
        } else if (!wasOpen) {
            runSearch(false, true);
        }
        searchBar.focusField();
    }

    /** Closes the find bar, drops the highlights and gives the keyboard back to the shell. */
    public void hideSearch() {
        if (!searchOpen) return;
        searchOpen = false;
        searchOutdated = false;
        searchRefresh.stop();
        search = SearchState.NONE;
        if (searchBar != null) searchBar.setVisible(false);
        markDirty();
        requestFocus();
    }

    public boolean isSearchShowing() {
        return searchOpen;
    }

    /** Whether the find bar's field has the keyboard — the one case the focus guard must leave alone. */
    public boolean searchHasFocus() {
        return searchOpen && searchBar != null && searchBar.fieldIsFocused();
    }

    /** Steps to the next match, opening the bar first if it is closed — what Find Next does. */
    public void findNext(boolean older) {
        if (!searchOpen) {
            showSearch();
            return;
        }
        stepSearch(older);
    }

    /** The selection's text if it is short and on one line, which is what makes a useful query. */
    private @Nullable String selectionForSearch() {
        String text = lockedSelectedText();
        if (text == null || text.isBlank() || text.length() > 200 || text.contains("\n")) return null;
        return text;
    }

    /**
     * Searches the whole buffer for the bar's query.
     *
     * <p>The rows are copied out under the lock and matched outside it, so the emulator is held up
     * for a copy of the text rather than for a regular expression over the scrollback.
     *
     * @param keepPlace stay on the match the user had stepped to, if it is still there — what a
     *     refresh after new output wants; a new query starts over from the bottom of the view
     * @param reveal scroll the current match into view
     */
    private void runSearch(boolean keepPlace, boolean reveal) {
        if (searchBar == null || buffer == null) return;
        java.util.regex.Pattern pattern = ScrollbackSearch.compile(searchBar.query(), searchBar.isRegex());
        boolean valid = pattern != null || searchBar.query().isEmpty();
        List<String> texts = new ArrayList<>();
        List<Boolean> wrapped = new ArrayList<>();
        int firstLine;
        long scrolledAt;
        int epoch;
        int[] keep = null;
        buffer.lock();
        try {
            SearchState previous = search;
            if (keepPlace && previous.current() >= 0 && previous.epoch() == layoutEpoch) {
                ScrollbackSearch.Match was = previous.matches().get(previous.current());
                keep = new int[] {was.firstLine() - (int) (linesScrolled - previous.scrolledAt()), was.fromSlot()};
            }
            int history = buffer.getHistoryLinesCount();
            int height = buffer.getHeight();
            firstLine = -history;
            for (int line = -history; line < height; line++) {
                var row = buffer.getLine(line);
                texts.add(row == null ? "" : row.getText());
                wrapped.add(row != null && row.isWrapped());
            }
            scrolledAt = linesScrolled;
            epoch = layoutEpoch;
        } finally {
            buffer.unlock();
        }
        List<ScrollbackSearch.Match> matches = ScrollbackSearch.find(texts, wrapped, firstLine, pattern);
        int current = keep == null ? -1 : ScrollbackSearch.indexAt(matches, keep[0], keep[1]);
        if (current < 0) current = ScrollbackSearch.initial(matches, scrollOrigin + rows - 1);
        search = new SearchState(matches, current, scrolledAt, epoch);
        searchBar.showCount(current, matches.size(), valid);
        if (reveal) revealMatch();
        markDirty();
    }

    private void stepSearch(boolean older) {
        if (searchOutdated || search.epoch() != layoutEpoch) {
            searchOutdated = false;
            runSearch(true, false);
        }
        SearchState state = search;
        int next = ScrollbackSearch.step(state.current(), state.matches().size(), older);
        search = new SearchState(state.matches(), next, state.scrolledAt(), state.epoch());
        if (searchBar != null) searchBar.showCount(next, state.matches().size(), true);
        revealMatch();
        markDirty();
    }

    /** Scrolls the current match to the middle of the view, unless it is already in it. */
    private void revealMatch() {
        SearchState state = search;
        if (state.current() < 0 || buffer == null || display.isAlternateScreen()) return;
        ScrollbackSearch.Match match = state.matches().get(state.current());
        int moved = (int) (linesScrolled - state.scrolledAt());
        int first = match.firstLine() - moved;
        int last = match.lastLine() - moved;
        if (first >= scrollOrigin && last < scrollOrigin + rows) return;
        int history = buffer.getHistoryLinesCount();
        int next = Math.max(-history, Math.min(0, first - rows / 2));
        if (next != scrollOrigin) {
            scrollOrigin = next;
            markDirty();
        }
    }

    /**
     * Washes every visible match, the current one more strongly and outlined, so it can be told
     * from the rest at a glance. Over the text rather than behind it, like the selection, and in
     * the palette's yellow, which every theme already renders legibly as a background.
     */
    private void drawSearchMatches(GraphicsContext g) {
        SearchState state = search;
        if (!searchOpen || state.matches().isEmpty()) return;
        if (state.epoch() != layoutEpoch) {
            // Rebuilt rows: the lines recorded no longer name these matches. Drawn again once the
            // refresh has run, rather than painted somewhere they are not.
            searchOutdated = true;
            if (searchRefresh.getStatus() != javafx.animation.Animation.Status.RUNNING) searchRefresh.playFromStart();
            return;
        }
        int moved = (int) (linesScrolled - state.scrolledAt());
        Color yellow = palette.ansi()[3];
        Color wash = Color.color(yellow.getRed(), yellow.getGreen(), yellow.getBlue(), SEARCH_ALPHA);
        Color strong = Color.color(yellow.getRed(), yellow.getGreen(), yellow.getBlue(), CURRENT_MATCH_ALPHA);
        for (int i = 0; i < state.matches().size(); i++) {
            ScrollbackSearch.Match match = state.matches().get(i);
            if (match.lastLine() - moved < scrollOrigin || match.firstLine() - moved >= scrollOrigin + rows) continue;
            boolean current = i == state.current();
            for (ScrollbackSearch.Span span : match.spans()) {
                int line = span.line() - moved;
                int row = line - scrollOrigin;
                if (row < 0 || row >= rows) continue;
                String text = lineTextAt(line);
                double x = ColumnMap.columnAt(text, span.fromSlot()) * charWidth;
                double to = ColumnMap.columnAt(text, span.toSlot()) * charWidth;
                double y = row * lineHeight;
                g.setFill(current ? strong : wash);
                g.fillRect(x, y, to - x, lineHeight);
                if (current) {
                    g.setStroke(yellow);
                    g.setLineWidth(1);
                    g.strokeRect(x + 0.5, y + 0.5, to - x - 1, lineHeight - 1);
                }
            }
        }
    }

    private static final double SEARCH_ALPHA = 0.30;
    private static final double CURRENT_MATCH_ALPHA = 0.55;

    /** Top right of the text area, clear of the scrollbar, and never wider than the terminal. */
    private void layoutSearchBar(double width) {
        if (searchBar == null || !searchBar.isVisible()) return;
        double w = Math.min(searchBar.prefWidth(-1), Math.max(0, width - 2 * SEARCH_BAR_INSET));
        double h = searchBar.prefHeight(w);
        searchBar.resizeRelocate(Math.max(0, width - w - SEARCH_BAR_INSET), SEARCH_BAR_INSET, w, h);
    }

    private static final double SEARCH_BAR_INSET = 8;

    /** Whether an event is headed for the find bar, which handles its own keys and clicks. */
    private boolean fromSearchBar(javafx.event.Event e) {
        if (searchBar == null || !searchOpen) return false;
        javafx.scene.Node node = e.getTarget() instanceof javafx.scene.Node n ? n : null;
        for (; node != null; node = node.getParent()) {
            if (node == searchBar) return true;
        }
        return false;
    }

    /** Types a query into the bar and reports the count — for the development capture hook. */
    public String searchForCapture(String query, boolean regex, int steps) {
        showSearch();
        // Styled and laid out now, as a pulse would have done before a hand reached it: a field
        // created in this pulse has no skin yet, and without its skin it ignores typing.
        if (getScene() != null) {
            getScene().getRoot().applyCss();
            getScene().getRoot().layout();
        }
        java.util.Objects.requireNonNull(searchBar, "showSearch makes the bar").setQuery(query, regex);
        for (int i = 0; i < steps; i++) stepSearch(true);
        SearchState state = search;
        return "count=<" + searchBar.countText() + "> matches="
                + state.matches().size()
                + " current=" + state.current()
                + " origin=" + scrollOrigin
                + " focus=" + (searchHasFocus() ? "field" : "elsewhere");
    }
}
