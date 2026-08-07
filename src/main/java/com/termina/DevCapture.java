package com.termina;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javafx.animation.PauseTransition;
import com.jediterm.terminal.model.TerminalTextBuffer;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.util.Duration;

import com.termina.ui.TerminalView;
import com.termina.ui.TerminalWindow;
import com.termina.ui.WindowManager;

/**
 * Development-only screen capture, driven entirely by system properties so it costs nothing when
 * unused.
 *
 * <p>A terminal's failure modes are visual — a wrong cell width, a colour resolved to the wrong
 * index, a cursor drawn a row off — and none of them throw. This drives the real window and the
 * real shell, then writes what came out to a PNG, which is the only way to check those without a
 * human looking.
 *
 * <p>Run it through {@code scripts/dev-run.sh}, which can pass arbitrary {@code -D} options.
 */
final class DevCapture {

    static final String CAPTURE_PROPERTY = "termina.capture";

    private DevCapture() {}

    static boolean requested() {
        return System.getProperty(CAPTURE_PROPERTY) != null;
    }

    /**
     * Longest gap between animation frames, in milliseconds.
     *
     * <p>The FX thread drives that timer, so a long gap means it was blocked — which is what a
     * "freeze" is. Rendering slowly shows up as a steady 30-50ms; blocking shows up as one enormous
     * gap. The two look identical from the outside and have completely different causes.
     */
    private static volatile double maxStallMs;

    private static final javafx.animation.AnimationTimer STALL_METER = new javafx.animation.AnimationTimer() {
        private long previous;

        @Override
        public void handle(long now) {
            if (previous != 0) {
                double gap = (now - previous) / 1_000_000.0;
                if (gap > maxStallMs) maxStallMs = gap;
            }
            previous = now;
        }
    };

    /**
     * Runs an optional command, drives optional input, writes a PNG, then exits.
     *
     * <p>The delays are the point: the shell needs time to start and print a prompt, and the
     * renderer paints on the next animation frame rather than synchronously, so capturing straight
     * after sending input reliably photographs the frame before it.
     */
    static void schedule(WindowManager windows, TerminalWindow firstWindow) {
        schedule(windows, firstWindow, null);
    }

