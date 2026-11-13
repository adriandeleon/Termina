package com.termina.ui;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tooltips name the command and the chord it is on now (TBAR-03, Pauta 2.0.2). */
@Tag("fx")
class ChordTooltipsTest {

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
    void aBoundCommandShowsItsChordAndAnUnboundOneItsTitleAlone() {
        KeyCombination chord = KeyCombination.valueOf("Ctrl+Shift+Z");
        assertEquals("Zoom In (" + chord.getDisplayText() + ")", IconNames.withChord("Zoom In", chord));
        assertEquals("Zoom In", IconNames.withChord("Zoom In", null));
    }

    @Test
    void theZoomRowReadsTheChordsInForceEachTimeItOpens() throws Exception {
        KeyCombination[] zoomIn = {KeyCombination.valueOf("Ctrl+Shift+Z")};
        ZoomMenuRow.Actions actions = new ZoomMenuRow.Actions() {
            @Override
            public void zoomIn() {}

            @Override
            public void zoomOut() {}

            @Override
            public void reset() {}

            @Override
            public void toggleFullScreen() {}

            @Override
            public int percent() {
                return 100;
            }

            @Override
            public KeyCombination chordOf(String id) {
                return "zoomIn".equals(id) ? zoomIn[0] : null;
            }
        };

        Map<String, String> first = onFx(() -> new ZoomMenuRow(actions).tooltips());
        assertEquals("Zoom In (" + zoomIn[0].getDisplayText() + ")", first.get("zoomIn"));
        assertEquals("Zoom Out", first.get("zoomOut"), "an unbound command shows its title alone");

        // Rebound on the Keyboard page: the next open shows the new chord.
        Map<String, String> moved = onFx(() -> {
            ZoomMenuRow row = new ZoomMenuRow(actions);
            zoomIn[0] = KeyCombination.valueOf("Ctrl+Shift+Y");
            row.refresh();
            return row.tooltips();
        });
        assertEquals("Zoom In (" + zoomIn[0].getDisplayText() + ")", moved.get("zoomIn"));
    }

    /**
     * TBAR-03 (Pauta 6.0.1): the up arrow steps older, which is Find Next, and the down arrow is
     * Find Previous; each carries its own command's title and chord, and the switch its command's.
     */
    @Test
    void theFindBarsButtonsAreNamedForTheCommandsTheyRun() throws Exception {
        KeyCombination next = KeyCombination.valueOf("F3");
        KeyCombination regex = KeyCombination.valueOf("Alt+R");
        var tips = onFx(() -> {
            SearchBar bar = new SearchBar(new SearchBar.Listener() {
                @Override
                public void queryChanged(String query, boolean regex) {}

                @Override
                public void step(boolean older) {}

                @Override
                public void closed() {}
            });
            java.util.List<String> unbound = new java.util.ArrayList<>(bar.arrowTooltips());
            unbound.add(bar.regexTooltip());
            bar.showChords(id -> "findNext".equals(id) ? next : "findRegex".equals(id) ? regex : null);
            java.util.List<String> bound = new java.util.ArrayList<>(bar.arrowTooltips());
            bound.add(bar.regexTooltip());
            return java.util.List.of(unbound, bound);
        });
        assertEquals(java.util.List.of("Find Next", "Find Previous", "Toggle Regular Expression"), tips.get(0));
        assertEquals(
                java.util.List.of(
                        "Find Next (" + next.getDisplayText() + ")",
                        "Find Previous",
                        "Toggle Regular Expression (" + regex.getDisplayText() + ")"),
                tips.get(1));
    }

    /** FIND-16 (Pauta 6.0.1): the switch is out of the Tab order, so toggling it is a call away. */
    @Test
    void theRegularExpressionSwitchTogglesWithoutThePointer() throws Exception {
        java.util.List<Boolean> asked = new java.util.ArrayList<>();
        boolean on = onFx(() -> {
            SearchBar bar = new SearchBar(new SearchBar.Listener() {
                @Override
                public void queryChanged(String query, boolean regex) {
                    asked.add(regex);
                }

                @Override
                public void step(boolean older) {}

                @Override
                public void closed() {}
            });
            bar.toggleRegex();
            bar.toggleRegex();
            bar.toggleRegex();
            return bar.isRegex();
        });
        assertTrue(on);
        assertEquals(java.util.List.of(true, false, true), asked);
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }
}
