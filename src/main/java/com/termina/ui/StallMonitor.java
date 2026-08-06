package com.termina.ui;

import javafx.animation.AnimationTimer;

/**
 * Reports when the JavaFX thread stops responding.
 *
 * <p>"It froze" has two completely different causes that look identical from the outside: the UI
 * thread blocked, or the shell simply had nothing to say yet. Only the first is ours. This measures
 * the gap between animation frames — the FX thread drives that timer, so a long gap means it was
 * blocked. Slow rendering shows up as a steady 30-50ms; blocking shows up as one enormous gap.
 *
 * <p><b>Opt-in.</b> Nothing is created unless {@code -Dtermina.stallLog=<milliseconds>} is set,
 * because an always-running {@code AnimationTimer} forces a 60fps pulse even when the terminal is
 * idle, which on a laptop is a battery cost for a diagnostic almost nobody needs.
 *
 * <pre>
 *   scripts/dev-run.sh -Dtermina.stallLog=400
 *   JAVA_TOOL_OPTIONS=-Dtermina.stallLog=400 /Applications/Termina.app/Contents/MacOS/Termina
 * </pre>
 */
public final class StallMonitor {

    private static final String PROPERTY = "termina.stallLog";

    private StallMonitor() {}

    /** Starts monitoring if the property is set. Returns whether it did. */
    public static boolean installIfRequested() {
        Long threshold = Long.getLong(PROPERTY);
        if (threshold == null || threshold <= 0) return false;

        System.getLogger(StallMonitor.class.getName())
                .log(System.Logger.Level.INFO, "stall monitor on, reporting gaps over " + threshold + "ms");

        new AnimationTimer() {
            private long previous;

            @Override
            public void handle(long now) {
                if (previous != 0) {
                    long gapMs = (now - previous) / 1_000_000;
                    if (gapMs >= threshold) {
                        // stderr rather than a logger call, so it shows up even in a packaged run
                        // with no logging configured.
                        System.err.println("[termina] UI thread blocked for " + gapMs + "ms");
                    }
                }
                previous = now;
            }
        }.start();
        return true;
    }
}
