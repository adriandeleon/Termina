package com.termina.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Runs a short discovery command without waiting for a pipe's EOF before enforcing its deadline. */
public final class CommandOutput {
    private static final int MAX_BYTES = 1024 * 1024;

    private CommandOutput() {}

    public static Optional<byte[]> read(ProcessBuilder builder, Duration timeout)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile("termina-command-", ".out");
        Process process = null;
        try {
            // A file cannot fill a pipe, and inherited stdout in a child cannot keep an EOF read blocked.
            process = builder.redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) return Optional.empty();
            try (var in = Files.newInputStream(output)) {
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                return bytes.length > MAX_BYTES ? Optional.empty() : Optional.of(bytes);
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
                // Windows may still hold the output file while the terminated process exits.
                output.toFile().deleteOnExit();
            }
        }
    }
}
