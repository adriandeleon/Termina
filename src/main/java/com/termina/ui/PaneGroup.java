package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.StackPane;

/**
 * A tab's content: one terminal, or several side by side and one above another.
 *
 * <p>A tree of {@link SplitPane}s with {@link TerminalView}s at the leaves. Splitting a pane in the
 * direction its parent already runs adds a sibling rather than nesting another split, so three
 * panes side by side are one row of three and not a pair inside a pair — which is what makes the
 * dividers share the width evenly, and what a person splitting twice expects. Closing a pane that
 * leaves a split with one child collapses the split, for the same reason in reverse.
 *
 * <p>One pane is <em>active</em> at a time: the one the keyboard was last in. It is what the tab
 * is titled after, what Copy, Find and a new tab's directory act on, and what gets the keyboard
 * back when the tab is selected. The window tells the group when focus moves into one of its panes
 * — including into a pane's find bar, where the pane itself does not have focus but is still the
 * one being worked in.
 *
 * <p>Pure layout: starting and closing the shells is the window's, which knows the session
 * lifecycle and the close prompt.
 */
final class PaneGroup extends StackPane {

    /** Which way a neighbour lies. */
    enum Direction {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    /** A pane's rectangle, for choosing a neighbour without needing a laid-out scene to test it. */
    record Rect(double x, double y, double width, double height) {
        double maxX() {
            return x + width;
        }

        double maxY() {
            return y + height;
        }
    }

    private final ReadOnlyObjectWrapper<TerminalView> active = new ReadOnlyObjectWrapper<>();

    /** The tree's top: a terminal, or a split. */
    private Node root;

    PaneGroup(TerminalView first) {
        getStyleClass().add("pane-group");
        setRoot(first);
        active.set(first);
    }

    TerminalView active() {
        return active.get();
    }

    ReadOnlyObjectProperty<TerminalView> activeProperty() {
        return active.getReadOnlyProperty();
    }

    /** Makes {@code pane} the one being worked in. Ignored for a pane that is not in this group. */
    void setActive(TerminalView pane) {
        if (pane != null && contains(pane) && active.get() != pane) {
            active.set(pane);
            showFocus();
        }
    }

    /** Every pane, left to right and top to bottom — the tree's own order. */
    List<TerminalView> panes() {
        List<TerminalView> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node node, List<TerminalView> out) {
        if (node instanceof TerminalView view) out.add(view);
        else if (node instanceof SplitPane split) for (Node item : split.getItems()) collect(item, out);
    }

    boolean contains(TerminalView pane) {
        return panes().contains(pane);
    }

    boolean isSplit() {
        return root instanceof SplitPane;
    }

    /**
     * Puts {@code added} beside {@code target}: to its right for {@link Orientation#HORIZONTAL},
     * below it for {@link Orientation#VERTICAL} — JavaFX names a split by the way its items run.
     */
    void split(TerminalView target, Orientation orientation, TerminalView added) {
        SplitPane parent = parentOf(target);
        if (parent != null && parent.getOrientation() == orientation) {
            parent.getItems().add(parent.getItems().indexOf(target) + 1, added);
            equalize(parent);
        } else {
            SplitPane split = new SplitPane();
            split.setOrientation(orientation);
            // Out of the traversal order like the rest of the chrome: Tab belongs to the shell.
            split.setFocusTraversable(false);
            replace(target, split);
            split.getItems().addAll(target, added);
            equalize(split);
        }
        showFocus();
    }

    /**
     * Takes a pane out, collapsing a split it leaves with a single child.
     *
     * @return the pane that should take the keyboard instead, or null when it was the last pane and
     *     the group is empty. The active pane stays active when another one was closed; closing the
     *     active one hands the keyboard to whatever took its space — the sibling that grew into it,
     *     as in Windows Terminal — rather than to some pane across the window.
     */
    TerminalView remove(TerminalView pane) {
        if (!contains(pane)) return active.get();
        SplitPane parent = parentOf(pane);
        if (parent == null) {
            setRoot(null);
            active.set(null);
            return null;
        }
        int index = parent.getItems().indexOf(pane);
        parent.getItems().remove(pane);
        Node successor;
        if (parent.getItems().size() == 1) {
            successor = parent.getItems().getFirst();
            parent.getItems().clear();
            replace(parent, successor);
        } else {
            successor = parent.getItems().get(Math.max(0, index - 1));
            equalize(parent);
        }
        TerminalView next = active.get() != pane && contains(active.get()) ? active.get() : firstPaneIn(successor);
        active.set(next);
        showFocus();
        return next;
    }

