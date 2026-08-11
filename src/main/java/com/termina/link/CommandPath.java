package com.termina.link;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds the program a configured command names.
 *
 * <p>Pure: the directories to search and the test for "is this runnable" are both supplied, so the
 * rule can be tested without a filesystem and without caring which machine it runs on.
 *
 * <p>It exists because a GUI application does not have the PATH its user does. Launched from Finder
 * or from a desktop entry, a process inherits a stripped {@code /usr/bin:/bin:/usr/sbin:/sbin} —
 * no Homebrew, no {@code ~/.local/bin}, none of the version managers. So a command that runs
 * perfectly in the user's terminal fails from the app, with an error that looks like the command
 * being wrong rather than the environment differing.
 */
public final class CommandPath {

    private CommandPath() {}

    /**
     * Whether the command already says where the program is.
     *
     * <p>Anything with a separator is a path — absolute, or relative to wherever the app happens to
     * be — and is used as given. Only a bare name needs looking up.
     */
    public static boolean looksLikePath(String command) {
        return command != null && (command.contains("/") || command.contains("\\"));
    }

    /** Splits a PATH variable. Blank entries are dropped rather than read as the current directory. */
    public static List<Path> entries(String pathVariable) {
        List<Path> out = new ArrayList<>();
        if (pathVariable == null || pathVariable.isBlank()) return out;
        for (String part : pathVariable.split(File.pathSeparator, -1)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                out.add(Path.of(trimmed));
            } catch (RuntimeException e) {
                // A malformed entry in someone's PATH is not a reason to fail the lookup.
            }
        }
        return List.copyOf(out);
    }

    /**
     * The first directory holding a runnable file of that name, or null.
     *
     * @param isExecutable supplied so the rule is testable; in production this is {@code
     *     Files::isExecutable}
     */
    public static Path resolve(String command, List<Path> directories, Predicate<Path> isExecutable) {
        if (command == null || command.isBlank()) return null;
        if (looksLikePath(command)) {
            Path direct = Path.of(command);
            return isExecutable.test(direct) ? direct : null;
        }
        for (Path directory : directories) {
            Path candidate = directory.resolve(command);
            if (isExecutable.test(candidate)) return candidate;
        }
        return null;
    }

    /**
     * The PATH a login shell prints, pulled out from between markers.
     *
     * <p>Markers because an interactive login shell prints whatever the user's profile prints —
     * a greeting, a version notice, a fortune — and the PATH has to be told apart from all of it.
     */
    public static String extractMarked(String output, String marker) {
        if (output == null) return "";
        int first = output.indexOf(marker);
        if (first < 0) return "";
        int start = first + marker.length();
        int end = output.indexOf(marker, start);
        return end < 0 ? "" : output.substring(start, end).trim();
    }
}
