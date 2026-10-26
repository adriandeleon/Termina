package com.termina.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import com.termina.pty.LaunchOptions;

/**
 * The tabs a window has closed, most recent first, so one can be brought back.
 *
 * <p>What comes back is the <em>tab</em>, not its contents: the shell each of its panes was running,
 * the directory each was in when it closed, the arrangement of the panes and which was active, the
 * names the user gave them, and its place in the strip (see {@link PaneLayout}). A
 * scrollback cannot be reopened — the process is gone — and pretending otherwise would be worse
 * than saying so. Browsers draw the same line, which is where the expectation comes from.
 *
 * <p>Bounded, because this holds a working directory per entry for the life of the window and a
 * window can close a great many tabs; and pure apart from the deque, so the two rules in it — where
 * a tab goes back, and which directory it reopens in — are tested rather than assumed.
 */
final class ClosedTabs {

    /** Enough to undo a run of closes, few enough that nothing old is ever surprising. */
    static final int MAX = 10;

    /**
     * @param layout its panes, each already carrying the directory it was in when it closed
     * @param index where it sat in the strip when it closed
     */
    record Entry(PaneLayout.Tab layout, int index) {

        /** A tab of one pane. */
        Entry(LaunchOptions options, String userTitle, int index) {
            this(new PaneLayout.Tab(new PaneLayout.Leaf(options, userTitle), 0), index);
        }

        /** How to start the active pane again — the profile's command or the shell, and its directory. */
        LaunchOptions options() {
            return layout.active().options();
        }

        /** The name the user gave the active pane, or "" when it had the automatic title. */
        String userTitle() {
            return layout.active().userTitle();
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();

    void remember(Entry entry) {
        entries.push(entry);
        while (entries.size() > MAX) entries.removeLast();
    }

    /** The most recently closed tab, removed from the list. */
    Optional<Entry> pop() {
        return Optional.ofNullable(entries.poll());
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }

    /**
     * Where a reopened tab goes: its old place, clamped to the strip as it is now.
     *
     * <p>Its old place rather than the end, which is what a browser does and what makes undoing a
     * close feel like undoing. The strip may have shrunk since — the tabs after it may have closed
     * too — so the index is held to the list's current size, which appends.
     */
    static int reopenIndex(int savedIndex, int tabCount) {
        return Math.max(0, Math.min(savedIndex, Math.max(0, tabCount)));
    }

    /**
     * A tab's launch options, in the directory it is in <em>now</em>.
     *
     * <p>The options a tab was opened with name where it <em>started</em>; by the time it closes or
     * is duplicated the shell has usually moved. The directory the watcher last reported wins when
     * there is one; where the OS will not say (Windows) the original stands.
     */
    static LaunchOptions inDirectory(LaunchOptions options, String currentDirectory) {
        if (options == null) return LaunchOptions.DEFAULTS;
        if (currentDirectory == null || currentDirectory.isBlank()) return options;
        return options.withWorkingDirectory(currentDirectory);
    }
}
