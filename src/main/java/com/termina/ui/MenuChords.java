package com.termina.ui;

import java.util.List;
import java.util.function.Function;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;

import org.jspecify.annotations.Nullable;

/**
 * The chord column of the right-click menus (MENU-15, Pauta 6.0.1): a row that runs a command
 * shows the chord the command is on now, as the menu bar's rows do, and an unbound one shows none.
 *
 * <p>A row says which command it runs with {@link #command}; the menu calls {@link #fill} as it
 * shows, so a chord moved on the Keyboard page is the one shown next time. The chord is the
 * item's accelerator, which outside the menu bar is only ever a label: a terminal window keeps its
 * scene's accelerator table empty ({@code TerminalWindow.keepAcceleratorsDisplayOnly}), because
 * its key filter already runs every binding.
 */
final class MenuChords {

    private static final String COMMAND = "termina.menuChords.command";

    private MenuChords() {}

    /** Marks {@code item} as running command {@code id}; returns it. */
    static MenuItem command(MenuItem item, String id) {
        item.getProperties().put(COMMAND, id);
        return item;
    }

    /** The command a row runs, or null for a row that runs none: a separator, a submenu, a thing's name. */
    static @Nullable String commandOf(MenuItem item) {
        return item.getProperties().get(COMMAND) instanceof String id ? id : null;
    }

    /** Puts each command row's chord in its cell, and clears the cell of a command that has none. */
    static void fill(List<? extends MenuItem> items, Function<String, @Nullable KeyCombination> chordOf) {
        for (MenuItem item : items) {
            String id = commandOf(item);
            if (id != null) item.setAccelerator(chordOf.apply(id));
            if (item instanceof Menu menu) fill(menu.getItems(), chordOf);
        }
    }
}
