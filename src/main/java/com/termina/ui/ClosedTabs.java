package com.termina.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
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
 * <p>A pane closed on its own by the Close chord is remembered here too, as a tab of one pane right
 * after the tab it was in: putting it back into its old split would need the split to still be the
 * same one, and a tab of its own is where it can always go.
 *
 * <p>Bounded, because this holds a working directory per entry for the life of the window and a
 * window can close a great many tabs; and pure apart from the deque, so the rules in it — where a
 * tab goes back, in which order a batch comes back, and which directory it reopens in — are tested
 * rather than assumed.
 */
final class ClosedTabs {

    /** Enough to undo a run of closes, few enough that nothing old is ever surprising. */
    static final int MAX = 10;

    /**
     * @param layout its panes, each already carrying the directory it was in when it closed
     * @param index where it sat in the strip when it closed
     * @param stamp when it closed, on a clock shared by every window, so Reopen can tell whether a
     *     tab closed here or a whole window closed elsewhere is the more recent; 0 when nothing asks
     */
    record Entry(PaneLayout.Tab layout, int index, long stamp) {

        Entry(PaneLayout.Tab layout, int index) {
            this(layout, index, 0);
        }

        /** A tab of one pane. */
        Entry(LaunchOptions options, String userTitle, int index) {
            this(new PaneLayout.Tab(new PaneLayout.Leaf(options, userTitle), 0), index, 0);
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

    /**
     * A whole window's tabs, closed together.
     *
     * @param tabs each tab, in strip order, with its place in that strip
     * @param selected which of them was selected
     * @param stamp when the window closed, on the clock {@link Entry#stamp} uses
     */
    record Window(List<Entry> tabs, int selected, long stamp) {
        Window {
            tabs = List.copyOf(tabs);
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();

    void remember(Entry entry) {
        entries.push(entry);
        while (entries.size() > MAX) entries.removeLast();
    }

    /**
     * Remembers tabs closed together — Close Other Tabs, Close Tabs to the Right — so that reopening
     * them one at a time puts each back where it was.
     *
     * <p>Each index was read before any of them went, so each is right only in a strip that already
     * holds every one of them to its left. Reopening leftmost first gives exactly that, which means
     * pushing rightmost first. Pushed in strip order, {@code [A,B,C,D]} closing all but A came back
     * {@code [A,B,D,C]}: D first, clamped to the end of a strip of one, then C at its old index —
     * before D.
     */
    void rememberAll(List<Entry> closedTogether) {
        for (Entry entry : reopenOrder(closedTogether).reversed()) remember(entry);
    }

    /** The order tabs closed together come back in: leftmost first. Pure. */
    static List<Entry> reopenOrder(List<Entry> closedTogether) {
        List<Entry> order = new ArrayList<>(closedTogether);
        order.sort(Comparator.comparingInt(Entry::index));
        return order;
    }

    /** The most recently closed tab, removed from the list. */
    Optional<Entry> pop() {
        return Optional.ofNullable(entries.poll());
    }

    /** The most recently closed tab, left in the list. */
    Optional<Entry> peek() {
        return Optional.ofNullable(entries.peek());
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }

    /**
     * Whether Reopen should bring back a closed window rather than this window's last closed tab:
     * when there is one, and it closed more recently than the tab did — undo goes back in time, and
     * which window the keys are pressed in does not change what happened last.
     *
     * @param tabStamp when this window's most recent closed tab closed, or -1 for none
     * @param windowStamp when the most recently closed window closed, or -1 for none
     */
    static boolean reopensWindow(long tabStamp, long windowStamp) {
        return windowStamp >= 0 && windowStamp > tabStamp;
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
     * there is one; where nothing has said (a WSL tab whose shell reports none) the original stands.
     */
    static LaunchOptions inDirectory(LaunchOptions options, String currentDirectory) {
        if (options == null) return LaunchOptions.DEFAULTS;
        if (currentDirectory == null || currentDirectory.isBlank()) return options;
        return options.withWorkingDirectory(currentDirectory);
    }
}
