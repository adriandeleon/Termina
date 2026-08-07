package com.termina.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Termina's command line.
 *
 * <p>Pure: parsing happens before the toolkit starts, and it is the one part of startup that has to
 * be right whether or not a window ever appears. {@code --version} in particular is what somebody
 * pastes into a bug report, so it cannot depend on the application having got as far as a display.
 *
 * <p>The option names follow the terminals people already have. {@code -e} takes the rest of the
 * line as a command, which is xterm's convention and GNOME Terminal's; {@code --working-directory}
 * is GNOME's spelling and {@code -d} the short form.
 */
public record CommandLine(
        boolean help,
        boolean version,
        String workingDirectory,
        List<String> command,
        String configDir,
        String error) {

    /** Parsed successfully and the application should start. */
    public boolean shouldRun() {
        return !help && !version && error == null;
    }

    /** Whether a command was given with {@code -e}, to be run instead of a shell. */
    public boolean hasCommand() {
        return command != null && !command.isEmpty();
    }

    public static CommandLine parse(String... args) {
        boolean help = false;
        boolean version = false;
        String workingDirectory = "";
        String configDir = "";
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

            switch (arg) {
                case "-h", "--help" -> help = true;
                case "-v", "-V", "--version" -> version = true;
                case "-d", "--working-directory" -> {
                    if (i + 1 >= args.length) return error(arg + " needs a directory");
                    workingDirectory = args[++i];
                }
                case "--config-dir" -> {
                    if (i + 1 >= args.length) return error(arg + " needs a directory");
                    configDir = args[++i];
                }
                default -> {
                    if (arg.startsWith("--working-directory=")) {
                        workingDirectory = value(arg);
                    } else if (arg.startsWith("--config-dir=")) {
                        configDir = value(arg);
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
        return new CommandLine(help, version, workingDirectory, List.copyOf(command), configDir, null);
    }

    private static String value(String arg) {
        return arg.substring(arg.indexOf('=') + 1);
    }

    private static CommandLine error(String message) {
        return new CommandLine(false, false, "", List.of(), "", message);
    }

    /** The text {@code --help} prints. */
    public static String usage(String name) {
        return """
                Usage: %s [options] [-e command [args...]]

                A cross-platform terminal emulator.

                Options:
                  -h, --help                     Show this help and exit
                  -v, --version                  Show the version and exit
                  -d, --working-directory=DIR    Start the shell in DIR
                  -e, --command CMD [args...]    Run CMD instead of the shell; must come last
                      --config-dir=DIR           Read and write settings in DIR

                The shell, colours, font and everything else are configured in the application
                itself, under Settings. TERMINA_CONFIG_DIR does the same as --config-dir.
                """
                .formatted(name);
    }
}
