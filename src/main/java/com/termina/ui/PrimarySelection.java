package com.termina.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;

import com.termina.process.CommandInput;
import com.termina.process.CommandOutput;
import org.jspecify.annotations.Nullable;

/**
 * The X11 PRIMARY selection — what a middle-click pastes on Linux, and what selecting text sets —
 * reached through the command-line tools, because JavaFX cannot reach it.
 *
 * <p>JavaFX's {@code Clipboard} is the CLIPBOARD selection and nothing else; there is no API for
 * PRIMARY at all. Every desktop that has one ships a tool for it, though: {@code wl-paste} and
 * {@code wl-copy} under Wayland, {@code xclip} or {@code xsel} under X11. Which of them is asked
 * follows the session, Wayland first — under GNOME on Wayland JavaFX itself runs through XWayland,
 * and the compositor bridges the two, but the Wayland side is the one other applications see.
 *
 * <p>On Linux with no such tool installed, a middle-click pastes the clipboard and selecting sets
 * nothing. On macOS and Windows, which have no PRIMARY, a middle-click pastes nothing at all
 * (OS-04, Pauta 6.0.1: the one row where the three differ, and Linux's alone).
 *
 * <p>All of it off the FX thread, on one worker, so a tool that hangs hangs there.
 */
final class PrimarySelection {

    /** How to read and how to write the selection, as command lines. */
    record Tools(List<String> read, List<String> write) {}

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final boolean LINUX = existsOn(com.termina.Os.name());

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "primary-selection");
        thread.setDaemon(true);
        return thread;
    });

    /** Resolved once, on the worker. Empty means "looked, and there is nothing". */
    private static volatile @Nullable Optional<Tools> tools;

    /** The text waiting to be written, so a burst of selections is one write of the last of them. */
    private static final AtomicReference<@Nullable String> pending = new AtomicReference<>();

    private PrimarySelection() {}

    /**
     * Which tools to use, given the session's environment and a way to find a program — or null when
     * there are none. Pure, so every session shape is tested without one.
     *
     * @param find a program's absolute path, or null when it is not installed
     */
    static @Nullable Tools choose(Map<String, String> env, Function<String, @Nullable Path> find) {
        if (present(env.get("WAYLAND_DISPLAY"))) {
            Path paste = find.apply("wl-paste");
            Path copy = find.apply("wl-copy");
            if (paste != null && copy != null) {
                return new Tools(
                        List.of(paste.toString(), "--primary", "--no-newline"), List.of(copy.toString(), "--primary"));
            }
        }
        if (present(env.get("DISPLAY"))) {
            Path xclip = find.apply("xclip");
            if (xclip != null) {
                return new Tools(
                        List.of(xclip.toString(), "-o", "-selection", "primary"),
                        List.of(xclip.toString(), "-i", "-selection", "primary"));
            }
            Path xsel = find.apply("xsel");
            if (xsel != null) {
                return new Tools(List.of(xsel.toString(), "-p", "-o"), List.of(xsel.toString(), "-p", "-i"));
            }
        }
        return null;
    }

    /** Whether this platform has a PRIMARY selection, so that a middle-click is meant to paste it. */
    static boolean exists() {
        return LINUX;
    }

    /** The same, for an {@code os.name}: only Linux has one. */
    static boolean existsOn(String os) {
        return com.termina.Os.of(os) == com.termina.Os.Kind.LINUX;
    }

    private static boolean present(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static Optional<Tools> tools() {
        Optional<Tools> found = tools;
        if (found != null) return found;
        synchronized (PrimarySelection.class) {
            if (tools == null) {
                tools = LINUX ? Optional.ofNullable(choose(System.getenv(), SystemTools::find)) : Optional.empty();
            }
            return tools;
        }
    }

    /** Sets PRIMARY to {@code text}, later and off the FX thread. Does nothing where there is none. */
    static void set(String text) {
        if (!LINUX || text == null || text.isEmpty()) return;
        if (pending.getAndSet(text) != null) return; // a write is queued and will take this text
        WORKER.execute(() -> {
            String latest = pending.getAndSet(null);
            Tools found = tools().orElse(null);
            if (found == null || latest == null) return;
            try {
                CommandInput.write(
                        com.termina.process.ChildPath.apply(new ProcessBuilder(found.write())), latest, TIMEOUT);
            } catch (Exception e) {
                // A selection that did not reach PRIMARY is not worth an error: the clipboard still
                // has whatever it had, and the user can copy explicitly.
            }
        });
    }

    /**
     * Reads PRIMARY and hands the text to {@code paste} on the FX thread — or, where there is no
     * PRIMARY or it cannot be read, runs {@code otherwise} there instead.
     */
    static void get(Consumer<String> paste, Runnable otherwise) {
        // No PRIMARY, no paste: neither the selection nor the clipboard in its place.
        if (!LINUX) return;
        WORKER.execute(() -> {
            Tools found = tools().orElse(null);
            String text = null;
            if (found != null) {
                try {
                    text = CommandOutput.read(
                                    com.termina.process.ChildPath.apply(
                                            new ProcessBuilder(new ArrayList<>(found.read()))),
                                    TIMEOUT)
                            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                            .orElse(null);
                } catch (Exception e) {
                    text = null;
                }
            }
            String result = text;
            Platform.runLater(() -> {
                if (found == null) otherwise.run();
                else if (result != null) paste.accept(result);
            });
        });
    }
}
