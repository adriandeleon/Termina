package com.termina.report;

import java.nio.file.Files;
import java.nio.file.Path;

import com.termina.ui.DebugLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@code termina --report} reports from: the last session's log, or nothing when there is none. */
class ReportCommandTest {

    @TempDir
    Path dir;

    @Test
    void theLastSessionsLogOrNothing() throws Exception {
        assertEquals("", ReportCommand.lastSessionLog(dir));
        Files.writeString(dir.resolve(DebugLog.FILE_NAME), "it crashed\n");
        assertEquals("it crashed\n", ReportCommand.lastSessionLog(dir));
    }

    @Test
    void theReportIsPrintedAndItsAddressHandedOnNotOpenedHere() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.List<String> opened = new java.util.ArrayList<>();
        int status = ReportCommand.run(
                dir.resolve("settings.json"),
                new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8),
                url -> opened.add(url));
        assertEquals(0, status);
        assertEquals(1, opened.size());
        org.junit.jupiter.api.Assertions.assertTrue(
                out.toString(java.nio.charset.StandardCharsets.UTF_8).contains("Nothing has been sent"));
    }
}
