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

    /** Reopens everything in {@code closed} one Reopen at a time into {@code strip}, as the window does. */
    private static List<String> reopenAll(ClosedTabs closed, List<String> strip) {
        List<String> tabs = new java.util.ArrayList<>(strip);
        for (var entry = closed.pop(); entry.isPresent(); entry = closed.pop()) {
            tabs.add(
                    ClosedTabs.reopenIndex(entry.get().index(), tabs.size()),
                    entry.get().options().workingDirectory());
        }
        return tabs;
    }

    @Test
    void closeOthersThenReopenComesBackInOrder() {
        // [A,B,C,D], Close Other Tabs on A: pushed in strip order this came back [A,B,D,C].
        ClosedTabs closed = new ClosedTabs();
        closed.rememberAll(List.of(entry("B", 1), entry("C", 2), entry("D", 3)));
        assertEquals(List.of("A", "B", "C", "D"), reopenAll(closed, List.of("A")));
    }

    @Test
    void closeOthersAroundAMiddleTabComesBackInOrder() {
        ClosedTabs closed = new ClosedTabs();
        closed.rememberAll(List.of(entry("A", 0), entry("B", 1), entry("D", 3)));
        assertEquals(List.of("A", "B", "C", "D"), reopenAll(closed, List.of("C")));
    }

    @Test
    void closeToTheRightThenReopenComesBackInOrder() {
        ClosedTabs closed = new ClosedTabs();
        // In whatever order the caller had them: the order is ClosedTabs' to decide.
        closed.rememberAll(List.of(entry("D", 3), entry("C", 2)));
        assertEquals(List.of("A", "B", "C", "D"), reopenAll(closed, List.of("A", "B")));
    }

    @Test
    void theNewestCloseIsWhatReopenUndoes() {
        // A window closed after this window's last tab closed comes back first, and not before.
        assertTrue(ClosedTabs.reopensWindow(3, 7));
        assertFalse(ClosedTabs.reopensWindow(7, 3));
        assertTrue(ClosedTabs.reopensWindow(-1, 1));
        assertFalse(ClosedTabs.reopensWindow(4, -1));
        assertFalse(ClosedTabs.reopensWindow(-1, -1));
    }

    @Test
    void peekLeavesTheEntry() {
        ClosedTabs closed = new ClosedTabs();
        assertTrue(closed.peek().isEmpty());
        closed.remember(entry("/a", 0));
        assertEquals("/a", closed.peek().orElseThrow().options().workingDirectory());
        assertEquals(1, closed.size());
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
