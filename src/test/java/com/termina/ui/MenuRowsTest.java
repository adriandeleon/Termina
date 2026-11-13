package com.termina.ui;

import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The macOS screen menu bar's rows (MENU-02): plain text with the chord after the title, no icon and
 * no accelerator. The in-window rows are drawn, and checked in {@link MenusFxTest}.
 */
@Tag("fx")
class MenuRowsTest {

    /**
     * A separator is a control, and a control's class needs the toolkit: without this the test
     * passed only when another had started it, and failed in a run that leaves the fx tests out.
     */
    @org.junit.jupiter.api.BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
        javafx.application.Platform.setImplicitExit(false);
    }

    @Test
    void aSystemRowIsTextWithTheChordAfterTheTitle() {
        MenuItem row = MenuRows.row("New Tab", null, true);
        assertEquals("New Tab", row.getText());
        assertNull(row.getGraphic(), "no icon on the screen menu bar");
        KeyCombination chord = KeyCombination.valueOf("Shortcut+Shift+T");
        MenuRows.setChord(row, chord);
        assertEquals("New Tab" + MenuRows.SYSTEM_GAP + chord.getDisplayText(), row.getText());
        assertNull(row.getAccelerator());
        assertEquals("New Tab", MenuRows.titleOf(row));
        assertEquals(chord.getDisplayText(), MenuRows.chordText(row));
    }

    @Test
    void aNewTitleKeepsTheChordAndANewChordKeepsTheTitle() {
        MenuItem row = MenuRows.row("Pin Tab", null, true);
        KeyCombination chord = KeyCombination.valueOf("Shortcut+Shift+P");
        MenuRows.setChord(row, chord);
        MenuRows.setTitle(row, "Unpin Tab");
        assertEquals("Unpin Tab" + MenuRows.SYSTEM_GAP + chord.getDisplayText(), row.getText());
        MenuRows.setChord(row, null);
        assertEquals("Unpin Tab", row.getText(), "an unbound command shows its title alone");
    }

    @Test
    void aRowIsKnownByItsTitleAndNothingElseIsARow() {
        assertTrue(MenuRows.isRow(MenuRows.row("Copy", null, true)));
        assertFalse(MenuRows.isRow(new MenuItem("Copy")));
        assertEquals("Copy", MenuRows.titleOf(new MenuItem("Copy")));
        assertEquals("", MenuRows.chordText(new MenuItem("Copy")));
    }

    @Test
    void rowsBuiltForTheScreenMenuBarLeaveItToAppKit() {
        // One CustomMenuItem anywhere and JavaFX declines useSystemMenuBar for every menu: macOS
        // would be left with no menus at all. The system rows, submenu included, must not be one.
        javafx.scene.control.Menu file = new javafx.scene.control.Menu("File");
        javafx.scene.control.Menu submenu = MenuRows.submenu("New Tab With", null, true);
        submenu.getItems().add(MenuRows.row("zsh", null, true));
        file.getItems()
                .addAll(MenuRows.row("New Tab", null, true), submenu, new javafx.scene.control.SeparatorMenuItem());
        assertTrue(TerminalWindow.menusFitASystemMenuBar(java.util.List.of(file)));
    }
}
