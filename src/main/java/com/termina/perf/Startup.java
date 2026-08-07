package com.termina.perf;

import java.util.ArrayList;
import java.util.List;

import javafx.animation.AnimationTimer;

/**
 * Where the time goes between launching and a usable prompt.
 *
 * <p>A terminal is opened dozens of times a day, so cold start is the number that matters most and
 * the one easiest to regress without noticing. This records the phases so a change can be measured
 * rather than argued about.
 *
 * <p><b>Off unless asked for.</b> Without {@code TERMINA_PERF=1} or {@code -Dtermina.perf} every
 * {@link #mark} is a single static boolean test, so it ships inert.
 *
 * <pre>
 *   scripts/measure-startup.sh -n 5
 *   TERMINA_PERF=1 TERMINA_PERF_EXIT=1 ./scripts/dev-run.sh
 * </pre>
 */
public final class Startup {

    private static final boolean ENABLED =
            "1".equals(System.getenv("TERMINA_PERF")) || Boolean.getBoolean("termina.perf");

    /** Exit as soon as the first frame with content is on screen, for a repeatable loop. */
    private static final boolean EXIT_AT_PAINT = "1".equals(System.getenv("TERMINA_PERF_EXIT"));

    private record Phase(String name, long nanos) {}

    private static final List<Phase> PHASES = new ArrayList<>();

    /**
     * When the process really began.
     *
     * <p>Taken from the launcher when it says so, because {@link ProcessHandle#info()}'s start
     * instant is derived from boot time on Linux and drifts: it reported a first paint earlier than
     * the process had existed. A reported figure above wall-clock is the symptom.
     */
    private static final long ORIGIN_NANOS = resolveOrigin();

    private static boolean originIsExact;

    private static boolean painted;

    private Startup() {}

    public static boolean enabled() {
        return ENABLED;
    }

    /** Records a phase. Costs one boolean test when the harness is off. */
    public static void mark(String name) {
        if (!ENABLED) return;
        synchronized (PHASES) {
            PHASES.add(new Phase(name, System.nanoTime()));
        }
    }

    /**
     * Marks the first frame that actually shows something, then reports.
     *
     * <p>On the <em>second</em> tick, not the first: a tick fires at the start of a pulse, so only
     * the second one proves the pulse that laid out and painted the content completed. That costs
     * about a frame of resolution and is worth it — the first tick can fire before anything is on
     * screen, which makes the whole measurement a lie in the flattering direction.
     */
    public static void markFirstPaint() {
        if (!ENABLED || painted) return;
        painted = true;
        new AnimationTimer() {
            private int ticks;

            @Override
            public void handle(long now) {
                if (++ticks < 2) return;
                stop();
                mark("first-paint");
                report();
                if (EXIT_AT_PAINT) System.exit(0);
            }
        }.start();
    }

    private static long resolveOrigin() {
        String stamped = System.getenv("TERMINA_PERF_T0");
        if (stamped != null && !stamped.isBlank()) {
            try {
                long epochMillis = Long.parseLong(stamped.trim());
                long sinceThen = System.currentTimeMillis() - epochMillis;
                originIsExact = true;
                return System.nanoTime() - sinceThen * 1_000_000L;
            } catch (NumberFormatException ignored) {
                // fall through to the approximation
            }
        }
        return ProcessHandle.current()
                .info()
                .startInstant()
                .map(start -> System.nanoTime() - (System.currentTimeMillis() - start.toEpochMilli()) * 1_000_000L)
                .orElse(System.nanoTime());
    }

    private static void report() {
        StringBuilder out = new StringBuilder("\n[perf] startup");
        if (!originIsExact) {
            // Said, not hidden: without a stamped origin the total is only approximate, and an
            // approximate total that looks precise is worse than one that admits it.
            out.append("  (APPROXIMATE — no TERMINA_PERF_T0; run through scripts/measure-startup.sh)");
        }
        out.append('\n');

        long previous = ORIGIN_NANOS;
        long total = 0;
        synchronized (PHASES) {
            for (Phase phase : PHASES) {
                long sinceStart = (phase.nanos() - ORIGIN_NANOS) / 1_000_000L;
                long sincePrevious = (phase.nanos() - previous) / 1_000_000L;
                out.append(String.format("  %-16s %5d ms  (+%d)%n", phase.name(), sinceStart, sincePrevious));
                previous = phase.nanos();
                total = sinceStart;
            }
        }
        out.append(String.format("  %-16s %5d ms%n", "TOTAL", total));
        // stdout, so it survives a packaged run with no logging configured.
        System.out.print(out);
    }
}
