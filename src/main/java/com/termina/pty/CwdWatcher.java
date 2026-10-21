package com.termina.pty;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.termina.process.RunningProgram;

/**
 * Watches one process's working directory, and what it is running, and reports each when it
 * changes.
 *
 * <p>Polled rather than pushed, because there is nothing to subscribe to: {@code chdir} is a
 * syscall the shell makes on its own behalf and no signal comes out of it, and a child starting is
 * the same. The cost is one {@code readlink} and one walk of the process table per session per
 * tick — set against the repaint timer already running at frame rate for every open tab, it does
 * not register. All watchers share a single daemon thread, so tab count buys threads at zero.
 *
 * <p>The program is read here, on this thread and this cadence, because the one place that must
 * never ask is the renderer: a process-table walk per frame would be the single most expensive
 * thing the view did. Here it is a human-speed poll of a human-speed event.
 *
 * <p>The callbacks fire on that thread, never on the JavaFX one. Each fires on the first successful
 * read and thereafter only on a change, so a binding driven from it does not churn.
 */
public final class CwdWatcher implements AutoCloseable {

    /**
     * Fast enough that the tab has caught up before the eye moves to it, slow enough to be free.
     * The directory only changes when the user runs a command, which is a human-speed event.
     */
    private static final long INTERVAL_MS = 750;

    private static final class Pool {
        static final ScheduledExecutorService INSTANCE = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "termina-cwd");
            t.setDaemon(true);
            return t;
        });
    }

    private final ScheduledFuture<?> task;
    private volatile String last;
    private volatile String lastProgram;

    private CwdWatcher(long pid, Consumer<String> onChange, Consumer<String> onProgramChange) {
        task = Pool.INSTANCE.scheduleWithFixedDelay(
                () -> poll(pid, onChange, onProgramChange), 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts watching the directory alone, reporting it immediately and then on every change.
     *
     * @return a handle to stop with; closing it is what keeps a closed tab from being polled
     *     forever, so it belongs on the same path that disposes the session
     */
    public static CwdWatcher watch(long pid, Consumer<String> onChange) {
        return new CwdWatcher(pid, onChange, null);
    }

    /**
     * Starts watching the directory and the foreground program.
     *
     * <p>The program is {@link RunningProgram#first}: the shell's first child, or empty once it has
     * none again — empty is reported too, so a tab that said {@code vim} says the directory again
     * when vim exits. The directory half is skipped where the OS cannot answer, and the program half
     * works everywhere {@link ProcessHandle} does, which is why this is started on every platform.
     */
    public static CwdWatcher watch(long pid, Consumer<String> onChange, Consumer<String> onProgramChange) {
        return new CwdWatcher(pid, onChange, onProgramChange);
    }

    private void poll(long pid, Consumer<String> onChange, Consumer<String> onProgramChange) {
        try {
            if (ProcessCwd.isSupported()) pollCwd(pid, onChange);
            if (onProgramChange != null) pollProgram(pid, onProgramChange);
        } catch (Throwable t) {
            // A throw here would cancel the scheduled task silently and stop the watch for good.
        }
    }

    private void pollCwd(long pid, Consumer<String> onChange) {
        Optional<String> cwd = ProcessCwd.of(pid);
        // Empty means the process is gone, or this platform cannot say. Neither is a reason to
        // wipe a directory already on screen: a tab whose shell has exited should keep reading
        // as the place it was, not blank out.
        if (cwd.isEmpty()) return;
        String value = cwd.get();
        if (value.equals(last)) return;
        last = value;
        onChange.accept(value);
    }

    private void pollProgram(long pid, Consumer<String> onProgramChange) {
        String program = RunningProgram.first(pid).orElse("");
        if (program.equals(lastProgram)) return;
        lastProgram = program;
        onProgramChange.accept(program);
    }

    @Override
    public void close() {
        task.cancel(false);
    }
}
