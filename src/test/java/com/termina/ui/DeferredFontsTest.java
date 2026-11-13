package com.termina.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.text.Font;

import com.termina.testing.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bundled family left out of the first frame (PERF-15) is still found when a profile asks for it.
 * JavaFX lists its families once and never adds one registered later, so without
 * {@code Fonts.loadedFamilies} a profile in Fira Code silently drew in JetBrains Mono.
 */
@Tag("fx")
// The resolve test first: it needs Fira Code unloaded when JavaFX lists its families, and the
// picker test loads every family. Fonts cannot be unloaded, so the order is the isolation.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeferredFontsTest {

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
    }

    @Test
    @Order(1)
    void aFamilyLoadedAfterJavaFxListedItsFamiliesStillResolves() throws Exception {
        CompletableFuture<String> resolved = new CompletableFuture<>();
        Platform.runLater(() -> {
            Fonts.load("");
            Font.getFamilies(); // JavaFX fixes its list here
            MonospaceFonts.forget();
            resolved.complete(MonospaceFonts.resolve("Fira Code"));
        });
        assertEquals("Fira Code", resolved.get(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(2)
    void thePickerListsEveryBundledFamily() throws Exception {
        // Asked on the FX thread, the list is the families registered so far: the rest are read on
        // a worker, never there (PERF-01, Pauta 6.0.1), and the list is forgotten when they are in.
        // So the first answer may be short, and the one after the worker is done is whole. This
        // test alone, in a JVM where nothing else has loaded a font, is the case that showed it.
        CompletableFuture<java.util.List<String>> first = new CompletableFuture<>();
        Platform.runLater(() -> {
            Fonts.load("");
            first.complete(MonospaceFonts.available());
        });
        assertTrue(first.get(10, TimeUnit.SECONDS).contains(Fonts.BUNDLED_MONO.getFirst()), "the default family");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Fonts.restLoaded() && System.nanoTime() < deadline) Await.frame();
        assertTrue(Fonts.restLoaded(), "the worker registered the rest");

        // The worker forgets the cached list just after its last family is in: asked until whole.
        java.util.List<String> families = java.util.List.of();
        while (System.nanoTime() < deadline) {
            CompletableFuture<java.util.List<String>> listed = new CompletableFuture<>();
            Platform.runLater(() -> listed.complete(MonospaceFonts.available()));
            families = listed.get(10, TimeUnit.SECONDS);
            if (families.containsAll(Fonts.BUNDLED_MONO)) break;
            Await.frame();
        }
        assertTrue(families.containsAll(Fonts.BUNDLED_MONO), families::toString);
    }

    /**
     * PERF-01 (Pauta 6.0.1): after the first frame a worker makes the list, measuring every
     * installed family, so the font picker's first opening measures nothing on the FX thread.
     */
    @Test
    @Order(3)
    void theListIsMadeOnAWorkerBeforeThePickerAsks() throws Exception {
        MonospaceFonts.forget();
        assertFalse(MonospaceFonts.isListed());
        Thread worker = MonospaceFonts.warmInBackground();
        assertEquals("termina-fonts", worker.getName());
        worker.join(TimeUnit.SECONDS.toMillis(30));
        assertTrue(MonospaceFonts.isListed(), "the worker left the list made");

        CompletableFuture<java.util.List<String>> listed = new CompletableFuture<>();
        Platform.runLater(() -> listed.complete(MonospaceFonts.available()));
        assertTrue(listed.get(10, TimeUnit.SECONDS).containsAll(Fonts.BUNDLED_MONO));
    }
}