    static void schedule(WindowManager windows, TerminalWindow window, com.termina.config.Settings settings) {
        String target = System.getProperty(CAPTURE_PROPERTY);
        String command = System.getProperty("termina.captureCommand", "");
        long settleMs = Long.getLong("termina.captureSettleMs", 2500);
        long afterCommandMs = Long.getLong("termina.captureAfterCommandMs", 2000);

        STALL_METER.start();
        PauseTransition settle = new PauseTransition(Duration.millis(settleMs));
        settle.setOnFinished(e -> {
            openExtraTabsAndWindows(windows, window);
            TerminalView terminal = window.activeTerminal();
            if (!command.isBlank() && terminal != null) {
                sendUntilItTakes(terminal, command);
            }
            // -Dtermina.captureTypeTab=<text> types the text then presses Tab, without a Return —
            // the completion case, which cannot be expressed as a command to run.
            String tabPrefix = System.getProperty("termina.captureTypeTab");
            if (tabPrefix != null && terminal != null) {
                terminal.getSession().sendString(tabPrefix);
                maxStallMs = 0; // measure only from here
                // Two different things look identical to someone watching: the UI thread blocked,
                // and the shell simply not answering. The stall meter measures the first; this
                // measures the second. Without both, "it froze" is unattributable.
                long[] firstChange = {0};
                long sentAt = System.nanoTime();
                terminal.getSession().getTextBuffer().addModelListener(() -> {
                    if (firstChange[0] == 0) firstChange[0] = System.nanoTime();
                });
                terminal.getSession().send(new byte[] {0x09});
                PauseTransition measure = new PauseTransition(Duration.millis(afterCommandMs - 500));
                measure.setOnFinished(x -> System.out.println("[capture] shellReplyMs="
                        + (firstChange[0] == 0 ? "NEVER" : (firstChange[0] - sentAt) / 1_000_000)));
                measure.play();
                // A large completion set makes zsh ask before listing; -Dtermina.captureAfterTab
                // answers it, so the listing is actually drawn and can be measured.
                String afterTab = System.getProperty("termina.captureAfterTab");
                if (afterTab != null && !afterTab.isEmpty()) {
                    PauseTransition answer = new PauseTransition(Duration.millis(1200));
                    answer.setOnFinished(x -> terminal.getSession().sendString(afterTab));
                    answer.play();
                }
            }
            PauseTransition afterCommand = new PauseTransition(Duration.millis(afterCommandMs));
            afterCommand.setOnFinished(e2 -> {
                TerminalView active = window.activeTerminal();
                if (active != null) driveInput(window, active);
                fireChordIfRequested(window);
                selectTabIfRequested(window);
                closeTabsIfRequested(window);
                if (settings != null) switchThemeIfRequested(settings);
                // Before the settle pause, not after: a resize applied in the same pulse as the
                // exit never reaches the scene, so the geometry written on close is the old one and
                // the run proves nothing.
                resizeIfRequested(window);
                openPaletteIfRequested(window);
                probeDebugLog();
                TerminalWindow shown = windowToCapture(windows, window);
                Scene captured = sceneToCapture(windows, shown, shown.activeTerminal());
                // A further pause before snapshotting, because rendering is deliberately deferred
                // to the next animation frame: capturing in this same pulse photographs the frame
                // *before* the input, which looks exactly like input that did nothing.
                PauseTransition settleFrame = new PauseTransition(Duration.millis(150));
                settleFrame.setOnFinished(e3 -> {
                    try {
                        write(captured, new File(target));
                        System.out.println("[capture] wrote " + target);
                    } catch (IOException io) {
                        System.err.println("[capture] failed: " + io);
                    }
                    printScreenTextIfRequested(shown.activeTerminal());
                    report(windows);
                    windows.closeAll();
                    Platform.exit();
                });
                settleFrame.play();
            });
            afterCommand.play();
        });
        settle.play();
    }

    /**
     * {@code -Dtermina.captureTabs=N} opens N extra tabs; {@code -Dtermina.captureWindows=N} opens N
     * extra windows.
     */
    private static void openExtraTabsAndWindows(WindowManager windows, TerminalWindow window) {
        for (int i = 0; i < Integer.getInteger("termina.captureTabs", 0); i++) window.openTab();
        for (int i = 0; i < Integer.getInteger("termina.captureWindows", 0); i++) windows.openWindow();
    }

    /**
     * {@code -Dtermina.captureTheme=<id>} switches theme after the windows exist, which is how the
     * broadcast gets checked: photograph a window that was open *before* the change and see whether
     * it followed.
     */
    private static void switchThemeIfRequested(com.termina.config.Settings settings) {
        String theme = System.getProperty("termina.captureTheme");
        if (theme == null || theme.isBlank()) return;
        settings.setThemeId(theme);
        System.out.println("[capture] switched theme to " + theme);
    }

    /** {@code -Dtermina.captureWindowIndex=N} photographs a window other than the first. */
    private static TerminalWindow windowToCapture(WindowManager windows, TerminalWindow fallback) {
        int index = Integer.getInteger("termina.captureWindowIndex", -1);
        if (index < 0 || index >= windows.windows().size()) return fallback;
        return windows.windows().get(index);
    }

