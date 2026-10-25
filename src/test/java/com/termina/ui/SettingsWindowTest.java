package com.termina.ui;

import com.termina.i18n.Messages;
import com.termina.pty.ShellLauncher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions in the settings window that do not need a window. */
class SettingsWindowTest {

    // ---------------------------------------------------------------- search

    @Test
    void aTitleHitOutranksADescriptionHit() {
        // "scrollback" is in the description of "Show a scrollbar" and the title of "Scrollback
        // lines"; ranked by page order the search landed on the wrong one.
        int scrollbar = SettingsWindow.matchRank("scrollback", "Show a scrollbar", "The wheel scrolls the scrollback");
        int lines = SettingsWindow.matchRank("scrollback", "Scrollback lines", "Held in memory");
        assertTrue(lines > scrollbar);
        assertEquals(0, SettingsWindow.matchRank("scrollback", "Visual bell", "Flashes the screen"));
        assertEquals(1, SettingsWindow.matchRank("  ", "anything", "at all"), "a blank query keeps everything");
    }

    @Test
    void theBestMatchIsTheHighestRankAndTheFirstOnATie() {
        assertEquals(2, SettingsWindow.bestOf(new int[] {1, 0, 2, 2}));
        assertEquals(0, SettingsWindow.bestOf(new int[] {1, 1}));
        assertEquals(-1, SettingsWindow.bestOf(new int[] {0, 0}));
        assertEquals(-1, SettingsWindow.bestOf(new int[0]));
    }

    // ---------------------------------------------------------------- sidebar keys

    @Test
    void arrowKeysSkipTheGroupHeaders() {
        // Items: header, Appearance, Terminal, Profiles, header, Advanced.
        java.util.function.IntPredicate selectable = i -> i != 0 && i != 4;
        assertEquals(
                1, SettingsWindow.nextSelectable(-1, 1, 6, selectable), "Down from nothing lands on the first page");
        assertEquals(
                5, SettingsWindow.nextSelectable(3, 1, 6, selectable), "Down from Profiles jumps the SYSTEM header");
        assertEquals(3, SettingsWindow.nextSelectable(5, -1, 6, selectable), "and Up jumps back over it");
        assertEquals(-1, SettingsWindow.nextSelectable(5, 1, 6, selectable), "nothing below the last page");
        assertEquals(
                -1, SettingsWindow.nextSelectable(1, -1, 6, selectable), "the header above the first is not a stop");
    }

    // ---------------------------------------------------------------- spinners

    @Test
    void aNumberFieldReadsWhatPeopleType() {
        // The default converter threw on all of these, uncaught, on the FX thread.
        assertEquals(13.0, SettingsWindow.lenientNumber("13px"));
        assertEquals(13.5, SettingsWindow.lenientNumber("13,5"));
        assertEquals(14.0, SettingsWindow.lenientNumber(" 14 "));
        assertEquals(-2.0, SettingsWindow.lenientNumber("-2"));
        assertNull(SettingsWindow.lenientNumber("large"));
        assertNull(SettingsWindow.lenientNumber(""));
        assertNull(SettingsWindow.lenientNumber(null));
    }

    // ---------------------------------------------------------------- live reload

    @Test
    void theWindowReloadsOnlyWhileShownAndNeverFromInsideAReload() {
        assertTrue(SettingsWindow.shouldReload(true, false));
        assertFalse(SettingsWindow.shouldReload(false, false), "nothing to refresh when hidden; show() reloads anyway");
        assertFalse(SettingsWindow.shouldReload(true, true), "the re-entrancy guard");
    }

    // ---------------------------------------------------------------- validation

    @Test
    void onlyAProblemGetsALineUnderTheField() {
        assertNull(SettingsWindow.validationKey(ShellLauncher.PathStatus.OK));
        assertNull(
                SettingsWindow.validationKey(ShellLauncher.PathStatus.EMPTY),
                "the prompt text already says what empty means");
        assertEquals("validation.notFound", SettingsWindow.validationKey(ShellLauncher.PathStatus.NOT_FOUND));
        assertEquals("validation.notExecutable", SettingsWindow.validationKey(ShellLauncher.PathStatus.NOT_EXECUTABLE));
        assertEquals(
                "validation.notADirectory", SettingsWindow.validationKey(ShellLauncher.PathStatus.NOT_A_DIRECTORY));
    }

    // ---------------------------------------------------------------- update errors

    @Test
    void aFailedUpdateCheckIsDescribedInWords() {
        Messages.init("en");
        assertEquals(
                "You appear to be offline.",
                WindowManager.describeFailure(
                        com.termina.update.UpdateCheck.ErrorKind.OFFLINE, "UnknownHostException"));
        String refused = WindowManager.describeFailure(com.termina.update.UpdateCheck.ErrorKind.HTTP, "403");
        assertTrue(refused.contains("403"), refused);
        assertFalse(refused.contains("Exception"), refused);
    }

    @Test
    void aboutShowsWhenTheLastCheckRanOnlyOnceThereHasBeenOne() {
        assertNull(AboutWindow.lastChecked(0));
        assertNull(AboutWindow.lastChecked(-5));
        String when = AboutWindow.lastChecked(1_700_000_000_000L);
        assertTrue(when != null && !when.isBlank());
    }

    @Test
    void aQueryThatNamesAPageLandsOnIt() {
        // The Keyboard page's "Sync Settings Now" is a title hit; the Sync page's rows are not.
        int keyboardRow = SettingsWindow.landingRank(2, "sync", "Keyboard");
        int syncRow = SettingsWindow.landingRank(1, "sync", "Sync");
        assertEquals(1, SettingsWindow.bestOf(new int[] {keyboardRow, syncRow}));
        assertEquals(0, SettingsWindow.landingRank(0, "sync", "Sync"), "a row that did not match stays hidden");
        assertEquals(2, SettingsWindow.landingRank(2, "", "Sync"));
    }
}
