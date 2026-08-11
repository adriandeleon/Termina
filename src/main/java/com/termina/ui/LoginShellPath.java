package com.termina.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.termina.link.CommandPath;

/**
 * Where to look for a program the user named by bare command.
 *
 * <p>The inherited PATH first, then the one a login shell reports. A GUI process is given a stripped
 * {@code /usr/bin:/bin:/usr/sbin:/sbin} — no Homebrew, no {@code ~/.local/bin}, nothing a version
 * manager put on the path — so a command the user runs happily in a terminal is simply not there
 * from a Finder-launched app. Asking their own shell is the only way to learn what they mean by it,
 * because the answer lives in their profile and is different on every machine.
 *
 * <p>Asked once, lazily, and only when a bare command actually has to be resolved: a user who has
 * configured nothing, or who gave an absolute path, never pays for the subprocess.
 */
final class LoginShellPath {

    private static final String MARKER = "__TERMINA_PATH__";

    private static final long TIMEOUT_SECONDS = 5;

    private static volatile List<Path> cached;

    private LoginShellPath() {}

    /** Directories to search, inherited first so an explicitly-set PATH still wins. */
    static synchronized List<Path> directories() {
        if (cached != null) return cached;
        List<Path> all = new ArrayList<>(CommandPath.entries(System.getenv("PATH")));
        for (Path extra : CommandPath.entries(fromLoginShell())) {
            if (!all.contains(extra)) all.add(extra);
        }
        cached = List.copyOf(all);
        return cached;
    }

    /**
     * Runs the user's shell as a login shell and reads back its PATH.
     *
     * <p>Interactive as well as login, because plenty of profiles set the PATH in the interactive
     * half. Everything is best effort — a shell that hangs, prompts, or prints nothing useful costs
     * the timeout and leaves the inherited PATH as the answer.
     */
    private static String fromLoginShell() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) return "";
        try {
            Process process = new ProcessBuilder(
                            shell, "-l", "-i", "-c", "printf '" + MARKER + "%s" + MARKER + "' \"$PATH\"")
                    // stdin from nowhere, so a profile that reads it cannot wait forever; stderr
                    // discarded, since a banner is not an error and an undrained pipe would block.
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return CommandPath.extractMarked(out, MARKER);
        } catch (InterruptedException e) {
            // Restored rather than swallowed: this runs on a caller's thread, and eating the flag
            // would hide a shutdown from whatever is above.
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            // No shell, no permission, a profile that hangs: the inherited PATH is still an answer,
            // and nothing here is worth failing a click over.
            return "";
        }
    }
}
