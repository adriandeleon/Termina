package com.termina;

import com.termina.ui.TerminalView;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
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
                try {
                    write(scene, new File(target));
                    System.out.println("[capture] wrote " + target);
                } catch (IOException io) {
                    System.err.println("[capture] failed: " + io);
                }
                terminal.close();
                Platform.exit();
            });
            afterCommand.play();
        });
        settle.play();
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
