package com.termina.testing;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;

/**
 * Waiting in a test that has the toolkit, without sleeping (QUAL-07, Pauta 6.0.1).
 *
 * <p>A condition is looked at again after each frame the FX thread runs: the test thread waits on a
 * latch that the next frame counts down. So a wait ends in the frame that makes it true, not at the
 * end of a guessed number of milliseconds, and a wait for something the FX thread has yet to do
 * cannot be passed by a slow machine. A test with no toolkit waits on a {@link Signal}.
 */
public final class Await {

    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private Await() {}

    /** Blocks until the FX thread has run one more frame. Never called on that thread. */
    public static void frame() throws InterruptedException {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException("the FX thread would be waiting for its own frame");
        }
        CountDownLatch ran = new CountDownLatch(1);
        Platform.runLater(() -> new AnimationTimer() {
            @Override
            public void handle(long now) {
                stop();
                ran.countDown();
            }
        }.start());
        if (!ran.await(FRAME_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            throw new AssertionError("the FX thread ran no frame in " + FRAME_TIMEOUT.toSeconds() + " s");
        }
    }

    /** Blocks until the FX thread has run {@code count} more frames: what it had queued is done. */
    public static void frames(int count) throws InterruptedException {
        for (int i = 0; i < count; i++) frame();
    }

    /** Waits until {@code condition} holds, or fails naming {@code what} after twenty seconds. */
    public static void until(BooleanSupplier condition, String what) throws InterruptedException {
        until(condition, what, TIMEOUT);
    }

    /** Waits until {@code condition} holds, or fails naming {@code what} after {@code timeout}. */
    public static void until(BooleanSupplier condition, String what, Duration timeout) throws InterruptedException {
        if (!within(condition, timeout)) throw new AssertionError("timed out waiting for " + what);
    }

    /** Whether {@code condition} came to hold within {@code timeout}, looked at once a frame. */
    public static boolean within(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            frame();
        }
        return true;
    }
}
