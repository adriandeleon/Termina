package com.termina.ui;

import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PERF-20 (Pauta 6.0.1): a stalled UI thread's stack is sampled, once for each stall. */
class StallSamplerTest {

    @Test
    void aThreadIsSampledOnceItHasBeenSilentForTheThreshold() {
        assertFalse(StallSampler.samples(99, 100, 7, -1));
        assertTrue(StallSampler.samples(100, 100, 7, -1));
        assertTrue(StallSampler.samples(8_000, 100, 7, -1));
        // The same silence is not sampled again; the one after the next beat is.
        assertFalse(StallSampler.samples(8_000, 100, 7, 7));
        assertTrue(StallSampler.samples(8_000, 100, 8, 7));
    }

    @Test
    void theReportNamesTheFramesTopFirstAndCutsALongStack() {
        StackTraceElement[] stack = new StackTraceElement[StallSampler.DEPTH + 5];
        for (int i = 0; i < stack.length; i++) stack[i] = new StackTraceElement("a.B", "m" + i, "B.java", i + 1);
        String report = StallSampler.format(250, stack);
        String[] lines = report.split("\n");
        assertEquals("[termina] UI thread not responding for 250ms; it is in:", lines[0]);
        assertEquals("[termina]   at a.B.m0(B.java:1)", lines[1]);
        assertEquals(StallSampler.DEPTH + 2, lines.length);
        assertEquals("[termina]   … 5 more", lines[lines.length - 1]);

        assertTrue(StallSampler.format(250, new StackTraceElement[0]).contains("no stack"));
    }

    @Test
    void aStuckThreadsOwnStackIsWhatIsReported() throws Exception {
        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread watched = new Thread(() -> blockHere(stuck, release), "watched");
        watched.start();
        try {
            stuck.await();
            StallSampler sampler = new StallSampler(watched, 100);
            long now = System.currentTimeMillis();
            assertNull(sampler.check(now), "not silent for the threshold yet");
            String report = sampler.check(now + 500);
            assertNotNull(report);
            assertTrue(report.contains("blockHere"), report);
            assertNull(sampler.check(now + 900), "one stack for one stall");

            // It beats again, and the next silence is a new stall.
            sampler.beat();
            assertNotNull(sampler.check(System.currentTimeMillis() + 500));
        } finally {
            release.countDown();
            watched.join();
        }
    }

    private static void blockHere(CountDownLatch stuck, CountDownLatch release) {
        stuck.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
