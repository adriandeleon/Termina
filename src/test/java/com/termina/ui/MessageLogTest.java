package com.termina.ui;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** DIAG-01 and MLOG-01..07 (Pauta 2.0.2): newest first, bounded, cleared, unread errors kept. */
class MessageLogTest {

    private static MessageLog.Entry at(int second, MessageLog.Severity severity, String text) {
        return new MessageLog.Entry(LocalTime.of(14, 3, second), severity, text);
    }

    @Test
    void newestComesFirst() {
        MessageLog log = new MessageLog();
        log.add(at(1, MessageLog.Severity.INFO, "first"));
        log.add(at(2, MessageLog.Severity.INFO, "second"));
        assertEquals(
                List.of("second", "first"),
                log.entries().stream().map(MessageLog.Entry::text).toList());
        assertEquals("second", log.latest().text());
    }

    @Test
    void pastItsCapTheOldestGo() {
        MessageLog log = new MessageLog(3);
        for (int i = 0; i < 5; i++) log.add(at(i, MessageLog.Severity.INFO, "m" + i));
        assertEquals(
                List.of("m4", "m3", "m2"),
                log.entries().stream().map(MessageLog.Entry::text).toList());
        // PERF-11: what was cut is counted, for the window to say, until the log is cleared.
        assertEquals(2, log.cut());
        log.clear();
        assertEquals(0, log.cut());
    }

    @Test
    void anEvictedErrorIsStillCountedUntilRead() {
        MessageLog log = new MessageLog(2);
        log.add(at(0, MessageLog.Severity.ERROR, "broke"));
        log.add(at(1, MessageLog.Severity.INFO, "a"));
        log.add(at(2, MessageLog.Severity.INFO, "b"));
        assertEquals(1, log.unreadErrors(), "the error dropped off the end but nobody saw it");
        log.markRead();
        assertEquals(0, log.unreadErrors());
    }

    @Test
    void clearEmptiesItAndTheCount() {
        MessageLog log = new MessageLog();
        log.add(at(0, MessageLog.Severity.ERROR, "broke"));
        log.clear();
        assertEquals(List.of(), log.entries());
        assertEquals(0, log.unreadErrors());
        assertNull(log.latest());
    }

    @Test
    void severityComesFromTheKey() {
        assertEquals(MessageLog.Severity.ERROR, MessageLog.severityOfKey("status.updateCheckFailed"));
        assertEquals(MessageLog.Severity.ERROR, MessageLog.severityOfKey("status.syncError"));
        assertEquals(MessageLog.Severity.WARNING, MessageLog.severityOfKey("status.cannotOpen"));
        assertEquals(MessageLog.Severity.WARNING, MessageLog.severityOfKey("status.invalidUrl"));
        assertEquals(MessageLog.Severity.INFO, MessageLog.severityOfKey("status.upToDate"));
    }

    @Test
    void aCopiedLineCarriesItsTime() {
        assertEquals("14:03:07  hello", MessageLog.line(at(7, MessageLog.Severity.INFO, "hello")));
        assertEquals("14:03:00  zero", MessageLog.line(at(0, MessageLog.Severity.INFO, "zero")));
    }
}
