package com.termina.ui;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The debug log's formatting and bounds. */
class DebugLogTest {

    @Test
    void aLoggerNameIsShortenedToItsLastSegment() {
        assertEquals("TerminalSession", DebugLog.shortName("com.termina.term.TerminalSession"));
        assertEquals("bare", DebugLog.shortName("bare"));
        assertEquals("?", DebugLog.shortName(null));
        assertEquals("?", DebugLog.shortName("  "));
        // A trailing dot leaves nothing after it; the name itself is more use than an empty string.
        assertEquals("a.b.", DebugLog.shortName("a.b."));
    }

    @Test
    void aRecordBecomesOneLineWithItsLevelAndSource() {
        LogRecord record = new LogRecord(Level.WARNING, "could not start a shell");
        record.setLoggerName("com.termina.pty.ShellLauncher");
        String line = DebugLog.format(record);
        assertTrue(line.contains("WARNING"), line);
        assertTrue(line.contains("ShellLauncher"), line);
        assertTrue(line.contains("could not start a shell"), line);
    }

    @Test
    void aThrownExceptionKeepsItsStackTrace() {
        // The whole reason to have this: the message alone rarely says where it came from.
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("x.Y");
        record.setThrown(new IllegalStateException("the cause"));
        String line = DebugLog.format(record);
        assertTrue(line.contains("IllegalStateException"), line);
        assertTrue(line.contains("the cause"), line);
    }

    @Test
    void aSecondInstanceDoesNotTruncateTheFirstOnesLog(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        // A dev run without TERMINA_CONFIG_DIR wiped the packaged app's log — the one with the
        // crash in it. The first instance here is a lock held by the test; in the same JVM the
        // second tryLock throws OverlappingFileLockException, which is treated as "held".
        java.nio.file.Path shared = dir.resolve("termina-session.log");
        java.nio.file.Files.writeString(shared, "the first instance's story\n");
        try (java.nio.channels.FileChannel first =
                        java.nio.channels.FileChannel.open(shared, java.nio.file.StandardOpenOption.WRITE);
                java.nio.channels.FileLock held = first.lock()) {
            DebugLog.attachFile(dir, 4242);
            assertEquals(dir.resolve(DebugLog.siblingName(4242)), DebugLog.file(), "falls back to a per-pid sibling");
            DebugLog.append("from the second instance");
        } finally {
            DebugLog.detachFile();
            DebugLog.clear();
        }
        assertEquals("the first instance's story\n", java.nio.file.Files.readString(shared), "left untouched");
        assertTrue(java.nio.file.Files.readString(dir.resolve(DebugLog.siblingName(4242)))
                .contains("from the second instance"));
    }

    @Test
    void theOnlyInstanceTakesTheSharedFileAndTruncatesIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path shared = dir.resolve("termina-session.log");
        java.nio.file.Files.writeString(shared, "last run\n");
        try {
            DebugLog.attachFile(dir, 1);
            assertEquals(shared, DebugLog.file());
            DebugLog.append("this run");
        } finally {
            DebugLog.detachFile();
            DebugLog.clear();
        }
        String text = java.nio.file.Files.readString(shared);
        assertFalse(text.contains("last run"), text);
        assertTrue(text.contains("this run"), text);
    }

    @Test
    void theBufferIsBounded() {
        DebugLog.clear();
        for (int i = 0; i < 2500; i++) DebugLog.append("line " + i);
        String text = DebugLog.text();
        // Oldest dropped, newest kept — a log that pushed out the crash to keep the startup chatter
        // would be worse than none.
        assertFalse(text.contains("line 0\n"), "oldest should have been dropped");
        assertTrue(text.contains("line 2499"), "newest should be kept");
        DebugLog.clear();
        assertEquals("", DebugLog.text());
    }
}
