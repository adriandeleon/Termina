package com.termina.shell;

import java.util.List;

import com.termina.cli.Argv;

/**
 * One thing a tab can be opened as: a name, the command that starts it, and where.
 *
 * <p>A terminal on Windows is not one program. Command Prompt, Windows PowerShell, PowerShell 7 and
 * every installed WSL distribution are separate shells with separate command lines, and a single
 * "path to your shell" setting can only ever name one of them. The profile is the unit that lets a
 * window offer all of them at once — the same thing Windows Terminal's dropdown lists, and the same
 * thing macOS and Linux want the moment someone has both zsh and fish installed.
 *
 * @param id a stable key, used to record which profile is the default and to bind a chord to it.
 *     Stable across restarts and across discovery finding things in a different order, which is why
 *     it is not the list index.
 * @param name what the menu shows
 * @param command the argv to run. Never empty: a profile with nothing to run is not a profile, and
 *     the one case that would need it — "whatever {@code $SHELL} says" — is resolved into a real
 *     command by {@link ShellProfiles} before it ever gets here.
 * @param workingDirectory where to start, or blank to inherit the current tab's directory
 * @param source where the profile came from, which decides whether it can be edited
 */
public record Profile(String id, String name, List<String> command, String workingDirectory, Source source) {

    /** Where a profile came from. Only the last of these is the user's to edit or delete. */
    public enum Source {
        /**
         * The shell the OS says is yours — {@code $SHELL}, or the Windows fallback chain. Always
         * present and always first, so a window still opens on a machine where nothing else is
         * found and so the existing shell setting keeps working.
         */
        SYSTEM,
        /** Found installed on this machine. Re-derived at every launch, never written down. */
        DISCOVERED,
        /** Written by the user, in the settings window or by hand in the settings file. */
        USER
    }

    public Profile {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        command = command == null ? List.of() : List.copyOf(command);
        workingDirectory = workingDirectory == null ? "" : workingDirectory.trim();
        source = source == null ? Source.USER : source;
    }

    public static Profile of(String id, String name, List<String> command, Source source) {
        return new Profile(id, name, command, "", source);
    }

    /** Whether this profile names something that can actually be run. */
    public boolean isRunnable() {
        return !id.isEmpty() && !command.isEmpty();
    }

    /** True when the user may edit or delete it; the other two are re-derived at every launch. */
    public boolean isEditable() {
        return source == Source.USER;
    }

    /** The command as one line, for a settings field and for a tooltip. */
    public String commandLine() {
        return Argv.join(command);
    }

    public Profile withName(String newName) {
        return new Profile(id, newName, command, workingDirectory, source);
    }

    public Profile withCommandLine(String line) {
        return new Profile(id, name, Argv.split(line), workingDirectory, source);
    }

    public Profile withWorkingDirectory(String directory) {
        return new Profile(id, name, command, directory, source);
    }

    /** What to hand the launcher: this profile's command, started in {@code fallbackDirectory}. */
    public com.termina.pty.LaunchOptions toLaunchOptions(String fallbackDirectory) {
        String directory = workingDirectory.isBlank() ? fallbackDirectory : workingDirectory;
        return new com.termina.pty.LaunchOptions("", directory, command);
    }
}
