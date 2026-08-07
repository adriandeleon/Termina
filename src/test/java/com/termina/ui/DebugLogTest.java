package com.termina.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

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
