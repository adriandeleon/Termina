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

    public static final String FILE_NAME = "termina-session.log";

    /** The session before this one's log, kept when this one's starts (DIAG-02). */
    public static final String PREVIOUS_FILE_NAME = "termina-session.prev.log";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final Deque<String> ENTRIES = new ArrayDeque<>();

    private static @Nullable Path file;

    /** Held open so the lock stays held; closing it would release the file to the next instance. */
    private static @Nullable FileChannel channel;

    private static @Nullable FileLock lock;

    private DebugLog() {}

    /** Told of each uncaught exception after it is recorded, on the thread that threw it. */
    private static volatile java.util.function.@Nullable Consumer<Throwable> uncaughtListener;

    /** Who says something went wrong where the user is looking (DIAG-04): the window manager. */
    public static void onUncaught(java.util.function.Consumer<Throwable> listener) {
        uncaughtListener = listener;
    }

    /** Captures j.u.l output and uncaught exceptions. Call once, as early as possible. */
    public static void install() {
        Logger root = Logger.getLogger("");
        root.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                // Every record the root logger lets through, INFO and up by default (DIAG-02, Pauta
                // 2.0.2): what led up to a failure is as useful as the failure. JediTerm's and pty4j's
                // slf4j records arrive here too, through the java.util.logging binding.
                append(format(record));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            append(stamp("UNCAUGHT on " + thread.getName() + ": " + stackTrace(error)));
            java.util.function.Consumer<Throwable> listener = uncaughtListener;
            if (listener != null) listener.accept(error);
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
        // The last session's log is what a report after a crash needs, so it is kept, once the lock
        // says no running Termina is still writing it. A per-process sibling has no history to keep.
        // Read through the channel that holds the lock: on Windows the lock bars a second handle.
        long previous = opened.size();
        if (candidate.getFileName().toString().equals(FILE_NAME) && previous > 0 && previous < 64L * 1024 * 1024) {
            ByteBuffer bytes = ByteBuffer.allocate((int) previous);
            opened.position(0);
            while (bytes.hasRemaining() && opened.read(bytes) > 0) {
                // reading the whole file
            }
            Files.write(
                    candidate.resolveSibling(PREVIOUS_FILE_NAME),
                    java.util.Arrays.copyOf(bytes.array(), bytes.position()));
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

    /**
     * Writes the file, one line at a time, in order. Records come from any thread, the JavaFX one
     * included, and a write is disk I/O that thread must not wait on (PERF-01, Pauta 2.0.2).
     */
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "debug-log-writer");
                t.setDaemon(true);
                return t;
            });

    static void append(String line) {
        synchronized (DebugLog.class) {
            ENTRIES.addLast(line);
            while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
            if (channel == null) return;
        }
        // A thread already inside this class's lock writes for itself: the writer needs that lock,
        // and waiting on it from here would wait forever.
        if (Thread.holdsLock(DebugLog.class)) {
            write(line);
            return;
        }
        java.util.concurrent.Future<?> written = WRITER.submit(() -> write(line));
        // Any other thread waits for its line to reach the file: a record of something going wrong
        // is worth most just before the process ends, and the writer is a daemon that would die
        // with the line still queued. The FX thread hands its line over and moves on.
        if (!onFxThread()) {
            try {
                written.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException ignored) {
                // write() swallows its own failures; nothing reaches here.
            }
        }
    }

    private static boolean onFxThread() {
        try {
            return javafx.application.Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static synchronized void write(String line) {
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
