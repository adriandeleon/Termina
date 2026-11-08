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
 */
final class OneMenu {

    private static final Set<ContextMenu> MENUS = Collections.newSetFromMap(new WeakHashMap<>());

    private OneMenu() {}

    /** Makes {@code menu} one of the menus of which only one shows; returns it. */
    static ContextMenu install(ContextMenu menu) {
        if (MENUS.add(menu)) menu.addEventHandler(WindowEvent.WINDOW_SHOWING, e -> closeOthers(menu));
        return menu;
    }

    private static void closeOthers(ContextMenu showing) {
        for (ContextMenu other : List.copyOf(MENUS)) {
            if (other != showing && other.isShowing()) other.hide();
        }
    }
}
