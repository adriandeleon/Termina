package com.termina.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The window's own rules: what it remembers of its size, when it stays open empty, which chords it lists and runs. */
class WindowRulesTest {

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
    }

    private static <T> T onFx(Supplier<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    @Test
    void onlyAnOrdinaryWindowsGeometryIsRemembered() {
        assertTrue(TerminalWindow.isRestoredGeometry(false, false));
        assertFalse(TerminalWindow.isRestoredGeometry(true, false));
        // The one that was missed: closing a full-screen window saved the screen as its size.
        assertFalse(TerminalWindow.isRestoredGeometry(false, true));
        assertFalse(TerminalWindow.isRestoredGeometry(true, true));
    }

    @Test
    void aWindowWhoseLastShellWillNotStartStaysOpen() {
        // Closing took the error dialog with it and, for the last window, the application.
        assertTrue(TerminalWindow.keepsWindowOpenEmpty(0, false));
        // The default shell is being tried instead: that tab is what the window holds.
        assertFalse(TerminalWindow.keepsWindowOpenEmpty(0, true));
        // Other tabs are left; the failed one simply goes.
        assertFalse(TerminalWindow.keepsWindowOpenEmpty(2, false));
    }

    @Test
    void theReferenceListsProfileChordsOnlyForProfilesThatExist() {
        assertTrue(TerminalWindow.listsProfileChord("newTabProfile1", 1));
        assertFalse(TerminalWindow.listsProfileChord("newTabProfile2", 1));
        assertTrue(TerminalWindow.listsProfileChord("newTabProfile9", 9));
        // Everything that is not a profile chord is listed as before.
        assertTrue(TerminalWindow.listsProfileChord("newTab", 0));
        assertTrue(TerminalWindow.listsProfileChord("goToTab9", 0));
        assertTrue(TerminalWindow.listsProfileChord(null, 0));
    }

    /** A scene with a menu item on Ctrl+Shift+W and a text field holding the keyboard. */
    private record Rig(Scene scene, TextField field, MenuItem item, int[] fired) {}

    private static Rig rig() {
        int[] fired = {0};
        MenuItem item = new MenuItem("Close Tab");
        item.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        item.setOnAction(e -> fired[0]++);
        MenuBar bar = new MenuBar(new Menu("File", null, item));
        TextField field = new TextField();
        Scene scene = new Scene(new VBox(bar, field), 400, 300);
        // The skin is what copies the accelerators into the scene.
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return new Rig(scene, field, item, fired);
    }

    private static void pressCtrlShiftW(Rig rig) {
        Event.fireEvent(rig.field(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.W, true, true, false, false));
    }

    @Test
    void anUnconsumedChordRunsAMenuAcceleratorUnlessTheSceneKeepsThemDisplayOnly() throws Exception {
        // The control: this is the route the palette leaked Ctrl+Shift+W through.
        assertEquals(1, onFx(() -> {
            Rig rig = rig();
            pressCtrlShiftW(rig);
            return rig.fired()[0];
        }));
        assertEquals(0, onFx(() -> {
            Rig rig = rig();
            TerminalWindow.keepAcceleratorsDisplayOnly(rig.scene());
            pressCtrlShiftW(rig);
            return rig.fired()[0];
        }));
    }

    @Test
    void aDisplayOnlyAcceleratorIsStillShownAndStaysOutAsItemsChange() throws Exception {
        assertTrue(onFx(() -> {
            Rig rig = rig();
            TerminalWindow.keepAcceleratorsDisplayOnly(rig.scene());
            // A rebind sets the accelerator again, which JavaFX copies into the scene again.
            rig.item().setAccelerator(new KeyCodeCombination(KeyCode.Y, KeyCombination.CONTROL_DOWN));
            return rig.item().getAccelerator() != null
                    && rig.scene().getAccelerators().isEmpty();
        }));
    }
}
