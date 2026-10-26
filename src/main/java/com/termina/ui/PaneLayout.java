package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Orientation;

import com.termina.pty.LaunchOptions;

/**
 * A tab's panes as data — what Reopen Closed Tab brings back, now that a tab can be more than one
 * shell.
 *
 * <p>The tree {@link PaneGroup} draws, without the nodes: a leaf is a pane's launch options (already
 * in the directory it was in when the tab closed) and the name the user gave it; a split is its
 * direction, its divider positions and its children. Reopened, each leaf is a fresh shell — the
 * processes are gone — in the arrangement and the places they were.
 *
 * <p>Pure, so the round trip through it is tested without a window.
 */
sealed interface PaneLayout {

    /** One pane: how to start it again, and its name, or "" for the automatic one. */
    record Leaf(LaunchOptions options, String userTitle) implements PaneLayout {
        public Leaf {
            options = options == null ? LaunchOptions.DEFAULTS : options;
            userTitle = userTitle == null ? "" : userTitle;
        }
    }

    /**
     * Panes side by side ({@link Orientation#HORIZONTAL}) or one above another, as JavaFX names a
     * split by the way its items run.
     */
    record Split(Orientation orientation, double[] dividers, List<PaneLayout> children) implements PaneLayout {
        public Split {
            if (children.size() < 2) throw new IllegalArgumentException("a split has at least two panes");
            dividers = dividers == null ? new double[0] : dividers.clone();
            children = List.copyOf(children);
        }

        @Override
        public double[] dividers() {
            return dividers.clone();
        }
    }

    /** Every leaf, in the tree's order — the order {@link PaneGroup#panes} lists panes in. */
    default List<Leaf> leaves() {
        List<Leaf> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(PaneLayout node, List<Leaf> out) {
        switch (node) {
            case Leaf leaf -> out.add(leaf);
            case Split split -> split.children().forEach(child -> collect(child, out));
        }
    }

    /**
     * A closed tab: its layout and which of its panes was the active one, by its index in {@link
     * #leaves}. Out of range is read as the first, so a hand-built one cannot fail to reopen.
     */
    record Tab(PaneLayout root, int activeLeaf) {
        public Tab {
            int count = root.leaves().size();
            activeLeaf = activeLeaf < 0 || activeLeaf >= count ? 0 : activeLeaf;
        }

        Leaf active() {
            return root.leaves().get(activeLeaf);
        }
    }
}
