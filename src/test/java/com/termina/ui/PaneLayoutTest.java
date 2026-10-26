package com.termina.ui;

import java.util.List;

import javafx.geometry.Orientation;

import com.termina.pty.LaunchOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A closed tab's panes as data. */
class PaneLayoutTest {

    private static PaneLayout.Leaf leaf(String directory) {
        return new PaneLayout.Leaf(LaunchOptions.ofShell("").withWorkingDirectory(directory), "");
    }

    @Test
    void leavesComeInTheTreesOrder() {
        PaneLayout tree = new PaneLayout.Split(
                Orientation.HORIZONTAL,
                new double[] {0.5},
                List.of(
                        leaf("/a"),
                        new PaneLayout.Split(
                                Orientation.VERTICAL, new double[] {0.3}, List.of(leaf("/b"), leaf("/c")))));
        assertEquals(
                List.of("/a", "/b", "/c"),
                tree.leaves().stream().map(l -> l.options().workingDirectory()).toList());
    }

    @Test
    void anActiveIndexOutOfRangeReopensTheFirst() {
        PaneLayout tree = new PaneLayout.Split(Orientation.HORIZONTAL, null, List.of(leaf("/a"), leaf("/b")));
        assertEquals("/a", new PaneLayout.Tab(tree, 7).active().options().workingDirectory());
        assertEquals("/b", new PaneLayout.Tab(tree, 1).active().options().workingDirectory());
    }

    @Test
    void aSplitOfOneIsNotASplit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaneLayout.Split(Orientation.HORIZONTAL, null, List.of(leaf("/a"))));
    }

    @Test
    void theDividersCannotBeChangedFromOutside() {
        double[] dividers = {0.25};
        var split = new PaneLayout.Split(Orientation.HORIZONTAL, dividers, List.of(leaf("/a"), leaf("/b")));
        dividers[0] = 0.9;
        split.dividers()[0] = 0.8;
        assertEquals(0.25, split.dividers()[0]);
    }

    @Test
    void aOnePaneEntryStillAnswersForItsPane() {
        var entry = new ClosedTabs.Entry(LaunchOptions.ofShell("").withWorkingDirectory("/x"), "Logs", 2);
        assertEquals("/x", entry.options().workingDirectory());
        assertEquals("Logs", entry.userTitle());
    }
}
