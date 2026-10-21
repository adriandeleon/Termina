package com.termina.pty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.termina.cli.Argv;

/**
 * Chooses the shell to run and starts it attached to a pseudo-terminal.
 *
 * <p>The OS split here is not cosmetic: on Unix pty4j opens a real PTY, while on Windows it drives
 * ConPTY (or winpty on older builds), and the two disagree about what a "command" is.
 */
public final class ShellLauncher {

    private ShellLauncher() {}

    /** Terminal type advertised to the child. 256-color because the renderer supports it. */
    private static final String TERM = "xterm-256color";

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    /**
     * The command line for the user's shell.
     *
     * <p>Unix shells are started as <em>login</em> shells (-l). A GUI process inherits a stripped
     * PATH — on macOS roughly {@code /usr/bin:/bin:/usr/sbin:/sbin}, with no Homebrew and no
     * version-manager (nvm/asdf/rbenv) directories — so a non-login shell would leave the user
     * unable to run most of what they have installed. The login shell reads the profile that puts
     * those directories back.
     */
    public static List<String> shellCommand() {
        return shellCommand("");
    }

    /**
     * @param override an explicit shell path, or blank to use the user's own. An override that is
     *     not executable is ignored rather than failing the launch — a stale settings entry should
     *     not leave someone unable to open a terminal at all.
     */
    public static List<String> shellCommand(String override) {
        return shellCommand(override, false);
    }

