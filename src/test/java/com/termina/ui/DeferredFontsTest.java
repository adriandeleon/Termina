package com.termina.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.text.Font;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        CompletableFuture<java.util.List<String>> listed = new CompletableFuture<>();
        Platform.runLater(() -> {
            Fonts.load("");
            listed.complete(MonospaceFonts.available());
        });
        java.util.List<String> families = listed.get(10, TimeUnit.SECONDS);
        assertTrue(families.containsAll(Fonts.BUNDLED_MONO), families::toString);
    }
}
