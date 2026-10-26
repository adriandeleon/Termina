package com.termina.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Orientation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a tab's panes are arranged as they are split and closed, and which one a move lands on. */
class PaneGroupTest {

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
    }

    /** Controls are built and changed on the FX thread, as they are in the application. */
    private static <T> T onFx(Supplier<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static TerminalView pane() {
        return new TerminalView(13);
    }

    @Test
    void splittingTheSameWayAddsASiblingRatherThanNesting() throws Exception {
        assertEquals("H[0, 1, 2]", onFx(() -> {
            TerminalView a = pane();
            TerminalView b = pane();
            PaneGroup group = new PaneGroup(a);
            group.split(a, Orientation.HORIZONTAL, b);
            group.split(b, Orientation.HORIZONTAL, pane());
            return group.describe().replace("*", "");
        }));
    }

    @Test
    void splittingTheOtherWayNests() throws Exception {
        assertEquals("H[0*, V[1, 2]]", onFx(() -> {
            TerminalView a = pane();
            TerminalView b = pane();
            PaneGroup group = new PaneGroup(a);
            group.split(a, Orientation.HORIZONTAL, b);
            group.split(b, Orientation.VERTICAL, pane());
            return group.describe();
        }));
    }

    @Test
    void closingAPaneCollapsesASplitLeftWithOne() throws Exception {
        assertEquals("H[0, 1]", onFx(() -> {
            TerminalView a = pane();
            TerminalView b = pane();
            TerminalView c = pane();
            PaneGroup group = new PaneGroup(a);
            group.split(a, Orientation.HORIZONTAL, b);
            group.split(b, Orientation.VERTICAL, c);
            group.remove(b);
            return group.describe().replace("*", "");
        }));
    }

    @Test
    void closingTheActivePaneHandsTheKeyboardToWhatTookItsSpace() throws Exception {
        onFx(() -> {
            TerminalView a = pane();
            TerminalView b = pane();
            TerminalView c = pane();
            PaneGroup group = new PaneGroup(a);
            group.split(a, Orientation.HORIZONTAL, b);
            group.split(b, Orientation.VERTICAL, c);
            group.setActive(b);
            // b's space goes to c, below it — not to a, across the window.
            assertSame(c, group.remove(b));
            assertSame(c, group.active());
            return null;
        });
    }

    @Test
    void closingAnotherPaneLeavesTheActiveOneActive() throws Exception {
        onFx(() -> {
            TerminalView a = pane();
            TerminalView b = pane();
            PaneGroup group = new PaneGroup(a);
            group.split(a, Orientation.HORIZONTAL, b);
            group.setActive(a);
            assertSame(a, group.remove(b));
            assertFalse(group.isSplit());
            return null;
        });
    }

    @Test
    void closingTheLastPaneEmptiesTheGroup() throws Exception {
        onFx(() -> {
            TerminalView a = pane();
            PaneGroup group = new PaneGroup(a);
            assertNull(group.remove(a));
            assertTrue(group.panes().isEmpty());
            return null;
        });
    }

    @Test
    void onlyAPaneInTheGroupCanBeMadeActive() throws Exception {
        onFx(() -> {
            TerminalView a = pane();
            PaneGroup group = new PaneGroup(a);
            group.setActive(pane());
            assertSame(a, group.active());
            return null;
        });
    }

    private static PaneGroup.Rect rect(double x, double y, double w, double h) {
        return new PaneGroup.Rect(x, y, w, h);
    }

    /** A 2×2 grid, 100 wide and tall per cell, with a 2px divider between. */
    private static final List<PaneGroup.Rect> GRID =
            List.of(rect(0, 0, 100, 100), rect(102, 0, 100, 100), rect(0, 102, 100, 100), rect(102, 102, 100, 100));

    @Test
    void movesToTheNeighbourThatOverlapsRatherThanTheDiagonal() {
        assertEquals(1, PaneGroup.nearest(GRID.get(0), GRID, PaneGroup.Direction.RIGHT));
        assertEquals(2, PaneGroup.nearest(GRID.get(0), GRID, PaneGroup.Direction.DOWN));
        assertEquals(1, PaneGroup.nearest(GRID.get(3), GRID, PaneGroup.Direction.UP));
        assertEquals(2, PaneGroup.nearest(GRID.get(3), GRID, PaneGroup.Direction.LEFT));
    }

    @Test
    void anEdgeHasNothingBeyondIt() {
        assertEquals(-1, PaneGroup.nearest(GRID.get(0), GRID, PaneGroup.Direction.LEFT));
        assertEquals(-1, PaneGroup.nearest(GRID.get(0), GRID, PaneGroup.Direction.UP));
    }

    @Test
    void fromATallPaneTheNearerOfTwoStackedOnesWins() {
        // One pane on the left, two stacked on the right: both overlap, and the top one is first.
        List<PaneGroup.Rect> panes = List.of(rect(0, 0, 100, 200), rect(102, 0, 100, 99), rect(102, 101, 100, 99));
        assertEquals(1, PaneGroup.nearest(panes.get(0), panes, PaneGroup.Direction.RIGHT));
        assertEquals(0, PaneGroup.nearest(panes.get(2), panes, PaneGroup.Direction.LEFT));
    }

    @Test
    void maximizingShowsOnePaneAndKeepsTheRestInTheTree() throws Exception {
        assertEquals("H[0, V[1*, 2]] maximized=1 children=2 panes=3", onFx(() -> {
            TerminalView a = pane();
            TerminalView b = pane();
            PaneGroup group = new PaneGroup(a);
            group.split(a, Orientation.HORIZONTAL, b);
            group.split(b, Orientation.VERTICAL, pane());
            group.setActive(b);
            assertTrue(group.maximize(b));
            // The pane and its badge are all the group shows; every pane is still the group's.
            return group.describe() + " children=" + group.getChildren().size() + " panes="
                    + group.panes().size();
        }));
    }

    @Test
    void restoringPutsThePaneBackWhereItWas() throws Exception {
        assertEquals("H[0, V[1*, 2]]", onFx(() -> {
            TerminalView a = pane();
            TerminalView b = pane();
            PaneGroup group = new PaneGroup(a);
            group.split(a, Orientation.HORIZONTAL, b);
            group.split(b, Orientation.VERTICAL, pane());
            group.setActive(b);
            group.maximize(b);
            group.restore();
            assertFalse(group.isMaximized());
            return group.describe();
        }));
    }

    @Test
    void aLonePaneHasNothingToBeMaximizedOver() throws Exception {
        assertFalse(onFx(() -> {
            TerminalView a = pane();
            return new PaneGroup(a).maximize(a);
        }));
    }

    @Test
    void splittingOrClosingWhileMaximizedRestoresFirst() throws Exception {
        assertEquals("H[0, 1*, 2]|H[0, 1*]", onFx(() -> {
            TerminalView a = pane();
            TerminalView b = pane();
            PaneGroup group = new PaneGroup(a);
            group.split(a, Orientation.HORIZONTAL, b);
            group.setActive(b);
            group.maximize(b);
            TerminalView c = pane();
            group.split(b, Orientation.HORIZONTAL, c);
            String afterSplit = group.describe();
            group.maximize(b);
            group.setActive(c);
            group.remove(b);
            return afterSplit + "|" + group.describe();
        }));
    }

    @Test
    void aLayoutRebuildsTheSameTreeWithTheSamePaneActive() throws Exception {
        assertEquals("H[0, V[1, 2*]]|H[0, V[1, 2*]]", onFx(() -> {
            TerminalView a = pane();
            TerminalView b = pane();
            TerminalView c = pane();
            PaneGroup group = new PaneGroup(a);
            group.split(a, Orientation.HORIZONTAL, b);
            group.split(b, Orientation.VERTICAL, c);
            group.setActive(c);
            PaneLayout.Tab layout = group.layout(pane -> new PaneLayout.Leaf(pane.launchOptions(), ""));
            PaneGroup rebuilt = PaneGroup.of(layout, leaf -> pane());
            return group.describe() + "|" + rebuilt.describe();
        }));
    }

    @Test
    void aMaximizedPaneIsLaidOutInItsPlaceNotOnItsOwn() throws Exception {
        assertEquals(2, onFx(() -> {
            TerminalView a = pane();
            TerminalView b = pane();
            PaneGroup group = new PaneGroup(a);
            group.split(a, Orientation.HORIZONTAL, b);
            group.maximize(b);
            return group.layout(pane -> new PaneLayout.Leaf(pane.launchOptions(), ""))
                    .root()
                    .leaves()
                    .size();
        }));
    }
}
