package com.termina.shell;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What shells are installed on this machine.
 *
 * <p>Discovery rather than configuration: someone who has just installed PowerShell 7 or a second
 * WSL distribution should find it in the menu, not have to describe it in a settings file first.
 * Nothing found here is ever written down — the list is re-derived at each launch, so uninstalling
 * a shell removes it rather than leaving an entry that fails to start.
 *
 * <p>The probes are parameters so both platform branches can be tested from either platform. That
 * matters more than usual here: the Windows half is the half this feature exists for, and it is the
 * half that cannot be run on the machine it was written on.
 */
public final class ShellDiscovery {

    private ShellDiscovery() {}

    /** How long {@code wsl.exe --list} gets before it is abandoned. */
    private static final long WSL_TIMEOUT_SECONDS = 5;

    /**
     * Pseudo-distributions WSL lists but nobody wants a terminal in. Docker Desktop registers two
     * of its own; Rancher Desktop does the same. Windows Terminal hides exactly these.
     */
    private static final Set<String> WSL_INTERNAL =
            Set.of("docker-desktop", "docker-desktop-data", "rancher-desktop-data");

    /**
     * The Unix shells worth looking for, in the order they should appear.
     *
     * <p>The login flag is per shell rather than a blanket {@code -l}. Every shell in the POSIX
     * family, plus fish and tcsh, takes it; the newer ones vary, and passing an option a shell does
     * not understand turns a menu entry into a tab that prints usage and exits — which reads as the
     * shell being broken rather than as us having guessed.
     */
    private static final List<UnixShell> UNIX_SHELLS = List.of(
            new UnixShell("zsh", "Zsh", List.of("-l")),
            new UnixShell("bash", "Bash", List.of("-l")),
            new UnixShell("fish", "Fish", List.of("-l")),
            new UnixShell("nu", "Nushell", List.of()),
            new UnixShell("elvish", "Elvish", List.of()),
            new UnixShell("xonsh", "Xonsh", List.of()),
            new UnixShell("ksh", "Ksh", List.of("-l")),
            new UnixShell("tcsh", "Tcsh", List.of("-l")),
            new UnixShell("dash", "Dash", List.of("-l")),
            new UnixShell("sh", "Sh", List.of("-l")));

    private record UnixShell(String executable, String name, List<String> loginArgs) {}

    /** Directories to look in beyond {@code PATH}, where package managers put shells. */
    private static final List<String> UNIX_EXTRA_DIRECTORIES =
            List.of("/bin", "/usr/bin", "/usr/local/bin", "/opt/homebrew/bin", "/opt/local/bin", "/usr/pkg/bin");

    // ---------------------------------------------------------------- entry point

    /**
     * Everything installed, for the platform this is running on.
     *
     * <p>Runs a subprocess on Windows ({@code wsl.exe --list}) and reads files everywhere else, so
     * it belongs on a background thread. {@link ShellProfiles} is what arranges that.
     */
    public static List<Profile> discover() {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Predicate<Path> executable = Files::isExecutable;
        if (windows) {
            return onWindows(System::getenv, executable, ShellDiscovery::wslDistributions);
        }
        return onUnix(System::getenv, executable, ShellDiscovery::readEtcShells);
    }

    // ---------------------------------------------------------------- Windows