    /**
     * @param logFallback say so in the log when the override is ignored. Only the launch path asks
     *     for it: the system profile resolves the same command every time a menu is built, and a
     *     line per menu would bury the one that matters.
     */
    private static List<String> shellCommand(String override, boolean logFallback) {
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override.trim());
            if (Files.isExecutable(path)) {
                return isWindows() ? List.of(path.toString()) : List.of(path.toString(), "-l");
            }
            if (logFallback) {
                // INFO, not WARNING: the launch still works. But a typo in the shell field that
                // quietly gives you bash reads as "the setting is broken", and this is the one
                // place that knows otherwise.
                System.getLogger(ShellLauncher.class.getName())
                        .log(
                                System.Logger.Level.INFO,
                                "configured shell " + path + " is not executable; using the system shell");
            }
        }
        if (isWindows()) {
            String pwsh = findOnPath("pwsh.exe");
            if (pwsh != null) return List.of(pwsh, "-NoLogo");
            String powershell = findOnPath("powershell.exe");
            if (powershell != null) return List.of(powershell, "-NoLogo");
            String comspec = System.getenv("COMSPEC");
            return List.of(comspec != null && !comspec.isBlank() ? comspec : "cmd.exe");
        }
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) {
            shell = isMac() ? "/bin/zsh" : "/bin/bash";
            if (!Files.isExecutable(Path.of(shell))) shell = "/bin/sh";
        }
        return List.of(shell, "-l");
    }

    /**
     * Where to start the shell.
     *
     * <p>A directory that is not there falls back to home rather than failing the launch: the
     * argument is usually supplied by another program — a file manager, an editor — and a stale one
     * should not mean no terminal.
     */
    static String workingDirectory(String requested) {
        if (requested != null && !requested.isBlank()) {
            Path path = Path.of(requested.trim());
            if (Files.isDirectory(path)) return path.toAbsolutePath().toString();
        }
        return System.getProperty("user.home");
    }

    private static String findOnPath(String exe) {
        return findOnPath(exe, System::getenv, Files::isExecutable);
    }

    private static String findOnPath(String exe, Function<String, String> env, Predicate<Path> isExecutable) {
        String path = env.apply("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path p = Path.of(dir, exe);
            if (isExecutable.test(p)) return p.toString();
        }
        return null;
    }

    // ---------------------------------------------------------------- validation

    /**
     * What a settings field can say about the path typed into it.
     *
     * <p>The launcher falls back silently on purpose — a stale entry must not cost someone their
     * terminal — which leaves the settings window as the only place the user can be told. These are
     * the answers it gives, computed here so the rule and the fallback cannot drift apart.
     */
    public enum PathStatus {
        /** Nothing typed; the field's own prompt says what that means. */
        EMPTY,
        OK,
        NOT_FOUND,
        NOT_EXECUTABLE,
        NOT_A_DIRECTORY
    }

    /** The shell field: a path to one executable. */
    public static PathStatus validateShellPath(String raw) {
        return validateShellPath(raw, Files::exists, Files::isExecutable);
    }

    static PathStatus validateShellPath(String raw, Predicate<Path> exists, Predicate<Path> isExecutable) {
        if (raw == null || raw.isBlank()) return PathStatus.EMPTY;
        Path path;
        try {
            path = Path.of(raw.trim());
        } catch (RuntimeException e) {
            return PathStatus.NOT_FOUND;
        }
        if (!exists.test(path)) return PathStatus.NOT_FOUND;
        return isExecutable.test(path) ? PathStatus.OK : PathStatus.NOT_EXECUTABLE;
    }

    /**
     * A profile's command line: only its program is checked, and a bare name is looked for on the
     * path the way the shell would look for it.
     */
    public static PathStatus validateCommand(String commandLine) {
        return validateCommand(commandLine, System::getenv, Files::exists, Files::isExecutable);
    }

    static PathStatus validateCommand(
            String commandLine, Function<String, String> env, Predicate<Path> exists, Predicate<Path> isExecutable) {
        List<String> argv = Argv.split(commandLine == null ? "" : commandLine);
        if (argv.isEmpty()) return PathStatus.EMPTY;
        String program = argv.get(0);
        boolean bareName = !program.contains("/") && !program.contains("\\");
        if (bareName) {
            // Windows resolves `wsl` to `wsl.exe` for you; a field that says "not found" for the
            // spelling every Windows user types would be worse than no check at all.
            boolean found = findOnPath(program, env, isExecutable) != null
                    || (isWindows() && findOnPath(program + ".exe", env, isExecutable) != null);
            return found ? PathStatus.OK : PathStatus.NOT_FOUND;
        }
        return validateShellPath(program, exists, isExecutable);
    }

    /** A profile's starting directory. */
    public static PathStatus validateDirectory(String raw) {
        return validateDirectory(raw, Files::exists, Files::isDirectory);
    }

    static PathStatus validateDirectory(String raw, Predicate<Path> exists, Predicate<Path> isDirectory) {
        if (raw == null || raw.isBlank()) return PathStatus.EMPTY;
        Path path;
        try {
            path = Path.of(raw.trim());
        } catch (RuntimeException e) {
            return PathStatus.NOT_FOUND;
        }
        if (!exists.test(path)) return PathStatus.NOT_FOUND;
        return isDirectory.test(path) ? PathStatus.OK : PathStatus.NOT_A_DIRECTORY;
    }

    // ---------------------------------------------------------------- environment

    /** Environment for the child: the parent's, plus the terminal identity it needs. */
    public static Map<String, String> environment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", TERM);
        // Signals to shells and prompt frameworks that a full-featured terminal is present.
        env.put("COLORTERM", "truecolor");
        env.put("TERM_PROGRAM", "Termina");
        if (!isWindows()) ensureUtf8Locale(env);
        // Inherited from our own launch; meaningless to the child and confusing if it re-execs.
        env.remove("_");
        return env;
    }

    /**
     * Gives the child a UTF-8 locale when nothing has chosen one.
     *
     * <p>Without one the child emits Latin-1 and every non-ASCII glyph is mojibake. Only filled in
     * when the user has expressed no preference at all — {@code LC_ALL}, {@code LC_CTYPE} and
     * {@code LANG} are the three variables that decide the character set, in that order, and any
     * one of them set means someone decided. {@code C.UTF-8} rather than {@code en_US.UTF-8}: the
     * latter has to have been generated, and on a minimal image it has not been, so bash printed a
     * {@code setlocale} warning at every prompt. {@code C.UTF-8} ships with glibc and with macOS.
     */
    static void ensureUtf8Locale(Map<String, String> env) {
        if (env.get("LC_ALL") != null || env.get("LC_CTYPE") != null || env.get("LANG") != null) return;
        env.put("LANG", "C.UTF-8");
    }

    /** Starts the shell on a PTY sized to {@code columns} x {@code rows}. */
    public static PtyProcess start(int columns, int rows) throws IOException {
        return start(columns, rows, "");
    }

    public static PtyProcess start(int columns, int rows, String shellOverride) throws IOException {
        return start(columns, rows, LaunchOptions.ofShell(shellOverride));
    }

    public static PtyProcess start(int columns, int rows, LaunchOptions options) throws IOException {
        // An explicit command replaces the shell rather than running inside it: `termina -e vim`
        // should be vim, not a shell that happens to have started vim, so quitting it ends the
        // terminal the way every other terminal behaves.
        List<String> command = options.hasCommand() ? options.command() : shellCommand(options.shell(), true);
        return new PtyProcessBuilder(command.toArray(String[]::new))
                .setEnvironment(environment())
                .setDirectory(workingDirectory(options.workingDirectory()))
                .setInitialColumns(columns)
                .setInitialRows(rows)
                // false = a real PTY. Console mode is Windows' "attach to a console screen buffer"
                // path, which does not deliver the escape sequences the emulator needs.
                .setConsole(false)
                .setUseWinConPty(true)
                .setWindowsAnsiColorEnabled(true)
                .start();
    }
}
