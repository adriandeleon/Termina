package com.termina.ui;

import java.util.List;

import com.termina.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Where another launch's command line opens (CLI-07). */
class WindowManagerTest {

    @Test
    void aTabGoesToTheWindowUsedLast() {
        assertEquals("b", WindowManager.tabHost(Settings.OtherLaunches.TAB, List.of("a", "b", "c"), "b"));
    }

    /** Before any window has had the keyboard, or after the one that had it closed. */
    @Test
    void withNoWindowUsedLastATabGoesToTheNewest() {
        assertEquals("c", WindowManager.tabHost(Settings.OtherLaunches.TAB, List.of("a", "b", "c"), null));
        assertEquals("c", WindowManager.tabHost(Settings.OtherLaunches.TAB, List.of("a", "b", "c"), "gone"));
    }

    @Test
    void withNoWindowAtAllATabBecomesAWindow() {
        assertNull(WindowManager.tabHost(Settings.OtherLaunches.TAB, List.<String>of(), null));
    }

    @Test
    void theOtherSettingsNeverPutItInATab() {
        assertNull(WindowManager.tabHost(Settings.OtherLaunches.WINDOW, List.of("a"), "a"));
        // Declined before it gets here; were one to arrive anyway, it is a window, not a lost launch.
        assertNull(WindowManager.tabHost(Settings.OtherLaunches.OWN_PROCESS, List.of("a"), "a"));
    }
}
