package com.termina.ui;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * What went wrong, kept somewhere a user can reach it.
 *
 * <p>A packaged application has no console. Anything logged, and anything thrown on a thread with
 * no handler, would otherwise vanish — so the one useful sentence about why the shell would not
 * start goes nowhere, and the bug report says "it did not work".
 *
 * <p>Two destinations, for two situations. A bounded in-memory buffer backs the window, which
 * covers "it is misbehaving right now". A file beside the settings covers "it died", since a
 * crashed process cannot show its own window; it is truncated per session, so it stays the story of
 * this run rather than growing without limit.
 *
 * <p><b>The file is locked for the life of the process.</b> Truncating per session is right for
 * one instance and wrong for two: a dev run without {@code TERMINA_CONFIG_DIR} wiped the packaged
 * app's log, which is the one with the crash in it. So the file is taken with {@code tryLock}, and a
 * second instance that finds it held writes to a sibling named after its own pid instead.
 */
public final class DebugLog {

    /** Enough to hold a session's worth of trouble without holding a session's worth of memory. */
    private static final int MAX_ENTRIES = 2000;

    private static final String FILE_NAME = "termina-session.log";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final Deque<String> ENTRIES = new ArrayDeque<>();

    private static @Nullable Path file;

    /** Held open so the lock stays held; closing it would release the file to the next instance. */
    private static @Nullable FileChannel channel;

    private static @Nullable FileLock lock;

    private DebugLog() {}

    /** Captures j.u.l output and uncaught exceptions. Call once, as early as possible. */
    public static void install() {
        Logger root = Logger.getLogger("");
        root.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() < Level.WARNING.intValue()) return;
                append(format(record));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            append(stamp("UNCAUGHT on " + thread.getName() + ": " + stackTrace(error)));
            // Still to stderr, for anyone who did launch from a terminal.
            error.printStackTrace();
        });
    }

    /**
     * Also mirror to {@code <configDir>/termina-session.log}, truncated for this run — or, when
     * another instance holds that file, to {@code termina-session-<pid>.log} beside it.
     */
    public static synchronized void attachFile(Path configDir) {
        attachFile(configDir, ProcessHandle.current().pid());
    }

    static synchronized void attachFile(Path configDir, long pid) {
        detachFile();
        try {
            Files.createDirectories(configDir);
            for (Path candidate : List.of(configDir.resolve(FILE_NAME), configDir.resolve(siblingName(pid)))) {
                if (claim(candidate)) return;
            }
        } catch (IOException e) {
            // A log that cannot be written is not worth failing a launch over.
            detachFile();
        }
    }

    /** The name a second instance falls back to. */
    static String siblingName(long pid) {
        return "termina-session-" + pid + ".log";
    }

    /**
     * Takes the file if nobody else has it. Truncation happens only after the lock is held, which
     * is the whole point: the first version truncated first and asked no questions.
     */
    private static boolean claim(Path candidate) throws IOException {
        FileChannel opened = FileChannel.open(
                candidate, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        FileLock held;
        try {
            held = opened.tryLock();
        } catch (OverlappingFileLockException e) {
            // The same JVM already holds it — which, outside a test, means this very class.
            held = null;
        }
        if (held == null) {
            opened.close();
            return false;
        }
        opened.truncate(0);
        channel = opened;
        lock = held;
        file = candidate;
        return true;
    }

    /** Releases the file. For tests, and for nothing else: a running app keeps its log to the end. */
    static synchronized void detachFile() {
        try {
            if (lock != null) lock.release();
            if (channel != null) channel.close();
        } catch (IOException ignored) {
            // Nothing left to do with a channel that will not close.
        }
        lock = null;
        channel = null;
        file = null;
    }

    static synchronized void append(String line) {
        ENTRIES.addLast(line);
        while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
        if (channel == null) return;
        try {
            ByteBuffer bytes = ByteBuffer.wrap((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            channel.position(channel.size());
            while (bytes.hasRemaining()) channel.write(bytes);
        } catch (IOException ignored) {
            // Same again: the in-memory copy is still there.
        }
    }

    /** Everything captured this session, oldest first. */
    public static synchronized String text() {
        return String.join(System.lineSeparator(), ENTRIES);
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    /** The file being mirrored to, or null when there is none. */
    public static synchronized @Nullable Path file() {
        return file;
    }

    /** Renders a record as one line, with a stack trace after it when there is one. */
    static String format(LogRecord record) {
        StringBuilder out = new StringBuilder(stamp(
                record.getLevel().getName() + " " + shortName(record.getLoggerName()) + " — " + record.getMessage()));
        if (record.getThrown() != null) {
            out.append(System.lineSeparator()).append(stackTrace(record.getThrown()));
        }
        return out.toString();
    }

    private static String stamp(String message) {
        return LocalTime.now().format(TIME) + "  " + message;
    }

    /**
     * The last segment of a logger name.
     *
     * <p>Full names are mostly package, and the package is the same for every line that matters —
     * so the part that identifies the source is the part that gets pushed off the end.
     */
    static String shortName(String loggerName) {
        if (loggerName == null || loggerName.isBlank()) return "?";
        int dot = loggerName.lastIndexOf('.');
        return dot < 0 || dot == loggerName.length() - 1 ? loggerName : loggerName.substring(dot + 1);
    }

    private static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString().stripTrailing();
    }
}
