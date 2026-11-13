package com.termina.report;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DIAG-06 (Pauta 2.0.2): what the report carries, and how it reaches the issue form. */
class ProblemReportTest {

    private static final Redactor REDACT = new Redactor("/home/jdoe", "jdoe");

    @Test
    void itCarriesTheEnvironmentTheUsefulSettingsAndTheEndOfTheLog() {
        ProblemReport report = ProblemReport.build(
                List.of("Termina 0.15.0", "Java: 25"),
                Map.of(
                        "font.size", "15",
                        "terminal.shell", "/home/jdoe/bin/fish",
                        "profile.1.name", "prod",
                        "profile.1.command", "ssh deploy@prod-db",
                        "keys.newTab", "Shortcut+Shift+Y",
                        "sync.repository", "https://bob:tok@github.com/b/s.git"),
                "WARNING one\nSEVERE two",
                REDACT);
        assertEquals("Termina 0.15.0\nJava: 25", report.environment());
        String body = report.report();
        assertTrue(body.contains("font.size = 15"), body);
        assertTrue(body.contains("terminal.shell = ~/bin/fish"), body);
        assertTrue(body.contains("key bindings changed: 1, user profiles: 1"), body);
        assertTrue(body.contains("SEVERE two"), body);
        assertFalse(body.contains("prod-db"), "a profile's command is not in a report");
        assertFalse(body.contains("github.com/b/s"), "nor the sync address");
    }

    @Test
    void onlyTheEndOfALongLogGoesIn() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 500; i++) log.append("line ").append(i).append('\n');
        String body =
                ProblemReport.build(List.of(), Map.of(), log.toString(), REDACT).report();
        assertTrue(body.contains("line 499"));
        assertFalse(body.contains("line 299\n"));
        assertTrue(body.contains("last " + ProblemReport.LOG_LINES + " lines"));
    }

    @Test
    void aShortReportRidesInTheLink() {
        ProblemReport report = new ProblemReport("Termina 1.0", "it broke");
        ProblemReport.Link link = report.link("adriandeleon/Termina", "paste it here");
        assertFalse(link.pasteReport());
        assertFalse(link.url().contains("paste%20it%20here"), link.url());
        assertTrue(link.url().startsWith("https://github.com/adriandeleon/Termina/issues/new?template=bug_report.yml"));
        assertTrue(link.url().contains("environment=Termina%201.0"), link.url());
        assertTrue(link.url().contains("report=it%20broke"), link.url());
    }

    @Test
    void aLongReportFallsBackToTheClipboard() {
        ProblemReport report = new ProblemReport("Termina 1.0", "x".repeat(ProblemReport.MAX_LINK_LENGTH));
        ProblemReport.Link link = report.link("adriandeleon/Termina", "paste it here");
        assertTrue(link.pasteReport());
        // DIAG-13 (Pauta 6.0.1): the form's report field asks for the paste; the report stays out.
        assertTrue(link.url().endsWith("&report=paste%20it%20here"), link.url());
        assertFalse(link.url().contains("xxxx"));
        assertTrue(link.url().length() <= ProblemReport.MAX_LINK_LENGTH);
    }

    @Test
    void anEnvironmentPastTheLimitLeavesNoRoomForTheHint() {
        ProblemReport report = new ProblemReport("e".repeat(ProblemReport.MAX_LINK_LENGTH), "it broke");
        ProblemReport.Link link = report.link("adriandeleon/Termina", "paste it here");
        assertTrue(link.pasteReport());
        assertFalse(link.url().contains("report="));
    }
}
