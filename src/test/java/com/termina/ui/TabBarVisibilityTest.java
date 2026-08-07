package com.termina.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the tab strip earns its row of chrome. */
class TabBarVisibilityTest {

    @Test
    void aLoneTabDoesNotNeedAStrip() {
        assertFalse(TerminalWindow.shouldShowTabBar(1, true));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 12})
    void moreThanOneTabAlwaysShowsTheStrip(int tabs) {
        assertTrue(TerminalWindow.shouldShowTabBar(tabs, true));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5})
    void withTheSettingOffTheStripIsAlwaysShown(int tabs) {
        assertTrue(TerminalWindow.shouldShowTabBar(tabs, false));
    }

    @Test
    void anEmptyWindowIsTreatedLikeALoneTab() {
        // Reachable for one pulse while the last tab is being removed, before the window closes.
        // Showing an empty strip there would flash a bare row on the way out.
        assertFalse(TerminalWindow.shouldShowTabBar(0, true));
    }
}
