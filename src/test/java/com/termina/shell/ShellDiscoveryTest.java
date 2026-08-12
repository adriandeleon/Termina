package com.termina.shell;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows half of this is the half the feature exists for and the half that cannot be run on
 * the machine it was written on, so every branch of it is driven here with the probes replaced.
 */
class ShellDiscoveryTest {

    /**
     * Pretends the named paths exist and nothing else does.
     *
     * <p>Takes {@link Path}s built the same way the code builds its candidates, not strings.
     * {@code Path.of("C:\\Windows", "System32", "cmd.exe")} renders with backslashes on Windows and
     * forward slashes everywhere else, so a Windows-shaped string literal here never matches on the
     * machine this is being run on — which reads as discovery finding nothing rather than as the
     * test comparing two spellings of the same path.
     */
    private static Predicate<Path> present(Path... paths) {
        Set<Path> set = Set.of(paths);
        return set::contains;
    }

    private static java.util.function.Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    // ---------------------------------------------------------------- WSL output

    @Test
    @DisplayName("wsl --list output is UTF-16, and reading it as UTF-8 is the mistake to catch")
    void parsesUtf16WslOutput() {
        byte[] wide = "Ubuntu\r\nDebian\r\n".getBytes(StandardCharsets.UTF_16LE);
        assertEquals(List.of("Ubuntu", "Debian"), ShellDiscovery.parseWslDistributions(wide));
    }

    @Test
    @DisplayName("a byte-order mark is consumed rather than becoming part of the first name")
    void handlesByteOrderMark() {
        byte[] withBom = new byte[] {(byte) 0xFF, (byte) 0xFE};
        byte[] body = "Ubuntu\r\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] all = new byte[withBom.length + body.length];
        System.arraycopy(withBom, 0, all, 0, withBom.length);
        System.arraycopy(body, 0, all, withBom.length, body.length);
        assertEquals(List.of("Ubuntu"), ShellDiscovery.parseWslDistributions(all));
    }

    @Test
    @DisplayName("a build that writes UTF-8 instead is read as UTF-8")
    void handlesUtf8Output() {
        byte[] narrow = "Ubuntu\nDebian\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(List.of("Ubuntu", "Debian"), ShellDiscovery.parseWslDistributions(narrow));
    }

    @Test
    @DisplayName("Docker and Rancher's internal distributions are not shells anybody wants")
    void hidesInternalDistributions() {
        byte[] wide = "Ubuntu\r\ndocker-desktop\r\ndocker-desktop-data\r\n".getBytes(StandardCharsets.UTF_16LE);
        assertEquals(List.of("Ubuntu"), ShellDiscovery.parseWslDistributions(wide));
    }

    @Test
    @DisplayName("no WSL, or no distributions, is an empty list rather than a failure")
    void toleratesNoDistributions() {
        assertEquals(List.of(), ShellDiscovery.parseWslDistributions(new byte[0]));
        assertEquals(List.of(), ShellDiscovery.parseWslDistributions(null));
        assertEquals(List.of(), ShellDiscovery.parseWslDistributions("\r\n\r\n".getBytes(StandardCharsets.UTF_16LE)));
    }

    // ---------------------------------------------------------------- Windows

