package com.termina.ui;

import javafx.scene.input.KeyCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which keys the view keeps, and which reach the shell. */
class ViewKeysTest {

    private static ViewKeys.Action of(KeyCode code, boolean shift, boolean control, boolean alternate) {
        return ViewKeys.of(code, shift, control, false, false, alternate);
    }

    @ParameterizedTest(name = "Shift+{0} is {1}")
    @CsvSource({
        "PAGE_UP, PAGE_UP",
        "PAGE_DOWN, PAGE_DOWN",
        "HOME, TOP",
        "END, BOTTOM",
    })
    void shiftedNavigationKeysScrollTheScrollback(KeyCode code, ViewKeys.Action expected) {
        assertEquals(expected, of(code, true, false, false));
    }

    @Test
    void unshiftedNavigationKeysBelongToTheShell() {
        // Plain PageUp is less's, and Home is readline's.
        assertEquals(ViewKeys.Action.NONE, of(KeyCode.PAGE_UP, false, false, false));
        assertEquals(ViewKeys.Action.NONE, of(KeyCode.HOME, false, false, false));
    }

    @Test
    void onTheAlternateScreenTheScrollingKeysPassThrough() {
        // There is no scrollback behind a full-screen program, and less binds Shift+PageUp itself.
        assertEquals(ViewKeys.Action.NONE, of(KeyCode.PAGE_UP, true, false, true));
        assertEquals(ViewKeys.Action.NONE, of(KeyCode.END, true, false, true));
    }

    @Test
    void insertPastesWithShiftAndCopiesWithControl() {
        assertEquals(ViewKeys.Action.PASTE, of(KeyCode.INSERT, true, false, false));
        assertEquals(ViewKeys.Action.COPY, of(KeyCode.INSERT, false, true, false));
        // Both work inside a full-screen program: neither is contested there.
        assertEquals(ViewKeys.Action.PASTE, of(KeyCode.INSERT, true, false, true));
        assertEquals(ViewKeys.Action.COPY, of(KeyCode.INSERT, false, true, true));
    }

    @Test
    void plainInsertAndOddCombinationsAreTheShells() {
        assertEquals(ViewKeys.Action.NONE, of(KeyCode.INSERT, false, false, false));
        assertEquals(ViewKeys.Action.NONE, of(KeyCode.INSERT, true, true, false));
        assertEquals(ViewKeys.Action.NONE, ViewKeys.of(KeyCode.INSERT, true, false, true, false, false));
        assertEquals(ViewKeys.Action.NONE, ViewKeys.of(KeyCode.PAGE_UP, true, false, false, true, false));
    }

    @Test
    void ctrlShiftedNavigationIsNotOurs() {
        assertEquals(ViewKeys.Action.NONE, of(KeyCode.PAGE_UP, true, true, false));
    }

    @Test
    void aPageIsTheScreenHeightAndTheEndsAreClamped() {
        assertEquals(-24, ViewKeys.scrolledOrigin(ViewKeys.Action.PAGE_UP, 0, 24, 100));
        assertEquals(-100, ViewKeys.scrolledOrigin(ViewKeys.Action.PAGE_UP, -90, 24, 100));
        assertEquals(-6, ViewKeys.scrolledOrigin(ViewKeys.Action.PAGE_DOWN, -30, 24, 100));
        assertEquals(0, ViewKeys.scrolledOrigin(ViewKeys.Action.PAGE_DOWN, -10, 24, 100));
        assertEquals(-100, ViewKeys.scrolledOrigin(ViewKeys.Action.TOP, 0, 24, 100));
        assertEquals(0, ViewKeys.scrolledOrigin(ViewKeys.Action.BOTTOM, -57, 24, 100));
    }

    @Test
    void withNoHistoryNothingScrolls() {
        assertEquals(0, ViewKeys.scrolledOrigin(ViewKeys.Action.PAGE_UP, 0, 24, 0));
        assertEquals(0, ViewKeys.scrolledOrigin(ViewKeys.Action.TOP, 0, 24, 0));
    }
}
