package com.termina.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.termina.config.Settings;
import com.termina.testing.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-09 (Pauta 6.0.1), measured: a screen that has not changed is repainted, frame after frame,
 * and what the FX thread allocates for each is counted by the JVM.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class RepaintAllocationFxTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void aRepaintOfAnUnchangedScreenAllocatesLittle() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setNotifications(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        Await.until(() -> started(window), "the shell");
        TerminalView view = onFx(window::activeTerminal);
        // A screenful in colour: some forty rows, a dozen style runs on each, and wide characters.
        onFx(() -> {
            view.getSession()
                    .sendString("for i in $(seq 1 40); do printf '\\033[3%dm%s \\033[1mbold\\033[0m plain"
                            + " \\033[4%dmback\\033[0m \\346\\227\\245\\346\\234\\254 row-%d\\n' $((i%8)) word $((i%8)) $i;"
                            + " done; printf 'screen-%s\\n' full\r");
            return null;
        });
        Await.until(() -> screen(view).contains("screen-full"), "the screenful");

        long fx = onFx(() -> Thread.currentThread().threadId());
        int frames = 120;
        // The same frames twice over, three times: asked to repaint, and left alone. What the
        // second costs is the test's own (a task handed to the FX thread, a timer for the frame)
        // and the toolkit's pulse; the difference is the repaint's.
        long[] repainting = new long[3];
        long[] idle = new long[3];
        for (int round = 0; round < repainting.length; round++) {
            for (boolean repaint : new boolean[] {true, false}) {
                long before = allocatedBy(fx);
                for (int frame = 0; frame < frames; frame++) {
                    boolean shown = frame % 2 == 0;
                    onFx(() -> {
                        // Marks the view dirty, so the next frame repaints it; or changes nothing.
                        view.setPaneFocusShown(repaint && shown);
                        return null;
                    });
                    Await.frame();
                }
                (repaint ? repainting : idle)[round] = (allocatedBy(fx) - before) / frames;
            }
        }
        onFx(() -> {
            view.setPaneFocusShown(false);
            return null;
        });
        // The third round, when everything that is made once has been made.
        long repaint = repainting[2] - idle[2];
        assertTrue(
                repaint < LIMIT,
                "a repaint allocated " + repaint + " bytes: repainting " + java.util.Arrays.toString(repainting)
                        + ", idle " + java.util.Arrays.toString(idle));
    }

    /**
     * Bytes a repaint of this screen may allocate, over a frame that draws nothing. Measured when
     * this was written: about 900 to 1,250 (the canvas's saved state, a cursor cell). Copying each
     * run to a string, as the painter did, measured 9,050 by itself, and the emulator's own walk
     * over the rows, which the painter no longer uses, 3,700 more; the limit is under either.
     */
    private static final long LIMIT = 3_000;

    /**
     * Bytes a thread has allocated so far, as the JVM counts them. Asked by name: the test is
     * compiled as part of the application's module, which does not read {@code java.management},
     * and run on the class path, where the JDK has it.
     */
    private static long allocatedBy(long thread) throws Exception {
        Object bean = Class.forName("java.lang.management.ManagementFactory")
                .getMethod("getThreadMXBean")
                .invoke(null);
        return (long) Class.forName("com.sun.management.ThreadMXBean")
                .getMethod("getThreadAllocatedBytes", long.class)
                .invoke(bean, thread);
    }

    private static String screen(TerminalView view) {
        try {
            return onFx(view::screenText);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean started(TerminalWindow window) {
        try {
            return onFx(() -> {
                TerminalView view = window.activeTerminal();
                return view != null && view.isStarted();
            });
        } catch (Exception e) {
            return false;
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}
