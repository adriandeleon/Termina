package com.termina.ui;

import java.util.List;
import java.util.Map;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The right-click menus' chord column (MENU-15, Pauta 6.0.1). */
class MenuChordsTest {

    @Test
    void aCommandRowShowsItsChordAndAnUnboundOneShowsNone() {
        MenuItem copy = MenuChords.command(new MenuItem("Copy"), "copy");
        MenuItem rename = MenuChords.command(new MenuItem("Rename Tab…"), "renameTab");
        MenuItem profile = new MenuItem("zsh"); // names a thing, runs no command
        Menu submenu = new Menu("New Tab With");
        MenuItem inside = MenuChords.command(new MenuItem("New Tab"), "newTab");
        submenu.getItems().add(inside);
        KeyCombination copyChord = KeyCombination.valueOf("Ctrl+Shift+C");
        KeyCombination newTabChord = KeyCombination.valueOf("Ctrl+Shift+T");
        Map<String, KeyCombination> chords = Map.of("copy", copyChord, "newTab", newTabChord);

        MenuChords.fill(List.of(copy, rename, profile, submenu), chords::get);

        assertEquals(copyChord, copy.getAccelerator());
        assertNull(rename.getAccelerator(), "an unbound command leaves the cell empty");
        assertNull(profile.getAccelerator());
        assertEquals(newTabChord, inside.getAccelerator(), "rows of a submenu too");
        assertEquals("copy", MenuChords.commandOf(copy));
        assertNull(MenuChords.commandOf(profile));

        // A keymap change rewrites every cell: moved, and taken away.
        KeyCombination moved = KeyCombination.valueOf("Alt+C");
        MenuChords.fill(List.of(copy, rename, profile, submenu), Map.of("copy", moved)::get);
        assertEquals(moved, copy.getAccelerator());
        assertNull(inside.getAccelerator());
    }
}