    private static final Path CMD = Path.of("C:\\Windows", "System32", "cmd.exe");
    private static final Path POWERSHELL =
            Path.of("C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    private static final Path PWSH = Path.of("C:\\Program Files", "PowerShell", "7", "pwsh.exe");
    private static final Path GIT_BASH = Path.of("C:\\Program Files", "Git", "bin", "bash.exe");

    @Test
    @DisplayName("a stock Windows box offers PowerShell and Command Prompt")
    void findsWindowsShells() {
        var env = env(Map.of("SystemRoot", "C:\\Windows", "COMSPEC", CMD.toString()));
        var isExecutable = present(CMD, POWERSHELL);

        List<Profile> found = ShellDiscovery.onWindows(env, isExecutable, List::of);

        assertEquals(
                List.of("powershell", "cmd"), found.stream().map(Profile::id).toList());
        assertEquals("Windows PowerShell", found.get(0).name());
        assertTrue(found.get(0).command().contains("-NoLogo"), "the banner is three lines per tab");
    }

    @Test
    @DisplayName("PowerShell 7 comes before Windows PowerShell — it is the one installed on purpose")
    void prefersPowerShellSeven() {
        // No PATH: on a POSIX machine the path separator is a colon, and a Windows PATH entry
        // begins "C:", so a PATH-based probe here would be testing the split rather than the rule.
        // The Program Files fallback is the same branch and is what most machines answer with.
        var env = env(Map.of("SystemRoot", "C:\\Windows", "ProgramFiles", "C:\\Program Files"));
        var isExecutable = present(PWSH, POWERSHELL);

        List<Profile> found = ShellDiscovery.onWindows(env, isExecutable, List::of);

        assertEquals(
                List.of("pwsh", "powershell"), found.stream().map(Profile::id).toList());
    }

    @Test
    @DisplayName("each WSL distribution becomes a profile that names it explicitly")
    void findsWslDistributions() {
        var env = env(Map.of("SystemRoot", "C:\\Windows"));
        var isExecutable = present(CMD);

        List<Profile> found = ShellDiscovery.onWindows(env, isExecutable, () -> List.of("Ubuntu", "Arch Linux"));

        Profile ubuntu = byId(found, "wsl-ubuntu");
        assertNotNull(ubuntu);
        // -d, so the entry keeps meaning the same distribution after `wsl --set-default`.
        assertEquals(List.of("wsl.exe", "-d", "Ubuntu"), ubuntu.command());
        assertEquals("Ubuntu (WSL)", ubuntu.name());
        // A space in the name must not become a space in the id, which keys a setting.
        assertNotNull(byId(found, "wsl-arch-linux"));
    }

    @Test
    @DisplayName("Git Bash is started as a login shell, or it is not the MSYS environment")
    void findsGitBash() {
        var env = env(Map.of("SystemRoot", "C:\\Windows", "ProgramFiles", "C:\\Program Files"));
        var isExecutable = present(GIT_BASH);

        Profile gitBash = byId(ShellDiscovery.onWindows(env, isExecutable, List::of), "git-bash");

        assertNotNull(gitBash);
        assertTrue(gitBash.command().containsAll(List.of("--login", "-i")));
    }

    @Test
    @DisplayName("a machine where nothing is found produces nothing, not a broken entry")
    void windowsFindsNothingCleanly() {
        assertEquals(List.of(), ShellDiscovery.onWindows(env(Map.of()), path -> false, List::of));
    }

    // ---------------------------------------------------------------- Unix

    @Test
    @DisplayName("/etc/shells and the path are both read, and the login flag is per shell")
    void findsUnixShells() {
        var env = env(Map.of("PATH", "/usr/bin"));
        var isExecutable = present(
                Path.of("/bin/zsh"), Path.of("/bin/bash"), Path.of("/opt/homebrew/bin/fish"), Path.of("/usr/bin/nu"));

        List<Profile> found = ShellDiscovery.onUnix(env, isExecutable, () -> List.of("/bin/zsh", "/bin/bash"));

        assertEquals(
                List.of("shell-zsh", "shell-bash", "shell-fish", "shell-nu"),
                found.stream().map(Profile::id).toList(),
                "the declared order, not the order the filesystem happened to answer in");
        assertEquals(List.of("/bin/zsh", "-l"), byId(found, "shell-zsh").command());
        // nu has no -l on every version that ships it, and a shell handed an option it does not
        // understand prints usage and exits — which reads as the menu entry being broken.
        assertEquals(List.of("/usr/bin/nu"), byId(found, "shell-nu").command());
    }

    @Test
    @DisplayName("two zsh binaries are one Zsh entry, not two identical ones")
    void onlyOneEntryPerShell() {
        var env = env(Map.of("PATH", "/opt/homebrew/bin"));
        var isExecutable = present(Path.of("/bin/zsh"), Path.of("/opt/homebrew/bin/zsh"));

        List<Profile> found = ShellDiscovery.onUnix(env, isExecutable, () -> List.of("/bin/zsh"));

        assertEquals(1, found.size());
        assertEquals("/bin/zsh", found.get(0).command().get(0), "the one /etc/shells named");
    }

    @Test
    @DisplayName("a shell listed in /etc/shells but since uninstalled is left out")
    void skipsMissingBinaries() {
        List<Profile> found = ShellDiscovery.onUnix(env(Map.of()), path -> false, () -> List.of("/usr/local/bin/fish"));
        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("an unrecognised entry in /etc/shells is ignored rather than guessed at")
    void ignoresUnknownShells() {
        var isExecutable = present(Path.of("/usr/bin/screen"));
        List<Profile> found = ShellDiscovery.onUnix(env(Map.of()), isExecutable, () -> List.of("/usr/bin/screen"));
        assertTrue(found.isEmpty(), "we do not know how to start it as a login shell");
    }

    // ---------------------------------------------------------------- ids

    @Test
    @DisplayName("an id carries no spaces or punctuation: it keys a setting and a chord")
    void slugsAreIdSafe() {
        assertEquals("ubuntu-22-04", ShellDiscovery.slug("Ubuntu-22.04"));
        assertEquals("arch-linux", ShellDiscovery.slug("Arch Linux"));
        assertEquals("opensuse-tumbleweed", ShellDiscovery.slug("openSUSE Tumbleweed"));
        assertFalse(ShellDiscovery.slug("Ubuntu ").endsWith("-"));
    }

    private static Profile byId(List<Profile> profiles, String id) {
        return profiles.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }
}
