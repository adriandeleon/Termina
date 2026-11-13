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
        assertFalse(WindowRules.shouldShowTabBar(1, true));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 12})
    void moreThanOneTabAlwaysShowsTheStrip(int tabs) {
        assertTrue(WindowRules.shouldShowTabBar(tabs, true));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5})
    void withTheSettingOffTheStripIsAlwaysShown(int tabs) {
        assertTrue(WindowRules.shouldShowTabBar(tabs, false));
    }

    @Test
    void anEmptyWindowIsTreatedLikeALoneTab() {
        // Reachable for one pulse while the last tab is being removed, before the window closes.
        // Showing an empty strip there would flash a bare row on the way out.
        assertFalse(WindowRules.shouldShowTabBar(0, true));
    }

    @org.junit.jupiter.api.Test
    void hiddenByItsCommandItStaysHiddenWithAnyNumberOfTabs() {
        // TABS-12 (Pauta 6.0.1): a command hides the tab bar, not only while there is one tab.
        for (int tabs = 1; tabs <= 12; tabs++) {
            assertFalse(WindowRules.shouldShowTabBar(tabs, false, false), tabs + " tabs");
            assertFalse(WindowRules.shouldShowTabBar(tabs, true, false), tabs + " tabs");
        }
        // Shown, the older rule decides as it did.
        assertTrue(WindowRules.shouldShowTabBar(3, true, true));
        assertFalse(WindowRules.shouldShowTabBar(1, true, true));
        assertTrue(WindowRules.shouldShowTabBar(1, false, true));
    }
}
