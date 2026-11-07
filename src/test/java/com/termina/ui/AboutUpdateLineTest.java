package com.termina.ui;

import com.termina.update.ReleaseInfo;
import com.termina.update.UpdateCheck;
import com.termina.update.UpdateService.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** About claims "up to date" only on an answer from this session (ABOUT-04, Pauta 2.0.2). */
class AboutUpdateLineTest {

    private static final ReleaseInfo NEWER = new ReleaseInfo("9.9.9", "https://example.invalid/r", "9.9.9");

    @Test
    void noAnswerYetIsNotCheckedNeverUpToDate() {
        assertEquals(AboutWindow.UpdateLine.NOT_CHECKED, AboutWindow.updateLine(null, null, true));
    }

    @Test
    void checksTurnedOffSaySoRatherThanUpToDate() {
        assertEquals(AboutWindow.UpdateLine.CHECKS_OFF, AboutWindow.updateLine(null, null, false));
    }

    @Test
    void aFailedCheckIsNotUpToDate() {
        Outcome failed = Outcome.failed(UpdateCheck.ErrorKind.OFFLINE, "UnknownHostException");
        assertEquals(AboutWindow.UpdateLine.CHECK_FAILED, AboutWindow.updateLine(null, failed, true));
    }

    @Test
    void anAnswerWithNothingNewerIsUpToDate() {
        assertEquals(AboutWindow.UpdateLine.UP_TO_DATE, AboutWindow.updateLine(null, Outcome.none(), true));
        // Checks off now, but one answered earlier this session: still a true statement.
        assertEquals(AboutWindow.UpdateLine.UP_TO_DATE, AboutWindow.updateLine(null, Outcome.none(), false));
    }

    @Test
    void aKnownReleaseWinsOverEverything() {
        assertEquals(AboutWindow.UpdateLine.AVAILABLE, AboutWindow.updateLine(NEWER, null, false));
        assertEquals(AboutWindow.UpdateLine.AVAILABLE, AboutWindow.updateLine(NEWER, Outcome.found(NEWER), true));
    }
}
