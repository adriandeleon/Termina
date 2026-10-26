package com.termina.pty;

import java.util.List;

/**
 * What to start in a terminal, and where.
 *
 * <p>One record rather than three parameters threaded through the view, the session and the
 * launcher: every one of those signatures would otherwise grow each time the command line does.
 *
 * @param shell an explicit shell path, or blank for the user's own
 * @param workingDirectory the directory to start in, or blank for the home directory
 * @param command a command to run instead of a shell — {@code termina -e vim}. The terminal closes
 *     when it exits, which is the behaviour of every terminal that offers this.
 */
public record LaunchOptions(String shell, String workingDirectory, List<String> command, String profileId) {

    public static final LaunchOptions DEFAULTS = new LaunchOptions("", "", List.of());

    public LaunchOptions {
        shell = shell == null ? "" : shell;
        workingDirectory = workingDirectory == null ? "" : workingDirectory;
        command = command == null ? List.of() : List.copyOf(command);
        profileId = profileId == null ? "" : profileId;
    }

    /** Options that came from no profile: the configured shell, or a command line. */
    public LaunchOptions(String shell, String workingDirectory, List<String> command) {
        this(shell, workingDirectory, command, "");
    }

    /** The configured shell and nothing else — what a new tab gets. */
    public static LaunchOptions ofShell(String shell) {
        return new LaunchOptions(shell, "", List.of());
    }

    public boolean hasCommand() {
        return !command.isEmpty();
    }

    /** The same options started somewhere else — a new tab inheriting the current one's directory. */
    public LaunchOptions withWorkingDirectory(String directory) {
        return new LaunchOptions(shell, directory, command, profileId);
    }

    /** The same options with a different shell, used when settings change but the CLI still applies. */
    public LaunchOptions withShell(String newShell) {
        return new LaunchOptions(newShell, workingDirectory, command, profileId);
    }

    /**
     * The profile these were made from, which travels with the pane — to its splits, its duplicate,
     * its reopening — so the profile's font and theme go with it. Empty for none.
     */
    public LaunchOptions withProfileId(String id) {
        return new LaunchOptions(shell, workingDirectory, command, id);
    }
}
