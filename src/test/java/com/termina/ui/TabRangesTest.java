package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TabRangesTest {

    @Test
    void closeOthersNeedsASecondTab() {
        assertEquals("palette.disabled.onlyTab", TabRanges.unavailable(TabRanges.CLOSE_OTHERS, 0, 1));
        assertNull(TabRanges.unavailable(TabRanges.CLOSE_OTHERS, 0, 2));
    }

    @Test
    void closeLeftNeedsATabToTheLeft() {
        assertEquals("palette.disabled.noTabsLeft", TabRanges.unavailable(TabRanges.CLOSE_LEFT, 0, 3));
        assertNull(TabRanges.unavailable(TabRanges.CLOSE_LEFT, 1, 3));
    }

    @Test
    void closeRightNeedsATabToTheRight() {
        assertEquals("palette.disabled.noTabsRight", TabRanges.unavailable(TabRanges.CLOSE_RIGHT, 2, 3));
        assertNull(TabRanges.unavailable(TabRanges.CLOSE_RIGHT, 1, 3));
    }

    @Test
    void noSelectedTabClosesNothing() {
        for (String id : new String[] {TabRanges.CLOSE_OTHERS, TabRanges.CLOSE_LEFT, TabRanges.CLOSE_RIGHT}) {
            assertEquals("palette.disabled.noTab", TabRanges.unavailable(id, -1, 3));
        }
    }

    @Test
    void theIdsAreTheCommandsIds() {
        assertEquals(TabRanges.CLOSE_OTHERS, TerminalWindow.idOf("menu.closeOtherTabs"));
        assertEquals(TabRanges.CLOSE_LEFT, TerminalWindow.idOf("menu.closeTabsToTheLeft"));
        assertEquals(TabRanges.CLOSE_RIGHT, TerminalWindow.idOf("menu.closeTabsToTheRight"));
    }
}
