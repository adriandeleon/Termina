package com.termina.cli;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Termina's command line.
 *
 * <p>Pure: parsing happens before the toolkit starts, and it is the one part of startup that has to
 * be right whether or not a window ever appears. {@code --version} in particular is what somebody
 * pastes into a bug report, so it cannot depend on the application having got as far as a display.
 *
 * <p>The option names follow the terminals people already have. {@code -e} takes the rest of the
 * line as a command, which is xterm's convention and GNOME Terminal's; {@code --working-directory}
 * is GNOME's spelling and {@code -d} the short form. {@code -T} is xterm's title option, which
 * Debian's {@code x-terminal-emulator} contract requires every registered terminal to accept.
 */
public record CommandLine(
        boolean help,
        boolean version,
        boolean report,
        boolean newInstance,
        boolean dev,
        boolean noSession,
        String workingDirectory,
        List<String> command,
        String configDir,
        String title,
        @Nullable String error) {

    /** Parsed successfully and the application should start. */
    public boolean shouldRun() {
        return !help && !version && !report && error == null;
    }

    /** Whether a command was given with {@code -e}, to be run instead of a shell. */
    public boolean hasCommand() {
        return command != null && !command.isEmpty();
    }

    public static CommandLine parse(String... args) {
        boolean help = false;
        boolean version = false;
        boolean report = false;
        boolean newInstance = false;
        boolean dev = false;
        boolean noSession = false;
        String workingDirectory = "";
        String configDir = "";
        String title = "";
        List<String> command = new ArrayList<>();

        for (int i = 0; args != null && i < args.length; i++) {
            String arg = args[i];

            // Everything after -e belongs to the command, options included: `-e ls -l` must pass -l
            // to ls rather than reject it as unknown. This is why it has to be last.
            if (arg.equals("-e") || arg.equals("--command")) {
                for (int j = i + 1; j < args.length; j++) command.add(args[j]);
                if (command.isEmpty()) return error("-e needs a command");
                break;
            }
            if (arg.startsWith("--command=")) {
                String first = value(arg);
                if (first.isEmpty()) return error("--command needs a command");
                command.add(first);
                for (int j = i + 1; j < args.length; j++) command.add(args[j]);
                break;
            }

            // What a launcher can leave among the program's arguments is skipped with its value,
            // never refused (CLI-12, Pauta 6.0.1).
            int launcherOption = launcherOptionWidth(arg);
            if (launcherOption > 0) {
                i += launcherOption - 1;
                continue;
            }

            switch (arg) {
                case "-h", "--help" -> help = true;
                case "-v", "-V", "--version" -> version = true;
                case "--report" -> report = true;
                case "--new-instance" -> newInstance = true;
                case "--dev" -> dev = true;
                case "--no-session" -> noSession = true;
                case "-d", "--working-directory" -> {
                    if (i + 1 >= args.length) return error(arg + " needs a directory");
                    workingDirectory = args[++i];
                }
                case "--config-dir" -> {
                    if (i + 1 >= args.length) return error(arg + " needs a directory");
                    configDir = args[++i];
                }
                case "-T", "--title" -> {
                    if (i + 1 >= args.length) return error(arg + " needs a title");
                    title = args[++i];
                }
                default -> {
                    if (arg.startsWith("--working-directory=")) {
                        workingDirectory = value(arg);
                    } else if (arg.startsWith("--config-dir=")) {
                        configDir = value(arg);
                    } else if (arg.startsWith("--title=")) {
                        title = value(arg);
                    } else if (arg.startsWith("-") && !arg.equals("-")) {
                        // Refused rather than ignored: a mistyped option that is silently dropped
                        // looks like the option did not work.
                        return error("unknown option: " + arg);
                    } else {
                        return error("unexpected argument: " + arg + " (use -e to run a command)");
                    }
                }
            }
        }
        return new CommandLine(
                help,
                version,
                report,
                newInstance,
                dev,
                noSession,
                workingDirectory,
                List.copyOf(command),
                configDir,
                title,
                null);
    }

    /**
     * How many arguments a JVM option left by the launcher takes up: 2 for {@code --add-exports},
     * {@code --add-opens} and {@code -p} followed by their value, 1 for the two long ones with the
     * value after {@code =}, 0 for anything else. By name, so a mistyped option of ours is still
     * refused.
     */
    static int launcherOptionWidth(String arg) {
        return switch (arg) {
            case "--add-exports", "--add-opens", "-p" -> 2;
            default -> arg.startsWith("--add-exports=") || arg.startsWith("--add-opens=") ? 1 : 0;
        };
    }

    private static String value(String arg) {
        return arg.substring(arg.indexOf('=') + 1);
    }

    private static CommandLine error(String message) {
        return new CommandLine(false, false, false, false, false, false, "", List.of(), "", "", message);
    }

    /** The text {@code --help} prints. */
    public static String usage(String name) {
        return """
                Usage: %s [options] [-e command [args...]]

                A cross-platform terminal emulator.

                Options:
                  -h, --help                     Show this help and exit
                  -v, --version                  Show the version and exit
                      --report                   Write a problem report to a file, open a GitHub issue for it, and exit
                      --new-instance             Start a Termina of its own, not a window in the running one
                      --dev                      Use ~/.termina-dev for settings, leaving ~/.termina alone
                      --no-session               Start without the tabs that were open last time
                  -d, --working-directory=DIR    Start the shell in DIR
                  -T, --title=TITLE              Name the first tab TITLE
                  -e, --command CMD [args...]    Run CMD instead of the shell; must come last
                      --config-dir=DIR           Read and write settings in DIR

                The shell, colours, font and everything else are configured in the application
                itself, under Settings. TERMINA_CONFIG_DIR does the same as --config-dir.
                """.formatted(name);
    }
}
