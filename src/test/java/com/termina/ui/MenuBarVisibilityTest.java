package com.termina.ui;

import java.util.List;

import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether the menu bar takes up a row in the window. */
@Tag("fx")
class MenuBarVisibilityTest {

    /**
     * A {@link SeparatorMenuItem} builds a {@link javafx.scene.control.Separator}, and a Control's
     * static initialiser asks the toolkit for the platform stylesheet — so one of the cases below
     * cannot be constructed without a toolkit, even though nothing here is rendered or shown.
     * JavaFX 26 has a headless Glass platform built in, which starts without a display and needs no
     * test harness or extra dependency.
     */
    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
    }

    @Test
    void inWindowMenuBarFollowsTheSetting() {
        assertTrue(WindowRules.menuBarOccupiesSpace(false, true));
        assertFalse(WindowRules.menuBarOccupiesSpace(false, false));
    }

    @Test
    void aScreenMenuBarNeverTakesSpaceWhateverTheSettingSays() {
        // The node stays in the scene graph because that is what JavaFX forwards to the system
        // bar, but leaving it measurable costs a band of empty chrome above the terminal — which
        // is exactly the bug this rule was extracted from.
        assertFalse(WindowRules.menuBarOccupiesSpace(true, true));
        assertFalse(WindowRules.menuBarOccupiesSpace(true, false));
    }

    @Test
    void theToggleIsOfferedOnlyWhereThereIsABarInTheWindowToHide() {
        // Read by two menus — the View menu's Hide item and the right-click menu's checkbox — which
        // is the whole reason it is a named rule rather than an `if` in each of them. Offering it
        // in one place and not the other reads as a bug in whichever you looked at first.
        assertTrue(WindowRules.offersMenuBarToggle(false));
        assertFalse(WindowRules.offersMenuBarToggle(true));
    }

    /** OS-01 (Pauta 6.0.1): where the bar cannot be hidden the command stays and says why. */
    @Test
    void underASystemMenuBarTheToggleIsUnavailableWithAReasonTheCatalogHas() {
        assertEquals(null, WindowRules.menuBarToggleUnavailableKey(false));
        String key = WindowRules.menuBarToggleUnavailableKey(true);
        assertEquals("settings.showMenuBar.mac", key);
        assertFalse(com.termina.i18n.Messages.tr(key).equals(key), "the reason is in the catalog");
    }

    @Test
    void theZoomRowIsOnlyOfferedWhereJavaFxCanDrawIt() {
        // Not a preference. The row is a CustomMenuItem, and one of those anywhere in the bar makes
        // JavaFX decline useSystemMenuBar for all of it — which, with the in-window bar collapsed on
        // macOS, left a Mac with no menus at all.
        assertTrue(WindowRules.showsZoomRow(false));
        assertFalse(WindowRules.showsZoomRow(true));
    }

    @Test
    void theMenuBarJoinsTheTitleBarOnlyWhereAllThreeAgree() {
        assertTrue(WindowRules.usesHeaderBar(false, true, true));
        // Off is off.
        assertFalse(WindowRules.usesHeaderBar(false, false, true));
        // Under a screen menu bar there is nothing to merge; an extended window would be a bare
        // strip under the traffic lights.
        assertFalse(WindowRules.usesHeaderBar(true, true, true));
        // JavaFX downgrades an unsupported EXTENDED to DECORATED without a word, which would put
        // our header bar under the native title bar as a second row.
        assertFalse(WindowRules.usesHeaderBar(false, true, false));
    }

    @Test
    void theTitleBarOptionIsOfferedExactlyWhereItCouldDoSomething() {
        assertTrue(WindowRules.offersHeaderBarOption(false, true));
        assertFalse(WindowRules.offersHeaderBarOption(true, true));
        assertFalse(WindowRules.offersHeaderBarOption(false, false));
    }

    @Test
    void ordinaryItemsAndSeparatorsFitASystemMenuBar() {
        // SeparatorMenuItem extends CustomMenuItem, and every menu has separators, so a check that
        // did not exclude them would report every bar unfit and never collapse the in-window one.
        assertTrue(TerminalWindow.menusFitASystemMenuBar(
                List.of(new MenuItem("New Tab"), new SeparatorMenuItem(), new MenuItem("Settings…"))));
    }

    @Test
    void aCustomItemDoesNotFit() {
        assertFalse(TerminalWindow.menusFitASystemMenuBar(List.of(new MenuItem("Zoom"), new CustomMenuItem())));
    }

    @Test
    void aCustomItemInASubmenuDoesNotFitEither() {
        // JavaFX walks the whole tree, so this check has to as well: a row buried one level down
        // costs the screen menu bar just as completely as one at the top.
        Menu submenu = new Menu("More");
        submenu.getItems().add(new CustomMenuItem());
        assertFalse(TerminalWindow.menusFitASystemMenuBar(List.of(new MenuItem("Copy"), submenu)));
    }
}
