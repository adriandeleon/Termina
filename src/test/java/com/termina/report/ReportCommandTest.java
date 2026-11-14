package com.termina.report;

import java.nio.file.Files;
import java.nio.file.Path;

import com.termina.ui.DebugLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void theReportGoesInTheGivenFolderAndItsPathIsPrinted() throws Exception {
        // CLI-05 (Pauta 6.0.1): a file in the current folder, its path printed, the issue handed on.
        Path config = Files.createDirectory(dir.resolve("config"));
        Path cwd = Files.createDirectory(dir.resolve("cwd"));
        Files.writeString(config.resolve(DebugLog.FILE_NAME), "it crashed\n");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.List<String> opened = new java.util.ArrayList<>();
        int status = ReportCommand.run(
                config.resolve("settings.json"),
                cwd,
                new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8),
                url -> opened.add(url));
        assertEquals(0, status);
        assertEquals(1, opened.size());
        java.util.List<Path> written;
        try (var files = Files.list(cwd)) {
            written = files.toList();
        }
        assertEquals(1, written.size(), "one report file in the folder");
        Path file = written.get(0);
        assertTrue(file.getFileName().toString().matches("termina-report-\\d{8}-\\d{6}\\.txt"), file.toString());
        String report = Files.readString(file);
        assertTrue(report.contains("it crashed"), report);
        String printed = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(printed.contains("Wrote the problem report to " + file.toAbsolutePath()), printed);
        assertTrue(printed.contains("Nothing has been sent"), printed);
    }

    @Test
    void aFolderThatCannotBeWrittenSendsTheReportToTheNext() throws Exception {
        // A regular file where the folder should be: no file can be created under it, on any OS.
        Path notAFolder = Files.writeString(dir.resolve("blocked"), "");
        Path fallback = Files.createDirectory(dir.resolve("temp"));
        Path file = ReportCommand.write("the report", java.util.List.of(notAFolder, fallback), "r.txt");
        assertEquals(fallback.toAbsolutePath().resolve("r.txt"), file);
        assertEquals("the report", Files.readString(fallback.resolve("r.txt")));
        org.junit.jupiter.api.Assertions.assertNull(
                ReportCommand.write("the report", java.util.List.of(notAFolder), "r.txt"));
    }

    @Test
    void theFileNameCarriesTheTime() {
        assertEquals(
                "termina-report-20261112-090503.txt",
                ReportCommand.fileName(java.time.LocalDateTime.of(2026, 11, 12, 9, 5, 3)));
    }
}
