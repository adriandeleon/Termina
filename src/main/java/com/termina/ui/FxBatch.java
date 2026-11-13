package com.termina.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Hands what another thread produces to the FX thread in batches, at least {@link
 * #MIN_INTERVAL_MILLIS} apart, never one {@code runLater} for each item (PERF-07, Pauta 6.0.1).
 *
 * <p>The terminal's output is already drained once a frame. This is for the streams beside it
 * that arrive at their source's pace and used to be posted one at a time: a program's desktop
 * notifications (OSC 9 and 777), its clipboard writes (OSC 52), uncaught errors. A loop printing
 * any of them queued a runnable per sequence, which is the FX thread's time spent at the rate of
 * somebody else's output.
 *
 * <p>The hand-off and the wait are injected, so the batching is tested without a toolkit.
 *
 * @param <T> what is handed over
 */
final class FxBatch<T> {

    /** A frame at sixty a second: the least time between two drains. */
    static final long MIN_INTERVAL_MILLIS = 16;

    private final Consumer<List<T>> drain;
    private final Consumer<Runnable> toFxThread;
    private final BiConsumer<Long, Runnable> afterMillis;
    private final java.util.function.LongSupplier nanoTime;

    private final Object lock = new Object();
    private List<T> pending = new ArrayList<>();
    private boolean scheduled;
    /** Read and written on the FX thread only. */
    private long lastDrainNanos;

    private boolean drainedOnce;

    /** A batch drained on the FX thread, by {@code drain}, which gets every item added since the last. */
    FxBatch(Consumer<List<T>> drain) {
        this(drain, javafx.application.Platform::runLater, FxBatch::pause, System::nanoTime);
    }

    FxBatch(
            Consumer<List<T>> drain,
            Consumer<Runnable> toFxThread,
            BiConsumer<Long, Runnable> afterMillis,
            java.util.function.LongSupplier nanoTime) {
        this.drain = drain;
        this.toFxThread = toFxThread;
        this.afterMillis = afterMillis;
        this.nanoTime = nanoTime;
    }

    /** Adds an item, from any thread. One hand-off is scheduled for however many arrive before it runs. */
    void add(T item) {
        synchronized (lock) {
            pending.add(item);
            if (scheduled) return;
            scheduled = true;
        }
        toFxThread.accept(this::onFxThread);
    }

    private void onFxThread() {
        long wait = drainedOnce ? waitMillis(nanoTime.getAsLong(), lastDrainNanos) : 0;
        if (wait > 0) afterMillis.accept(wait, this::drainNow);
        else drainNow();
    }

    private void drainNow() {
        List<T> batch;
        synchronized (lock) {
            batch = pending;
            pending = new ArrayList<>();
            scheduled = false;
        }
        lastDrainNanos = nanoTime.getAsLong();
        drainedOnce = true;
        if (!batch.isEmpty()) drain.accept(batch);
    }

    /** How long to wait before draining, so two drains are never closer than the minimum interval. */
    static long waitMillis(long nowNanos, long lastDrainNanos) {
        long sinceMillis = (nowNanos - lastDrainNanos) / 1_000_000;
        if (sinceMillis < 0) return 0;
        return Math.max(0, MIN_INTERVAL_MILLIS - sinceMillis);
    }

    private static void pause(long millis, Runnable then) {
        javafx.animation.PauseTransition pause =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(millis));
        pause.setOnFinished(e -> then.run());
        pause.play();
    }
}