    /**
     * Command Prompt, both PowerShells, Git Bash and every WSL distribution.
     *
     * <p>PowerShell 7 is listed before Windows PowerShell 5: it is the one someone installed on
     * purpose, and both are present on any machine that has it.
     */
    static List<Profile> onWindows(
            Function<String, String> env, Predicate<Path> isExecutable, Supplier<List<String>> wslDistributions) {
        List<Profile> found = new ArrayList<>();
        String systemRoot = orDefault(env.apply("SystemRoot"), "C:\\Windows");
        String programFiles = orDefault(env.apply("ProgramFiles"), "C:\\Program Files");

        // -NoLogo on both: the banner is three lines of copyright at the top of every new tab.
        Path pwsh = firstExisting(
                isExecutable,
                onPath(env, isExecutable, "pwsh.exe"),
                Path.of(programFiles, "PowerShell", "7", "pwsh.exe"));
        if (pwsh != null) {
            found.add(Profile.of("pwsh", "PowerShell", List.of(pwsh.toString(), "-NoLogo"), Profile.Source.DISCOVERED));
        }

        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (isExecutable.test(powershell)) {
            found.add(Profile.of(
                    "powershell",
                    "Windows PowerShell",
                    List.of(powershell.toString(), "-NoLogo"),
                    Profile.Source.DISCOVERED));
        }

        Path cmd = firstExisting(
                isExecutable, pathOrNull(env.apply("COMSPEC")), Path.of(systemRoot, "System32", "cmd.exe"));
        if (cmd != null) {
            found.add(Profile.of("cmd", "Command Prompt", List.of(cmd.toString()), Profile.Source.DISCOVERED));
        }

        // --login -i, which is what Git for Windows' own shortcut passes: without the login half the
        // shell starts outside the MSYS environment and half of what Git Bash is for is missing.
        Path gitBash = firstExisting(
                isExecutable,
                Path.of(programFiles, "Git", "bin", "bash.exe"),
                Path.of(orDefault(env.apply("ProgramFiles(x86)"), "C:\\Program Files (x86)"), "Git", "bin", "bash.exe"),
                Path.of(orDefault(env.apply("LOCALAPPDATA"), ""), "Programs", "Git", "bin", "bash.exe"));
        if (gitBash != null) {
            found.add(Profile.of(
                    "git-bash", "Git Bash", List.of(gitBash.toString(), "--login", "-i"), Profile.Source.DISCOVERED));
        }

        for (String distribution : wslDistributions.get()) {
            // -d names the distribution explicitly rather than relying on the default, so the entry
            // keeps meaning the same thing after someone runs `wsl --set-default`.
            found.add(Profile.of(
                    "wsl-" + slug(distribution),
                    distribution + " (WSL)",
                    List.of("wsl.exe", "-d", distribution),
                    Profile.Source.DISCOVERED));
        }
        return List.copyOf(found);
    }