    /**
     * A line of state the caller can assert on without reading the picture.
     *
     * <p>The descendant count is the one that matters: each tab owns a shell, and a tab that closes
     * without reaping it leaks a process, its pump threads and the emulator thread. Nothing about
     * the window would look wrong.
     */
    private static void report(WindowManager windows) {
        System.out.println("[capture] windows=" + windows.windows().size()
                + " terminals=" + windows.allTerminals().size()
                + " descendants=" + descendants()
                + " maxStallMs=" + Math.round(maxStallMs));
        for (TerminalWindow w : windows.windows()) {
            System.out.println("[capture] layout " + w.layoutReport());
            System.out.println("[capture] menus " + w.menuReport());
            System.out.println("[capture] windowTitle=\"" + w.stage().getTitle() + "\" tabs=" + w.tabTitles());
        }
    }

    /**
     * {@code -Dtermina.captureScrollBarTo=<value>} moves the scrollbar the way a drag does, going
     * through the same listener rather than setting scrollOrigin behind its back — otherwise the
     * test proves the renderer scrolls, not that the bar drives it.
     */
    private static void dragScrollBarIfRequested(TerminalWindow window) {
        String spec = System.getProperty("termina.captureScrollBarTo");
        if (spec == null) return;
        TerminalView terminal = window.selectedTerminal();
        if (terminal == null) return;
        terminal.setScrollBarValue(Double.parseDouble(spec.trim()));
        System.out.println("[capture] after drag: " + terminal.scrollBarReport());
    }

    /**
     * {@code -Dtermina.captureResizeTo=W,H} resizes the window before it closes, so the next run can
     * show whether the size was remembered.
     */
    private static void resizeIfRequested(TerminalWindow window) {
        String spec = System.getProperty("termina.captureResizeTo");
        if (spec == null) return;
        String[] parts = spec.split(",");
        window.stage().setWidth(Double.parseDouble(parts[0].trim()));
        window.stage().setHeight(Double.parseDouble(parts[1].trim()));
        System.out.println("[capture] resized to " + spec);
    }

    /** {@code -Dtermina.capturePalette=true} opens the command palette before the screenshot. */
    private static void openPaletteIfRequested(TerminalWindow window) {
        String query = System.getProperty("termina.capturePalette");
        if (query == null) return;
        System.out.println("[capture] palette \"" + query + "\" -> " + window.showPaletteForCapture(query));
    }

    /**
     * {@code -Dtermina.captureLogProbe=true} pushes a warning and an uncaught exception through the
     * real logging path, to check the debug log is actually capturing rather than merely present.
     */
    private static void probeDebugLog() {
        if (System.getProperty("termina.captureLogProbe") == null) return;
        System.getLogger("com.termina.probe.Fake")
                .log(System.Logger.Level.WARNING, "probe warning", new IllegalStateException("probe cause"));
        Thread thread = new Thread(
                () -> {
                    throw new IllegalArgumentException("probe uncaught");
                },
                "probe-thread");
        thread.start();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String captured = com.termina.ui.DebugLog.text();
        System.out.println("[capture] debugLog chars=" + captured.length()
                + " warning=" + captured.contains("probe warning")
                + " cause=" + captured.contains("probe cause")
                + " uncaught=" + captured.contains("probe uncaught")
                + " file=" + com.termina.ui.DebugLog.file());
    }

    /**
     * Types a command, and keeps typing it until the shell shows it.
     *
     * <p>First output is not the same as ready to read: a login shell prints its profile's noise —
     * on macOS, bash prints a notice about zsh — before it reaches a prompt, and anything sent in
     * that window is discarded. The symptom is a screenshot of a command sitting there unexecuted.
     * This has now caused three separate failures: the PTY integration test, and two attempts at
     * the README screenshot.
     *
     * <p>Re-sending is safe: the worst case is a shell that echoes a harmless command twice.
     */
    private static void sendUntilItTakes(TerminalView terminal, String command) {
        final int attempts = 8;
        final Duration retryEvery = Duration.millis(400);
        Timeline typing = new Timeline();
        for (int i = 0; i < attempts; i++) {
            typing.getKeyFrames().add(new KeyFrame(retryEvery.multiply(i), e -> {
                if (screenText(terminal).contains(command)) {
                    typing.stop();
                    return;
                }
                terminal.getSession().sendString(command + "\r");
            }));
        }
        typing.play();
    }

