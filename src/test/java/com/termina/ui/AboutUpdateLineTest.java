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
    void eachLineHasTheColorItsMeaningAsksFor() throws Exception {
        // ABOUT-10 (Pauta 6.0.1): up to date in green, a failed check in red, the rest muted; an
        // available update bold in amber, which no other About style uses, while links keep the accent.
        assertEquals("about-uptodate", AboutWindow.updateLineStyle(AboutWindow.UpdateLine.UP_TO_DATE));
        assertEquals("about-checkfailed", AboutWindow.updateLineStyle(AboutWindow.UpdateLine.CHECK_FAILED));
        assertEquals("about-details", AboutWindow.updateLineStyle(AboutWindow.UpdateLine.NOT_CHECKED));
        assertEquals("about-details", AboutWindow.updateLineStyle(AboutWindow.UpdateLine.CHECKS_OFF));
        String css =
                java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/com/termina/styles/app.css"));
        java.util.Map<String, String> rules = new java.util.HashMap<>();
        java.util.regex.Matcher rule = java.util.regex.Pattern.compile("(?m)^(\\.about-[a-z-]+)[^{]*\\{([^}]*)\\}")
                .matcher(css);
        while (rule.find()) rules.merge(rule.group(1), rule.group(2), String::concat);
        org.junit.jupiter.api.Assertions.assertTrue(
                rules.get(".about-update-headline").contains("-state-amber"));
        org.junit.jupiter.api.Assertions.assertTrue(
                rules.get(".about-update-headline").contains("bold"));
        org.junit.jupiter.api.Assertions.assertTrue(rules.get(".about-uptodate").contains("-state-green"));
        org.junit.jupiter.api.Assertions.assertTrue(
                rules.get(".about-checkfailed").contains("-state-red"));
        org.junit.jupiter.api.Assertions.assertTrue(
                rules.get(".about-lastchecked").contains("-color-fg-muted"));
        rules.forEach((selector, body) -> {
            if (!selector.equals(".about-update-headline")) {
                org.junit.jupiter.api.Assertions.assertFalse(
                        body.contains("-state-amber"), selector + " uses the amber");
            }
        });
    }

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
