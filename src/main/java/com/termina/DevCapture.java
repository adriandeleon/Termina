package com.termina;

import com.termina.ui.TerminalView;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javafx.animation.PauseTransition;
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

/**
 * Development-only screen capture, driven entirely by system properties so it costs nothing when
 * unused.
 *
 * <p>A terminal's failure modes are visual — a wrong cell width, a colour resolved to the wrong
 * index, a cursor drawn a row off — and none of them throw. This renders the real window, types a
 * command into the real shell, and writes what came out to a PNG, which is the only way to check
 * those without a human looking.
 *
 * <pre>
 *   mvn javafx:run -Djavafx.args=... -Dtermina.capture=/tmp/shot.png \
 *                  -Dtermina.captureCommand="ls --color=always"
 * </pre>
 */
final class DevCapture {

    static final String CAPTURE_PROPERTY = "termina.capture";

    private DevCapture() {}

    static boolean requested() {
        return System.getProperty(CAPTURE_PROPERTY) != null;
    }

    /**
     * Runs an optional command, waits for it to paint, writes a PNG, then exits.
     *
     * <p>The two delays are the point: the shell needs time to start and print a prompt, and the
     * renderer paints on the next animation frame rather than synchronously, so capturing straight
     * after sending input reliably photographs an empty screen.
     */
    static void schedule(Scene scene, TerminalView terminal) {
        String target = System.getProperty(CAPTURE_PROPERTY);
        String command = System.getProperty("termina.captureCommand", "");
        long settleMs = Long.getLong("termina.captureSettleMs", 2500);
        long afterCommandMs = Long.getLong("termina.captureAfterCommandMs", 2000);

        PauseTransition settle = new PauseTransition(Duration.millis(settleMs));
        settle.setOnFinished(e -> {
            if (!command.isBlank()) terminal.getSession().sendString(command + "\r");
            PauseTransition afterCommand = new PauseTransition(Duration.millis(afterCommandMs));
            afterCommand.setOnFinished(e2 -> {
                dragSelectIfRequested(terminal);
                // A further pause before snapshotting, because rendering is deliberately deferred
                // to the next animation frame: capturing in this same pulse photographs the frame
                // *before* the drag, which looks exactly like a selection that failed to paint.
                PauseTransition settleFrame = new PauseTransition(Duration.millis(150));
                settleFrame.setOnFinished(e3 -> {
                    try {
                        write(scene, new File(target));
                        System.out.println("[capture] wrote " + target);
                    } catch (IOException io) {
                        System.err.println("[capture] failed: " + io);
                    }
                    terminal.close();
                    Platform.exit();
                });
                settleFrame.play();
            });
            afterCommand.play();
        });
        settle.play();
    }

    /**
     * Scrolls the wheel over the view.
     *
     * <pre>-Dtermina.captureScroll=x,y,deltaY</pre>
     *
     * Positive deltaY is a wheel turn up/away, matching JavaFX.
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
        Event.fireEvent(terminal, new ScrollEvent(
                ScrollEvent.SCROLL, x, y, x, y,
                false, false, false, false, // shift, control, alt, meta
                false, false, // direct, inertia
                0, deltaY, 0, deltaY, // deltaX, deltaY, totalDeltaX, totalDeltaY
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0,
                0, null));
        System.out.println("[capture] scrolled deltaY=" + deltaY);
    }

    /**
     * Drags a selection across the view and reports what copying it yields.
     *
     * <p>Real {@code MouseEvent}s fired at the node, not a call to some internal setter: the thing
     * worth checking is the whole path — pixel to cell, anchor to drag, buffer coordinates to
     * extracted text. Setting the selection directly would verify only the highlight.
     *
     * <pre>-Dtermina.captureDrag=x1,y1,x2,y2</pre>
     */
    private static void dragSelectIfRequested(TerminalView terminal) {
        clickSelectIfRequested(terminal);
        scrollIfRequested(terminal);

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

        // Shift is the documented bypass for a program that has grabbed the mouse, so the
        // capture has to be able to exercise it.
        boolean shift = Boolean.getBoolean("termina.captureDragShift");
        Event.fireEvent(terminal, mouse(MouseEvent.MOUSE_PRESSED, x1, y1, 1, shift));
        Event.fireEvent(terminal, mouse(MouseEvent.MOUSE_DRAGGED, x2, y2, 1, shift));
        Event.fireEvent(terminal, mouse(MouseEvent.MOUSE_RELEASED, x2, y2, 1, shift));

        boolean copied = terminal.copySelection();
        System.out.println("[capture] selection copied=" + copied);
        if (copied) {
            String text = javafx.scene.input.Clipboard.getSystemClipboard().getString();
            System.out.println("[capture] clipboard<<<" + text + ">>>");
        }
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

    private static MouseEvent mouse(
            EventType<MouseEvent> type, double x, double y, int clicks, boolean shift) {
        return new MouseEvent(
                type, x, y, x, y, MouseButton.PRIMARY, clicks,
                shift, false, false, false, // shift, control, alt, meta
                true, false, false, // primary/middle/secondary button down
                false, false, false, // synthesized, popup trigger, still since press
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
