package com.termina.process;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short command that reads its input from stdin — {@link CommandOutput}'s other half, which
 * closes stdin before the command can read it.
 *
 * <p>Both of the commands this is for ({@code wl-copy}, {@code xclip -i}) fork a child that stays
 * behind to serve the selection and let the parent exit. So output goes nowhere rather than to a
 * pipe: a pipe the child inherits would stay open for as long as the selection is held, and anything
 * reading it would wait for an EOF that comes when the user next selects something.
 */
public final class CommandInput {

    private CommandInput() {}

    /** Writes {@code text} to the command and waits for it, returning whether it exited cleanly in time. */
    public static boolean write(ProcessBuilder builder, String text, Duration timeout)
            throws IOException, InterruptedException {
        Process process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            try (OutputStream in = process.getOutputStream()) {
                in.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS) && process.exitValue() == 0;
        } finally {
            // The parent only: a descendant still running is the one holding the selection.
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
