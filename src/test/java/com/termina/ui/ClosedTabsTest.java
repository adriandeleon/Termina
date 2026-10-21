package com.termina.ui;

import java.util.List;

import com.termina.pty.LaunchOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What comes back when a closed tab is reopened, and where. */
class ClosedTabsTest {

    private static ClosedTabs.Entry entry(String directory, int index) {
        return new ClosedTabs.Entry(LaunchOptions.ofShell("").withWorkingDirectory(directory), "", index);
    }

    @Test
    void theMostRecentlyClosedComesBackFirst() {
        ClosedTabs closed = new ClosedTabs();
        closed.remember(entry("/a", 0));
        closed.remember(entry("/b", 1));
        assertEquals("/b", closed.pop().orElseThrow().options().workingDirectory());
        assertEquals("/a", closed.pop().orElseThrow().options().workingDirectory());
        assertTrue(closed.pop().isEmpty());
        assertTrue(closed.isEmpty());
    }

    @Test
    void onlyTheLastFewAreKept() {
        ClosedTabs closed = new ClosedTabs();
        for (int i = 0; i < ClosedTabs.MAX + 5; i++) closed.remember(entry("/" + i, i));
        assertEquals(ClosedTabs.MAX, closed.size());
        // The oldest went, not the newest.
        assertEquals(
                "/" + (ClosedTabs.MAX + 4), closed.pop().orElseThrow().options().workingDirectory());
    }

    @Test
    void aTabGoesBackWhereItWasUnlessTheStripHasShrunk() {
        // Undoing a close puts the tab back in its place, like a browser does.
        assertEquals(2, ClosedTabs.reopenIndex(2, 5));
        // The tabs after it have closed too: append rather than index past the end.
        assertEquals(1, ClosedTabs.reopenIndex(4, 1));
        assertEquals(0, ClosedTabs.reopenIndex(3, 0));
        assertEquals(0, ClosedTabs.reopenIndex(-1, 3));
    }

    @Test
    void aReopenedOrDuplicatedTabStartsWhereTheShellIsNow() {
        LaunchOptions started = new LaunchOptions("", "/home/adl", List.of("/usr/bin/fish", "-l"));
        LaunchOptions now = ClosedTabs.inDirectory(started, "/home/adl/src");
        assertEquals("/home/adl/src", now.workingDirectory());
        assertEquals(started.command(), now.command());
        // Where the OS will not say, the original directory stands — and so does the object.
        assertSame(started, ClosedTabs.inDirectory(started, ""));
        assertSame(started, ClosedTabs.inDirectory(started, null));
        assertFalse(ClosedTabs.inDirectory(null, "/x").hasCommand());
    }
}
