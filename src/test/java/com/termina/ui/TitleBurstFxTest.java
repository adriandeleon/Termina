package com.termina.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-21 (Pauta 6.0.1), for the fix in b9fbb83: a program that sets the title in a loop, a build
 * naming each step, costs one update of the titles and not one for each sequence. Each used to
 * queue its own task carrying its own text, and the tab strip was restyled for every one.
 */
@Tag("fx")
class TitleBurstFxTest {

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
    void aBurstOfTitlesWhileTheUiThreadIsBusyIsOneUpdateWithTheLast() throws Exception {
        FxTerminalDisplay display = new FxTerminalDisplay();
        List<String> seen = new CopyOnWriteArrayList<>();
        display.windowTitleProperty().addListener((property, was, now) -> seen.add(now));

        // The FX thread is held, as it is while a frame is drawn; the emulator's thread goes on
        // reading title sequences meanwhile.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Platform.runLater(() -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(10, TimeUnit.SECONDS));
        for (int step = 1; step <= 200; step++) display.setWindowTitle("step " + step);
        release.countDown();

        CountDownLatch drained = new CountDownLatch(1);
        Platform.runLater(drained::countDown);
        assertTrue(drained.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("step 200"), seen, "one update, showing the latest");

        // And the next title after the burst is still shown: the pending flag was given back.
        display.setWindowTitle("done");
        CountDownLatch again = new CountDownLatch(1);
        Platform.runLater(again::countDown);
        assertTrue(again.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("step 200", "done"), seen);
    }
}
