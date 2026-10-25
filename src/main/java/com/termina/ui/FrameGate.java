package com.termina.ui;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides when a view's repaint timer runs: only while there is something to draw.
 *
 * <p>A running {@code AnimationTimer} makes JavaFX pulse at frame rate whatever the scene is doing —
 * the reason {@link StallMonitor} is opt-in — and one per tab, started with the tab and stopped
 * only when it closed, kept an idle terminal awake at 60fps for as long as it was open.
 *
 * <p>The emulator thread asks for frames ({@link #claim}) and the FX thread spends them
 * ({@link #frame}, {@link #release}). The claim is a compare-and-set, so the emulator asks for a
 * start at most once per idle spell rather than once per write.
 */
final class FrameGate {

    private final AtomicBoolean running = new AtomicBoolean();
    private final int idleLimit;
    private int idleFrames;

    /**
     * @param idleLimit frames with nothing to do before the timer stops — hysteresis, so bursts a
     *     few frames apart do not start and stop it each time
     */
    FrameGate(int idleLimit) {
        this.idleLimit = Math.max(1, idleLimit);
    }

    /**
     * Claims the right to start the timer. Any thread.
     *
     * @return true for exactly one caller per idle spell, which must then start it
     */
    boolean claim() {
        return running.compareAndSet(false, true);
    }

    /**
     * Records one frame. FX thread.
     *
     * @param worked whether the frame drew or delivered anything
     * @return true when the timer has idled long enough to stop — call {@link #release} next
     */
    boolean frame(boolean worked) {
        if (worked) {
            idleFrames = 0;
            return false;
        }
        return ++idleFrames >= idleLimit;
    }

    /**
     * Gives the timer up, then looks once more for work. FX thread, after stopping it.
     *
     * <p>The order closes the race with the emulator thread: a change that landed before the flag
     * was cleared lost its own claim and is caught by {@code workPending}; one that lands after
     * wins its claim and starts the timer itself.
     *
     * @param workPending re-read <em>after</em> the timer was stopped
     * @return true when the caller must start the timer again
     */
    boolean release(java.util.function.BooleanSupplier workPending) {
        idleFrames = 0;
        running.set(false);
        return workPending.getAsBoolean() && claim();
    }

    boolean isRunning() {
        return running.get();
    }
}