    /**
     * The installed WSL distributions, or nothing at all.
     *
     * <p>Every failure here is ordinary and none of them is worth reporting: WSL is not installed,
     * the feature is disabled, no distribution has been set up, the call hangs. All of them mean the
     * same thing to the menu — no WSL entries — and none of them should keep the rest of the list
     * from being built.
     */
    private static List<String> wslDistributions() {
        try {
            Process process = new ProcessBuilder("wsl.exe", "--list", "--quiet")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] output;
            try (InputStream in = process.getInputStream()) {
                output = readAll(in);
            }
            if (!process.waitFor(WSL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            // Not gated on the exit code: `wsl --list` reports a non-zero status when there are no
            // distributions, and parsing an empty answer already gives the right result.
            return parseWslDistributions(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Reads the distribution names out of {@code wsl.exe --list --quiet}.
     *
     * <p><b>The output is UTF-16LE, not UTF-8.</b> {@code wsl.exe} writes wide characters even when
     * its output is redirected, and read as UTF-8 every name comes back with a NUL between each of
     * its letters — which survives far enough to become a menu entry that starts nothing. Newer
     * builds have been reported writing UTF-8 instead, so the encoding is sniffed rather than
     * assumed: a NUL in the first few bytes means wide, and no real distribution name contains one.
     */
    static List<String> parseWslDistributions(byte[] output) {
        if (output == null || output.length == 0) return List.of();
        String text = decode(output);
        List<String> distributions = new ArrayList<>();
        for (String line : text.split("\\R")) {
            // \u0000 as well as whitespace: a wide string decoded as UTF-8 by a caller that got the
            // sniff wrong would otherwise arrive here full of them.
            String name = line.replace("\u0000", "").trim();
            if (name.isEmpty()) continue;
            if (WSL_INTERNAL.contains(name.toLowerCase(Locale.ROOT))) continue;
            if (!distributions.contains(name)) distributions.add(name);
        }
        return List.copyOf(distributions);
    }

    /** UTF-16LE when the bytes look wide, UTF-8 otherwise. A BOM is honoured before either. */
    private static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        // ASCII in UTF-16LE puts a NUL in every second byte, so one in the first handful settles it.
        int limit = Math.min(bytes.length, 16);
        for (int i = 1; i < limit; i += 2) {
            if (bytes[i] == 0) return new String(bytes, StandardCharsets.UTF_16LE);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        in.transferTo(buffer);
        return buffer.toByteArray();
    }

    // ---------------------------------------------------------------- macOS and Linux

    /**
     * The shells named in {@code /etc/shells}, plus the ones found on the path.
     *
     * <p>Both sources, because neither is complete on its own: {@code /etc/shells} is the list of
     * shells an account may be given and is what a package manager appends to, while a shell
     * installed into a home directory or a Homebrew prefix may never reach it.
     *
     * <p><b>One entry per shell, not per binary.</b> A machine with Homebrew has two zsh
     * executables and a menu offering "Zsh" twice with no way to tell them apart is worse than one
     * that picks the first. Someone who wants the other one adds a profile naming it.
     */
    static List<Profile> onUnix(
            Function<String, String> env, Predicate<Path> isExecutable, Supplier<List<String>> etcShells) {
        // Insertion-ordered and keyed by executable name, which is what enforces one entry each.
        Map<String, Profile> byExecutable = new LinkedHashMap<>();

        List<Path> candidates = new ArrayList<>();
        for (String line : etcShells.get()) {
            Path path = pathOrNull(line);
            if (path != null) candidates.add(path);
        }
        for (UnixShell shell : UNIX_SHELLS) {
            Path onPath = onPath(env, isExecutable, shell.executable());
            if (onPath != null) candidates.add(onPath);
            for (String directory : UNIX_EXTRA_DIRECTORIES) {
                candidates.add(Path.of(directory, shell.executable()));
            }
        }

        Set<Path> seen = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            if (!seen.add(candidate) || !isExecutable.test(candidate)) continue;
            Path fileName = candidate.getFileName();
            if (fileName == null) continue;
            UnixShell shell = unixShell(fileName.toString());
            if (shell == null || byExecutable.containsKey(shell.executable())) continue;
            List<String> command = new ArrayList<>();
            command.add(candidate.toString());
            command.addAll(shell.loginArgs());
            byExecutable.put(
                    shell.executable(),
                    Profile.of("shell-" + shell.executable(), shell.name(), command, Profile.Source.DISCOVERED));
        }

        // Back into the declared order: candidates arrive in whatever order /etc/shells and the
        // path happen to hold them, and the menu should read the same on every machine.
        List<Profile> ordered = new ArrayList<>();
        for (UnixShell shell : UNIX_SHELLS) {
            Profile profile = byExecutable.get(shell.executable());
            if (profile != null) ordered.add(profile);
        }
        return List.copyOf(ordered);
    }

    private static UnixShell unixShell(String executable) {
        for (UnixShell shell : UNIX_SHELLS) {
            if (shell.executable().equals(executable)) return shell;
        }
        return null;
    }

    /** {@code /etc/shells}, comments and blanks removed. Missing on some systems; that is fine. */
    private static List<String> readEtcShells() {
        Path file = Path.of("/etc/shells");
        if (!Files.isReadable(file)) return List.of();
        try {
            List<String> entries = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                entries.add(trimmed);
            }
            return List.copyOf(entries);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- shared helpers

    /** An id-safe form of a name, so a WSL distribution can key a setting and a chord. */
    static String slug(String name) {
        StringBuilder slug = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) slug.append(c);
            else if (!slug.isEmpty() && slug.charAt(slug.length() - 1) != '-') slug.append('-');
        }
        while (!slug.isEmpty() && slug.charAt(slug.length() - 1) == '-') slug.setLength(slug.length() - 1);
        return slug.toString();
    }

    private static Path onPath(Function<String, String> env, Predicate<Path> isExecutable, String executable) {
        String path = env.apply("PATH");
        if (path == null || path.isBlank()) return null;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory, executable);
            if (isExecutable.test(candidate)) return candidate;
        }
        return null;
    }

    private static Path firstExisting(Predicate<Path> isExecutable, Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && isExecutable.test(candidate)) return candidate;
        }
        return null;
    }

    private static Path pathOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Path.of(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
