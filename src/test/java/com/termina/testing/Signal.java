package com.termina.testing;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Something a test waits on that the code under test fires: a buffer's change listener, a
 * watcher's callback (QUAL-07, Pauta 6.0.1). The test looks at its condition again each time the
 * signal fires, so it wakes when there is something new to see and not on a timer.
 *
 * <p>The condition is read outside the monitor: a listener may fire while holding a lock the
 * condition also takes (the text buffer's), and the count of firings is what keeps one that
 * arrives between the look and the wait from being missed.
 */
public final class Signal {

    private final Object monitor = new Object();
    private long fired;

    /** Wakes whoever is waiting. Safe from any thread, holding any lock. */
    public void fire() {
        synchronized (monitor) {
            fired++;
            monitor.notifyAll();
        }
    }

    /** Whether {@code condition} came to hold within {@code timeout}, looked at on each firing. */
    public boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long seen;
            synchronized (monitor) {
                seen = fired;
            }
            if (condition.getAsBoolean()) return true;
            synchronized (monitor) {
                while (fired == seen) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) return false;
                    monitor.wait(Math.max(1, left / 1_000_000));
                }
            }
        }
    }

    /** Whether nothing fired for the whole of {@code window}: for something that must have stopped. */
    public boolean quietFor(Duration window) throws InterruptedException {
        long deadline = System.nanoTime() + window.toNanos();
        synchronized (monitor) {
            long seen = fired;
            while (fired == seen) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return true;
                monitor.wait(Math.max(1, left / 1_000_000));
            }
            return false;
        }
    }
}
