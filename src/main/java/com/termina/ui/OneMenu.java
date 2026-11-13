package com.termina.ui;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import javafx.scene.control.ContextMenu;
import javafx.stage.WindowEvent;

/**
 * One right-click menu at a time (MENU-06, Pauta 2.0.2): a menu that starts to show closes any
 * other that is open, before it appears.
 *
 * <p>Each tab owns its menu, each pane its own, and the profile chevron another. A right-click
 * closes an open menu through auto-hide, but a menu opened any other way — the Menu key, Tab Menu
 * from the palette — opens beside the first, which then has nothing to dismiss it. Weakly held, so
 * a closed tab's menu goes with the tab.
 *
 * <p>And a press anywhere outside a right-click menu closes it (MENU-07, Pauta 6.0.1). JavaFX's own
 * auto-hide ignores a press on the node the menu was opened on, so a tab's menu stayed up through a
 * click on the tab strip. {@link #closeOnOutsidePress} is the window's filter for that; a drop-down
 * opened by a plain click, the profile chevron's, is installed with {@link #install} alone and
 * keeps the stock behaviour, or the press that should close it would close and reopen it.
 */
final class OneMenu {

    private static final Set<ContextMenu> MENUS = Collections.newSetFromMap(new WeakHashMap<>());

    private OneMenu() {}

    /** Makes {@code menu} one of the menus of which only one shows; returns it. */
    static ContextMenu install(ContextMenu menu) {
        if (MENUS.add(menu)) menu.addEventHandler(WindowEvent.WINDOW_SHOWING, e -> closeOthers(menu));
        return menu;
    }

    private static final Set<ContextMenu> RIGHT_CLICK = Collections.newSetFromMap(new WeakHashMap<>());

    /** {@link #install}, for a menu opened by a right-click: a press outside it closes it. */
    static ContextMenu installRightClick(ContextMenu menu) {
        RIGHT_CLICK.add(menu);
        return install(menu);
    }

    /**
     * Closes a showing right-click menu on any press in {@code scene}, the control it was opened
     * on included. The press is not consumed: a click still does what it does, and a right-click
     * opens the menu of whatever is under it. A press inside the menu never gets here, the menu
     * being a window of its own.
     */
    static void closeOnOutsidePress(javafx.scene.Scene scene) {
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> closeRightClickMenus());
    }

    /** Hides every showing right-click menu, but one that turned auto-hide off: that is its own. */
    static void closeRightClickMenus() {
        for (ContextMenu menu : List.copyOf(RIGHT_CLICK)) {
            if (menu.isShowing() && menu.isAutoHide()) menu.hide();
        }
    }

    private static void closeOthers(ContextMenu showing) {
        for (ContextMenu other : List.copyOf(MENUS)) {
            if (other != showing && other.isShowing()) other.hide();
        }
    }
}