    private static TerminalView firstPaneIn(Node node) {
        List<TerminalView> found = new ArrayList<>();
        collect(node, found);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** The pane beside {@code from} in a direction, or null when it is at that edge. */
    TerminalView neighbour(TerminalView from, Direction direction) {
        List<TerminalView> all = panes();
        if (!all.contains(from) || all.size() < 2) return null;
        List<Rect> rects = new ArrayList<>();
        for (TerminalView pane : all) rects.add(rectOf(pane));
        int index = nearest(rects.get(all.indexOf(from)), rects, direction);
        return index < 0 ? null : all.get(index);
    }

    private Rect rectOf(TerminalView pane) {
        Bounds bounds = sceneToLocal(pane.localToScene(pane.getLayoutBounds()));
        return new Rect(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
    }

    /**
     * The rectangle to move to from {@code from} in a direction, or -1.
     *
     * <p>Only rectangles wholly on that side count — a divider's width of slack allowed — and among
     * them the ones that overlap {@code from} across the direction of travel win over ones that are
     * merely diagonal, so moving right from the top-left of a 2×2 grid lands top-right, not
     * bottom-right. Ties go to the nearer edge, then to the larger overlap.
     */
    static int nearest(Rect from, List<Rect> candidates, Direction direction) {
        final double slack = 8;
        int best = -1;
        double bestOverlap = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Rect r = candidates.get(i);
            if (r.equals(from)) continue;
            double distance;
            double overlap;
            switch (direction) {
                case RIGHT -> {
                    if (r.x() < from.maxX() - slack) continue;
                    distance = r.x() - from.maxX();
                    overlap = Math.min(from.maxY(), r.maxY()) - Math.max(from.y(), r.y());
                }
                case LEFT -> {
                    if (r.maxX() > from.x() + slack) continue;
                    distance = from.x() - r.maxX();
                    overlap = Math.min(from.maxY(), r.maxY()) - Math.max(from.y(), r.y());
                }
                case DOWN -> {
                    if (r.y() < from.maxY() - slack) continue;
                    distance = r.y() - from.maxY();
                    overlap = Math.min(from.maxX(), r.maxX()) - Math.max(from.x(), r.x());
                }
                default -> {
                    if (r.maxY() > from.y() + slack) continue;
                    distance = from.y() - r.maxY();
                    overlap = Math.min(from.maxX(), r.maxX()) - Math.max(from.x(), r.x());
                }
            }
            boolean overlaps = overlap > 0;
            boolean bestOverlaps = bestOverlap > 0;
            boolean better = best < 0
                    || (overlaps && !bestOverlaps)
                    || (overlaps == bestOverlaps
                            && (distance < bestDistance - 0.5
                                    || (Math.abs(distance - bestDistance) <= 0.5 && overlap > bestOverlap)));
            if (better) {
                best = i;
                bestOverlap = overlap;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Marks the active pane when there is more than one, so it can be told from the rest. */
    private void showFocus() {
        boolean split = isSplit();
        TerminalView current = active.get();
        for (TerminalView pane : panes()) pane.setPaneFocusShown(split && pane == current);
    }

    private void setRoot(Node node) {
        root = node;
        if (node == null) getChildren().clear();
        else getChildren().setAll(node);
    }

    /** Puts {@code replacement} where {@code node} is in the tree. */
    private void replace(Node node, Node replacement) {
        SplitPane parent = parentOf(node);
        if (parent == null) {
            setRoot(replacement);
        } else {
            int index = parent.getItems().indexOf(node);
            double[] dividers = parent.getDividerPositions();
            parent.getItems().set(index, replacement);
            parent.setDividerPositions(dividers);
        }
    }

    /**
     * The split holding a node. Looked up from the root rather than through {@code getParent()},
     * which for an item of a SplitPane is the skin's private wrapper and not the SplitPane.
     */
    private SplitPane parentOf(Node node) {
        return parentOf(root, node);
    }

    private static SplitPane parentOf(Node from, Node node) {
        if (!(from instanceof SplitPane split)) return null;
        for (Node item : split.getItems()) {
            if (item == node) return split;
            SplitPane found = parentOf(item, node);
            if (found != null) return found;
        }
        return null;
    }

    private static void equalize(SplitPane split) {
        int n = split.getItems().size();
        double[] positions = new double[n - 1];
        for (int i = 0; i < positions.length; i++) positions[i] = (i + 1) / (double) n;
        split.setDividerPositions(positions);
    }

    /** The tree, for the capture hook: {@code H[a, V[b, c]]}, each pane by its index in {@link #panes}. */
    String describe() {
        List<TerminalView> all = panes();
        StringBuilder out = new StringBuilder();
        describe(root, all, out);
        return out.toString();
    }

    private void describe(Node node, List<TerminalView> all, StringBuilder out) {
        if (node instanceof TerminalView view) {
            out.append(all.indexOf(view)).append(view == active.get() ? "*" : "");
        } else if (node instanceof SplitPane split) {
            out.append(split.getOrientation() == Orientation.HORIZONTAL ? "H[" : "V[");
            for (int i = 0; i < split.getItems().size(); i++) {
                if (i > 0) out.append(", ");
                describe(split.getItems().get(i), all, out);
            }
            out.append(']');
        }
    }
}
