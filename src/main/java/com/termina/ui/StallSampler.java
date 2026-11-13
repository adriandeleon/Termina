package com.termina.ui;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Says what the FX thread was doing while it was stalled (PERF-20, Pauta 6.0.1). {@link
 * StallMonitor} can only say for how long, after the fact, because it runs on the thread that was
 * stuck. This watches from a thread of its own: the FX thread beats once a frame, and when the
 * beats stop for the threshold its stack is sampled and printed, once for each stall.
 *
 * <p>Opt-in with the monitor, by {@code -Dtermina.stallLog=<milliseconds>}: a sampler nobody asked
 * for is a thread that wakes several times a second for nothing.
 */
final class StallSampler {

    /** How many frames of the stack are printed: enough to reach our own code past JavaFX's. */
    static final int DEPTH = 40;

    private final Thread watched;
    private final long thresholdMs;
    private volatile long lastBeatMs = System.currentTimeMillis();
    /** Counted, not told apart by their times: two beats can fall in one millisecond. */
    private final AtomicLong beats = new AtomicLong();

    private volatile long sampledAtBeat = -1;

    StallSampler(Thread watched, long thresholdMs) {
        this.watched = watched;
        this.thresholdMs = thresholdMs;
    }

    /** Called by the watched thread whenever it is alive and well: once a frame. */
    void beat() {
        lastBeatMs = System.currentTimeMillis();
        beats.incrementAndGet();
    }

    /** Starts the watching thread: a daemon, named, waking four times a threshold. */
    void start() {
        Thread watcher = new Thread(
                () -> {
                    while (true) {
                        try {
                            Thread.sleep(Math.max(10, thresholdMs / 4));
                        } catch (InterruptedException e) {
                            return;
                        }
                        String report = check(System.currentTimeMillis());
                        // stderr, like the monitor's own line: it shows in a packaged run too.
                        if (report != null) System.err.println(report);
                    }
                },
                "termina-stall-sampler");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** The report for a stall there is now and has not been reported, or null. */
    @org.jspecify.annotations.Nullable
    String check(long nowMs) {
        long beat = beats.get();
        long silentMs = nowMs - lastBeatMs;
        if (!samples(silentMs, thresholdMs, beat, sampledAtBeat)) return null;
        sampledAtBeat = beat;
        return format(silentMs, watched.getStackTrace());
    }

    /**
     * Whether to sample: the thread has been silent for the threshold, and the silence after this
     * beat has not been sampled already. One stack for one stall, however long it lasts. Pure.
     */
    static boolean samples(long silentMs, long thresholdMs, long beat, long sampledAtBeat) {
        return silentMs >= thresholdMs && sampledAtBeat != beat;
    }

    /** The stack as lines, top frame first, cut at {@link #DEPTH}. Pure. */
    static String format(long stalledMs, StackTraceElement[] stack) {
        StringBuilder out = new StringBuilder("[termina] UI thread not responding for " + stalledMs + "ms; it is in:");
        int shown = Math.min(stack.length, DEPTH);
        for (int i = 0; i < shown; i++) out.append("\n[termina]   at ").append(stack[i]);
        if (stack.length > shown) {
            out.append("\n[termina]   … ").append(stack.length - shown).append(" more");
        }
        if (stack.length == 0) out.append("\n[termina]   (no stack: the thread is not running)");
        return out.toString();
    }
}