    /** The visible screen as text. Used to tell "the shell ran it" from "the shell ignored it". */
    private static String screenText(TerminalView terminal) {
        TerminalTextBuffer buffer = terminal.getSession().getTextBuffer();
        buffer.lock();
        try {
            StringBuilder out = new StringBuilder();
            for (int row = 0; row < buffer.getHeight(); row++) {
                out.append(buffer.getLine(row).getText()).append('\n');
            }
            return out.toString();
        } finally {
            buffer.unlock();
        }
    }

    /**
     * {@code -Dtermina.captureScreenText=true} prints what is on screen.
     *
     * <p>The end-to-end check a smoke test needs: text on screen means the JVM started, JavaFX
     * rendered, a pseudo-terminal was allocated, a shell ran, and the emulator parsed what it wrote.
     * A screenshot proves only that a window appeared.
     */
    private static void printScreenTextIfRequested(TerminalView terminal) {
        if (System.getProperty("termina.captureScreenText") == null || terminal == null) return;
        System.out.println("[capture] screen<<<");
        System.out.println(screenText(terminal).stripTrailing());
        System.out.println("[capture] >>>");
    }

    private static long descendants() {
        return ProcessHandle.current().descendants().count();
    }

    /** {@code -Dtermina.captureCloseTabs=N} closes N tabs, to check they are actually reaped. */
    private static void closeTabsIfRequested(TerminalWindow window) {
        int count = Integer.getInteger("termina.captureCloseTabs", 0);
        if (count <= 0) return;
        System.out.println("[capture] descendants before close=" + descendants());
        for (int i = 0; i < count; i++) window.closeCurrentTab();
    }

