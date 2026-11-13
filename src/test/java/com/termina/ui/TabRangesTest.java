package com.termina.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TabRangesTest {

    private static List<Boolean> none(int count) {
        return java.util.Collections.nCopies(count, false);
    }

    @Test
    void closeOthersNeedsASecondTab() {
        assertEquals("palette.disabled.onlyTab", TabRanges.unavailable(TabRanges.CLOSE_OTHERS, 0, none(1)));
        assertNull(TabRanges.unavailable(TabRanges.CLOSE_OTHERS, 0, none(2)));
        assertEquals(List.of(0, 2), TabRanges.closing(TabRanges.CLOSE_OTHERS, 1, none(3)));
    }

    @Test
    void closeLeftNeedsATabToTheLeft() {
        assertEquals("palette.disabled.noTabsLeft", TabRanges.unavailable(TabRanges.CLOSE_LEFT, 0, none(3)));
        assertNull(TabRanges.unavailable(TabRanges.CLOSE_LEFT, 1, none(3)));
        assertEquals(List.of(0, 1), TabRanges.closing(TabRanges.CLOSE_LEFT, 2, none(3)));
    }

    @Test
    void closeRightNeedsATabToTheRight() {
        assertEquals("palette.disabled.noTabsRight", TabRanges.unavailable(TabRanges.CLOSE_RIGHT, 2, none(3)));
        assertNull(TabRanges.unavailable(TabRanges.CLOSE_RIGHT, 1, none(3)));
        assertEquals(List.of(2), TabRanges.closing(TabRanges.CLOSE_RIGHT, 1, none(3)));
    }

    @Test
    void noSelectedTabClosesNothing() {
        for (String id : new String[] {TabRanges.CLOSE_OTHERS, TabRanges.CLOSE_LEFT, TabRanges.CLOSE_RIGHT}) {
            assertEquals("palette.disabled.noTab", TabRanges.unavailable(id, -1, none(3)));
            assertEquals(List.of(), TabRanges.closing(id, -1, none(3)));
        }
    }

    @Test
    void aBulkCloseSparesPinnedTabs() {
        List<Boolean> pinned = List.of(true, false, true, false);
        assertEquals(List.of(3), TabRanges.closing(TabRanges.CLOSE_OTHERS, 1, pinned));
        assertEquals(List.of(1), TabRanges.closing(TabRanges.CLOSE_LEFT, 2, pinned));
        assertEquals(List.of(1, 2), TabRanges.closing(TabRanges.CLOSE_RIGHT, 0, List.of(true, false, false)));
    }

    @Test
    void aCloseThatWouldOnlyReachPinnedTabsSaysSo() {
        assertEquals(
                "palette.disabled.allPinned",
                TabRanges.unavailable(TabRanges.CLOSE_LEFT, 2, List.of(true, true, false)));
        assertEquals(
                "palette.disabled.allPinned", TabRanges.unavailable(TabRanges.CLOSE_OTHERS, 1, List.of(true, false)));
    }

    @Test
    void theIdsAreTheCommandsIds() {
        assertEquals(TabRanges.CLOSE_OTHERS, TerminalWindow.idOf("command.file.closeOtherTabs"));
        assertEquals(TabRanges.CLOSE_LEFT, TerminalWindow.idOf("command.file.closeTabsToTheLeft"));
        assertEquals(TabRanges.CLOSE_RIGHT, TerminalWindow.idOf("command.file.closeTabsToTheRight"));
    }

    /** KBD-04: a tab at the edge cannot move past it, and the menu and the palette say so. */
    @Test
    void aTabAtAnEdgeCannotMovePastIt() {
        assertEquals("palette.disabled.firstTab", TabRanges.moveUnavailable(-1, 0, 3));
        assertEquals("palette.disabled.lastTab", TabRanges.moveUnavailable(1, 2, 3));
        assertNull(TabRanges.moveUnavailable(1, 0, 3));
        assertNull(TabRanges.moveUnavailable(-1, 2, 3));
        assertNull(TabRanges.moveUnavailable(-1, 1, 3));
        // The only tab is both the first and the last.
        assertEquals("palette.disabled.firstTab", TabRanges.moveUnavailable(-1, 0, 1));
        assertEquals("palette.disabled.lastTab", TabRanges.moveUnavailable(1, 0, 1));
        assertEquals("palette.disabled.noTab", TabRanges.moveUnavailable(1, -1, 3));
        assertEquals("palette.disabled.noTab", TabRanges.moveUnavailable(-1, 3, 3));
    }
}
