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

    // ---------------------------------------------------------------- sidebar keys

    @Test
    void arrowKeysSkipTheGroupHeaders() {
        // Items: header, Appearance, Terminal, Profiles, header, Advanced.
        java.util.function.IntPredicate selectable = i -> i != 0 && i != 4;
        assertEquals(1, WindowRules.nextSelectable(-1, 1, 6, selectable), "Down from nothing lands on the first page");
        assertEquals(5, WindowRules.nextSelectable(3, 1, 6, selectable), "Down from Profiles jumps the SYSTEM header");
        assertEquals(3, WindowRules.nextSelectable(5, -1, 6, selectable), "and Up jumps back over it");
        assertEquals(-1, WindowRules.nextSelectable(5, 1, 6, selectable), "nothing below the last page");
        assertEquals(-1, WindowRules.nextSelectable(1, -1, 6, selectable), "the header above the first is not a stop");
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
        assertTrue(WindowRules.shouldReload(true, false, false));
        assertFalse(
                WindowRules.shouldReload(false, false, false), "nothing to refresh when hidden; show() reloads anyway");
        assertFalse(WindowRules.shouldReload(true, true, false), "the re-entrancy guard");
    }

    @Test
    void theWindowDoesNotReloadForAChangeItIsMakingItself() {
        // A spinner tick re-set thirty controls, resolved every key binding and rebuilt the profile
        // list and the preview, for a value the spinner was already showing.
        assertFalse(WindowRules.shouldReload(true, false, true));
        assertTrue(WindowRules.shouldReload(true, false, false), "a change from elsewhere still reloads");
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
}
