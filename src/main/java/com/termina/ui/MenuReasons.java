package com.termina.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;

import org.jspecify.annotations.Nullable;

/**
 * Why a greyed menu row cannot run, on the row (KBD-04, Pauta 6.0.1): the row stays in the menu,
 * disabled, and resting the pointer on it shows the reason the palette gives for the same command.
 *
 * <p>A disabled node takes no mouse events, so a tooltip installed on the row itself never shows.
 * The rows' parent does take them: one tooltip is installed there while the pointer is over a
 * greyed row that has a reason, with that row's words, and taken off again over any other row.
 * JavaFX's own tooltip timing does the rest.
 *
 * <p>Not on the macOS screen menu bar, whose rows are AppKit's and have no node: they are greyed
 * there and say nothing.
 */
final class MenuReasons {

    private static final String REASON = "termina.menu.reason";
    private static final String WATCHED = "termina.menu.reasonsWatched";

    private MenuReasons() {}

    /** Greys {@code item} with the reason, or makes it available again with null. */
    static void set(MenuItem item, @Nullable String reason) {
        item.setDisable(reason != null);
        if (reason == null) item.getProperties().remove(REASON);
        else item.getProperties().put(REASON, reason);
        // And for a screen reader, which is told the row is dimmed but not why: a row drawn from
        // our own nodes is named from its content, so the reason is that node's help text. A
        // plain MenuItem has no accessible property at all to carry one.
        if (item instanceof CustomMenuItem custom && custom.getContent() != null) {
            custom.getContent().setAccessibleHelp(reason);
        }
    }

    /** Why {@code item} is greyed, or null: it can run, or nothing said why. */
    static @Nullable String of(MenuItem item) {
        return item.getProperties().get(REASON) instanceof String reason ? reason : null;
    }

    /**
     * After a menu has shown: its greyed rows answer the pointer with their reason. Safe to call
     * on every showing; the rows' parent is watched once.
     */
    static void attach(List<? extends MenuItem> items) {
        Parent rows = null;
        for (MenuItem item : items) {
            Node node = item.getStyleableNode();
            if (node != null && node.getParent() != null) {
                rows = node.getParent();
                break;
            }
        }
        if (rows == null || rows.getProperties().containsKey(WATCHED)) return;
        Tooltip tooltip = IconNames.tooltip();
        rows.getProperties().put(WATCHED, tooltip);
        Parent watched = rows;
        watched.addEventFilter(
                MouseEvent.MOUSE_MOVED, e -> point(watched, tooltip, reasonAt(items, e.getX(), e.getY())));
        watched.addEventFilter(MouseEvent.MOUSE_EXITED, e -> point(watched, tooltip, null));
    }

    /** The tooltip a menu's rows are watched with, for a test; null before {@link #attach}. */
    static @Nullable Tooltip tooltipOf(Parent rows) {
        return rows.getProperties().get(WATCHED) instanceof Tooltip tooltip ? tooltip : null;
    }

    /** Puts the tooltip on the rows with {@code reason}, or takes it off with null. */
    private static void point(Parent rows, Tooltip tooltip, @Nullable String reason) {
        boolean installed = Boolean.TRUE.equals(tooltip.getUserData());
        if (reason == null) {
            if (installed) {
                Tooltip.uninstall(rows, tooltip);
                tooltip.setUserData(Boolean.FALSE);
            }
            return;
        }
        if (!reason.equals(tooltip.getText())) tooltip.setText(reason);
        if (!installed) {
            Tooltip.install(rows, tooltip);
            tooltip.setUserData(Boolean.TRUE);
        }
    }

    /** The reason of the greyed row under a point in the rows' parent, or null. */
    private static @Nullable String reasonAt(List<? extends MenuItem> items, double x, double y) {
        for (MenuItem item : items) {
            String reason = of(item);
            if (reason == null || !item.isDisable()) continue;
            Node node = item.getStyleableNode();
            if (node != null && node.getBoundsInParent().contains(x, y)) return reason;
        }
        return null;
    }
}
