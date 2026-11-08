package com.termina.ui;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Every message the app has said this session, newest first (DIAG-01, MLOG-01, Pauta 2.0.2): what
 * the status bar's echo area showed, and what a dialog said while the bar was hidden. Session-only
 * and bounded; no JavaFX, so its rules are tested on their own.
 */
public final class MessageLog {

    /** How much a message says about the state of things, which decides its colour. */
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    record Entry(LocalTime time, Severity severity, String text) {}

    /** Enough for a long session's messages; the Debug Log keeps the detail. */
    static final int MAX = 500;

    private final int max;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private int unreadErrors;
    /** How many of the oldest messages were dropped to stay under the cap (PERF-11). */
    private int cut;

    MessageLog() {
        this(MAX);
    }

    MessageLog(int max) {
        this.max = max;
    }

    /** Records a message now. */
    void add(Severity severity, String text) {
        add(new Entry(LocalTime.now().withNano(0), severity, text));
    }

    void add(Entry entry) {
        entries.addFirst(entry);
        // An evicted error still counts (MLOG-07): the count is of errors nobody has looked at,
        // and dropping the oldest entry is not the user looking at it.
        while (entries.size() > max) {
            entries.removeLast();
            cut++;
        }
        if (entry.severity() == Severity.ERROR) unreadErrors++;
        changed();
    }

    /** Newest first. */
    List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** The newest message, or null when there has been none. */
    @org.jspecify.annotations.Nullable
    Entry latest() {
        return entries.peekFirst();
    }

    /** How many older messages the cap has dropped since the log was last cleared. */
    int cut() {
        return cut;
    }

    int unreadErrors() {
        return unreadErrors;
    }

    /** Opening the log counts as reading it (MLOG-06). */
    void markRead() {
        if (unreadErrors == 0) return;
        unreadErrors = 0;
        changed();
    }

    void clear() {
        entries.clear();
        unreadErrors = 0;
        cut = 0;
        changed();
    }

    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }

    /**
     * A message's severity from its catalogue key (MLOG-05): a {@code status.*} key whose last part
     * names a failure or an error is an error, one that says something cannot be done or is invalid
     * is a warning, and anything else is information. New keys are named to fall on the right side.
     */
    static Severity severityOfKey(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (last.contains("fail") || last.contains("error")) return Severity.ERROR;
        if (last.contains("cannot") || last.contains("invalid")) return Severity.WARNING;
        return Severity.INFO;
    }

    /** One line of the log as Copy writes it: {@code 14:03:07  text}. */
    static String line(Entry entry) {
        return time(entry) + "  " + entry.text();
    }

    /** HH:mm:ss, always with the seconds: LocalTime drops them when they are zero. */
    static String time(Entry entry) {
        return TIME.format(entry.time());
    }

    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
}
