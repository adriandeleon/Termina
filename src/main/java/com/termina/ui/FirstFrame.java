package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.animation.AnimationTimer;

/**
 * Runs what nobody waits for once the first window has painted (PERF-15, Pauta 2.0.2): the fonts
 * the first window does not use, the large window icons, the update check.
 *
 * <p>On the second pulse after {@link #start}, not the first, for the reason
 * {@code perf.Startup.markFirstPaint} gives: a pulse's timer fires at its start, so only the second
 * proves the one that laid out and painted the window has finished. FX thread only.
 */
public final class FirstFrame {

    private static final List<Runnable> PENDING = new ArrayList<>();
    private static boolean started;
    private static boolean done;

    private FirstFrame() {}

    /** Queues {@code task} for after the first frame, or runs it now if that has passed. */
    public static void after(Runnable task) {
        if (done) {
            task.run();
        } else {
            PENDING.add(task);
        }
    }

    /** Starts counting pulses. Called once the first window has been shown. */
    public static void start() {
        if (started) return;
        started = true;
        new AnimationTimer() {
            private int ticks;

            @Override
            public void handle(long now) {
                if (++ticks < 2) return;
                stop();
                done = true;
                List<Runnable> tasks = List.copyOf(PENDING);
                PENDING.clear();
                for (Runnable task : tasks) task.run();
            }
        }.start();
    }
}