    /**
     * Fires an application chord at the window scene.
     *
     * <pre>-Dtermina.captureChord=T[,shift]</pre>
     *
     * <p>This checks the one genuinely subtle claim in the key handling: that a scene-level filter
     * beats TerminalView's own filter, which would otherwise encode Ctrl+&lt;letter&gt; as a control
     * byte and swallow the chord. Reasoning about JavaFX dispatch order is not evidence.
     */
    private static void fireChordIfRequested(TerminalWindow window) {
        String spec = System.getProperty("termina.captureChord");
        if (spec == null || spec.isBlank()) return;
        String[] parts = spec.split(",");
        javafx.scene.input.KeyCode code = javafx.scene.input.KeyCode.valueOf(parts[0].trim());
        boolean shift = parts.length > 1 && Boolean.parseBoolean(parts[1].trim());
        boolean mac = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("mac");
        javafx.scene.input.KeyEvent event = new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED,
                "",
                "",
                code,
                shift,
                !mac,
                false,
                mac); // shift, control, alt, meta
        Event.fireEvent(window.stage().getScene(), event);
        System.out.println("[capture] fired chord " + spec);
    }

    /**
     * Asks the emulator for the encoding of every special key we claim to handle, and reports the
     * ones it has nothing for.
     *
     * <p>A key with no encoding is not merely unsent: KeyEncoding returns null, the event is left
     * unconsumed, and JavaFX is free to act on it — which for Tab means moving focus out of the
     * terminal, so every keystroke after it goes somewhere else.
     */
    private static void probeKeyEncodings(TerminalView terminal) {
        if (System.getProperty("termina.captureKeyProbe") == null) return;
        int[][] keys = {
            {9, 'T'},
            {8, 'B'},
            {10, 'E'},
            {27, 'X'},
            {33, 'U'},
            {34, 'D'},
            {35, 'N'},
            {36, 'H'},
            {37, 'L'},
            {38, 'P'},
            {39, 'R'},
            {40, 'W'},
            {155, 'I'},
            {127, 'Z'},
            {112, '1'},
            {113, '2'},
            {114, '3'},
            {115, '4'},
            {116, '5'},
            {117, '6'},
        };
        String[] names = {
            "TAB",
            "BACK_SPACE",
            "ENTER",
            "ESCAPE",
            "PAGE_UP",
            "PAGE_DOWN",
            "END",
            "HOME",
            "LEFT",
            "UP",
            "RIGHT",
            "DOWN",
            "INSERT",
            "DELETE",
            "F1",
            "F2",
            "F3",
            "F4",
            "F5",
            "F6",
        };
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            byte[] code = terminal.getSession().keyCode(keys[i][0], 0);
            if (code == null || code.length == 0) missing.append(names[i]).append(' ');
        }
        System.out.println("[capture] keys with NO encoding: "
                + (missing.length() == 0 ? "(none)" : missing.toString().trim()));
    }

    /** Fires a real key event at the view, exercising the whole key path rather than raw bytes. */
    private static void pressKeyIfRequested(TerminalView terminal) {
        String spec = System.getProperty("termina.capturePressKey");
        if (spec == null || spec.isBlank()) return;
        String[] parts = spec.split(",");
        javafx.scene.input.KeyCode code = javafx.scene.input.KeyCode.valueOf(parts[0].trim());
        boolean shift = parts.length > 1 && Boolean.parseBoolean(parts[1].trim());
        // Type a partial command first, without Return, so the key lands in the shell's line editor
        // where completion actually happens.
        String prefix = System.getProperty("termina.capturePressKeyAfter", "");
        if (!prefix.isEmpty()) {
            terminal.getSession().sendString(prefix);
            try {
                Thread.sleep(300); // let the shell echo it before the key arrives
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        Event.fireEvent(
                terminal,
                new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
        System.out.println("[capture] pressed " + spec + "; terminal still focused=" + terminal.isFocused());
    }

    /**
     * Opens the context menu, then tries to dismiss it the way the user would.
     *
     * <pre>-Dtermina.captureMenuDismiss=escape|click</pre>
     */
    private static void dismissMenuIfRequested(TerminalView terminal) {
        String how = System.getProperty("termina.captureMenuDismiss");
        if (how == null || how.isBlank()) return;
        terminal.showContextMenuForCapture(300, 300, false);
        System.out.println("[capture] menu open before dismiss=" + terminal.isContextMenuShowing());
        if (how.equals("escape")) {
            Event.fireEvent(
                    terminal,
                    new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            javafx.scene.input.KeyCode.ESCAPE,
                            false,
                            false,
                            false,
                            false));
        } else {
            Event.fireEvent(terminal, mouse(MouseEvent.MOUSE_PRESSED, 400, 400, 1, false));
        }
        System.out.println("[capture] menu open after " + how + "=" + terminal.isContextMenuShowing());
    }

    /** {@code -Dtermina.captureSelectTab=N} selects a tab, to check what follows the selection. */
    private static void selectTabIfRequested(TerminalWindow window) {
        Integer index = Integer.getInteger("termina.captureSelectTab");
        if (index == null) return;
        window.selectTab(index);
        System.out.println("[capture] selected tab " + index);
    }

    private static void driveInput(TerminalWindow window, TerminalView terminal) {
        dragScrollBarIfRequested(window);
        driveInput(terminal);
    }

    private static void driveInput(TerminalView terminal) {
        probeKeyEncodings(terminal);
        dismissMenuIfRequested(terminal);
        pressKeyIfRequested(terminal);
        clickSelectIfRequested(terminal);
        scrollIfRequested(terminal);
        dragSelectIfRequested(terminal);
        dragScrollBarWithMouseIfRequested(terminal);
    }

    /**
     * The scene to photograph: the window's own, unless asked for the context menu, which lives in
     * a scene of its own.
     */
    private static Scene sceneToCapture(WindowManager windows, TerminalWindow window, TerminalView terminal) {
        Scene fallback = window.stage().getScene();
        if (System.getProperty("termina.captureSettings") != null) {
            return windows.showSettingsForCapture(window.stage(), System.getProperty("termina.captureSettings"));
        }
        if (System.getProperty("termina.captureAbout") != null) {
            return windows.showAboutForCapture(window.stage());
        }
        String tabMenu = System.getProperty("termina.captureTabMenu");
        if (tabMenu != null && !tabMenu.isBlank()) {
            Scene menu = window.showTabMenuForCapture(Integer.parseInt(tabMenu.trim()), 200, 120);
            System.out.println("[capture] tab menu shown=" + (menu != null));
            if (menu != null) return menu;
        }
        String menuSpec = System.getProperty("termina.captureMenu");
        if (menuSpec != null && !menuSpec.isBlank() && terminal != null) {
            String[] parts = menuSpec.split(",");
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            boolean shift = parts.length > 2 && Boolean.parseBoolean(parts[2].trim());
            Scene menu = terminal.showContextMenuForCapture(x, y, shift);
            System.out.println("[capture] context menu shown=" + (menu != null) + " mouseMode="
                    + terminal.getDisplay().getMouseMode());
            if (menu != null) return menu;
        }
        return fallback;
    }

    /**
     * Scrolls the wheel over the view.
     *
     * <pre>-Dtermina.captureScroll=x,y,deltaY</pre>
     */
    private static void scrollIfRequested(TerminalView terminal) {
        String spec = System.getProperty("termina.captureScroll");
        if (spec == null || spec.isBlank()) return;
        String[] parts = spec.split(",");
        if (parts.length != 3) {
            System.err.println("[capture] captureScroll needs x,y,deltaY");
            return;
        }
        double x = Double.parseDouble(parts[0].trim());
        double y = Double.parseDouble(parts[1].trim());
        double deltaY = Double.parseDouble(parts[2].trim());
        Event.fireEvent(
                terminal,
                new ScrollEvent(
                        ScrollEvent.SCROLL,
                        x,
                        y,
                        x,
                        y,
                        false,
                        false,
                        false,
                        false, // shift, control, alt, meta
                        false,
                        false, // direct, inertia
                        0,
                        deltaY,
                        0,
                        deltaY,
                        ScrollEvent.HorizontalTextScrollUnits.NONE,
                        0,
                        ScrollEvent.VerticalTextScrollUnits.NONE,
                        0,
                        0,
                        null));
        System.out.println("[capture] scrolled deltaY=" + deltaY);
    }

    /**
     * Drags a selection across the view and reports what copying it yields.
     *
     * <p>Real {@code MouseEvent}s fired at the node, not a call to some internal setter: the thing
     * worth checking is the whole path — pixel to cell, anchor to drag, buffer coordinates to
     * extracted text. Setting the selection directly would verify only the highlight.
     *
     * <pre>-Dtermina.captureDrag=x1,y1,x2,y2   -Dtermina.captureDragShift=true</pre>
     */
    private static void dragSelectIfRequested(TerminalView terminal) {
        String spec = System.getProperty("termina.captureDrag");
        if (spec == null || spec.isBlank()) return;
        String[] parts = spec.split(",");
        if (parts.length != 4) {
            System.err.println("[capture] captureDrag needs x1,y1,x2,y2");
            return;
        }
        double x1 = Double.parseDouble(parts[0].trim());
        double y1 = Double.parseDouble(parts[1].trim());
        double x2 = Double.parseDouble(parts[2].trim());
        double y2 = Double.parseDouble(parts[3].trim());

        // Shift is the documented bypass for a program that has grabbed the mouse, so the capture
        // has to be able to exercise it.
        boolean shift = Boolean.getBoolean("termina.captureDragShift");
        Event.fireEvent(terminal, mouse(MouseEvent.MOUSE_PRESSED, x1, y1, 1, shift));
        Event.fireEvent(terminal, mouse(MouseEvent.MOUSE_DRAGGED, x2, y2, 1, shift));
        Event.fireEvent(terminal, mouse(MouseEvent.MOUSE_RELEASED, x2, y2, 1, shift));

        boolean copied = terminal.copySelection();
        System.out.println("[capture] selection copied=" + copied);
        if (copied) {
            System.out.println("[capture] clipboard<<<"
                    + javafx.scene.input.Clipboard.getSystemClipboard().getString() + ">>>");
        }
    }

    /**
     * {@code -Dtermina.captureDragScrollBar=y1,y2} drags on the scrollbar.
     *
     * <p>Fired at the bar rather than at the terminal, because the whole question is what the event
     * <em>target</em> is: aiming at the terminal with scrollbar coordinates would exercise nothing,
     * since the terminal's filters key off the target, not the position.
     */
    private static void dragScrollBarWithMouseIfRequested(TerminalView terminal) {
        String spec = System.getProperty("termina.captureDragScrollBar");
        if (spec == null || spec.isBlank()) return;
        String[] parts = spec.split(",");
        if (parts.length != 2) {
            System.err.println("[capture] captureDragScrollBar needs y1,y2");
            return;
        }
        double y1 = Double.parseDouble(parts[0].trim());
        double y2 = Double.parseDouble(parts[1].trim());
        javafx.scene.Node bar = terminal.scrollBarNode();
        Event.fireEvent(bar, mouse(MouseEvent.MOUSE_PRESSED, 4, y1, 1, false));
        Event.fireEvent(bar, mouse(MouseEvent.MOUSE_DRAGGED, 4, y2, 1, false));
        Event.fireEvent(bar, mouse(MouseEvent.MOUSE_RELEASED, 4, y2, 1, false));
        System.out.println("[capture] scrollbar mouse drag: selection=" + terminal.hasSelection() + " "
                + terminal.scrollBarReport());
    }

    /**
     * Word (2) or line (3) selection by click count.
     *
     * <pre>-Dtermina.captureClick=x,y,clickCount</pre>
     */
    private static void clickSelectIfRequested(TerminalView terminal) {
        String spec = System.getProperty("termina.captureClick");
        if (spec == null || spec.isBlank()) return;
        String[] parts = spec.split(",");
        if (parts.length != 3) {
            System.err.println("[capture] captureClick needs x,y,clickCount");
            return;
        }
        double x = Double.parseDouble(parts[0].trim());
        double y = Double.parseDouble(parts[1].trim());
        int clicks = Integer.parseInt(parts[2].trim());
        Event.fireEvent(terminal, mouse(MouseEvent.MOUSE_PRESSED, x, y, clicks, false));
        Event.fireEvent(terminal, mouse(MouseEvent.MOUSE_RELEASED, x, y, clicks, false));

        if (terminal.copySelection()) {
            System.out.println("[capture] click-selection<<<"
                    + javafx.scene.input.Clipboard.getSystemClipboard().getString() + ">>>");
        } else {
            System.out.println("[capture] click-selection: nothing selected");
        }
    }

    private static MouseEvent mouse(EventType<MouseEvent> type, double x, double y, int clicks, boolean shift) {
        return new MouseEvent(
                type,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
                clicks,
                shift,
                false,
                false,
                false, // shift, control, alt, meta
                true,
                false,
                false, // primary/middle/secondary button down
                false,
                false,
                false, // synthesized, popup trigger, still since press
                null);
    }

    private static void write(Scene scene, File target) throws IOException {
        WritableImage image = scene.snapshot(null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();

        // Converted by hand rather than through SwingFXUtils: that lives in the javafx.swing
        // module, which this application deliberately does not depend on. Reading pixels and
        // filling a BufferedImage touches only Java2D, which is safe under java.awt.headless=true.
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                row[x] = reader.getArgb(x, y);
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        javax.imageio.ImageIO.write(out, "png", target);
    }
}
