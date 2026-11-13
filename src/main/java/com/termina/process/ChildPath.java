package com.termina.process;

import com.termina.Os;
import org.jspecify.annotations.Nullable;

/**
 * The {@code PATH} a program the app starts is given on macOS and Linux (CLI-10, Pauta 6.0.1): the
 * one this process inherited followed by the login shell's, once that is known.
 *
 * <p>A desktop launcher hands an app a stripped {@code PATH}, so a tool found by its full path
 * still fails when it starts a helper by name: {@code gh} looking for {@code git}, {@code git} for
 * a credential helper, an opener for the browser. Whoever asks the login shell publishes the
 * answer here ({@code ui.LoginShellPath}), and every place that starts a process applies it.
 *
 * <p>Applying never asks. The login shell is started only for a tool the user installed, where
 * the wait is the price of finding it; a system tool on a middle-click or a notification gets the
 * answer if someone has already paid for it, and the inherited {@code PATH} if not. Windows has
 * no login shell's {@code PATH}, and the inherited one is used there.
 */
public final class ChildPath {

    private static volatile @Nullable String known;

    private ChildPath() {}

    /** The login shell's answer, joined with the inherited {@code PATH}: kept for every later child. */
    public static void publish(String path) {
        if (!path.isBlank()) known = path;
    }

    /** What was published, or null while nobody has asked the login shell. */
    public static @Nullable String known() {
        return known;
    }

    /** Gives {@code builder}'s process the published {@code PATH} on this system; returns the builder. */
    public static ProcessBuilder apply(ProcessBuilder builder) {
        return apply(builder, Os.current(), known);
    }

    /** {@link #apply(ProcessBuilder)} for a system and an answer given: untouched on Windows, or with none. */
    static ProcessBuilder apply(ProcessBuilder builder, Os.Kind os, @Nullable String path) {
        if (path != null && os != Os.Kind.WINDOWS) builder.environment().put("PATH", path);
        return builder;
    }
}
