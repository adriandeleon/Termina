package com.termina.pty;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing the shell to launch.
 *
 * <p>The path with the least margin for error in the whole application: get it wrong and there is
 * no terminal at all, which is not a degraded experience but a blank window.
 */
class ShellLauncherTest {

    @Test
    void aConfiguredShellIsUsed() {
        // Derived from this platform rather than hardcoded: the first version asserted /bin/sh and
        // CI caught it on Windows, where that is not executable so the override is ignored and the
        // fallback chain returns pwsh. Asking for whatever this machine would have picked anyway
        // keeps the override branch covered everywhere instead of skipping a third of the matrix.
        String available = ShellLauncher.shellCommand().get(0);
        assertEquals(available, ShellLauncher.shellCommand(available).get(0));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aConfiguredShellIsStartedAsALoginShell() {
        // Without -l the user's profile never runs: no PATH, no prompt, no aliases — and it looks
        // like the shell is broken rather than like it was started oddly.
        assertEquals(List.of("/bin/sh", "-l"), ShellLauncher.shellCommand("/bin/sh"));
    }

    @Test
    void aShellThatIsNotThereIsIgnoredRatherThanLaunched() {
        // A stale settings entry — a shell uninstalled, or a config synced from another machine —
        // must not leave someone unable to open a terminal at all.
        List<String> command = ShellLauncher.shellCommand("/nonexistent/shell/that/is/not/there");
        assertFalse(command.get(0).contains("nonexistent"), command.toString());
        assertFalse(command.isEmpty());
    }

    @Test
    void aBlankOverrideFallsBackToTheUsersOwnShell() {
        assertEquals(ShellLauncher.shellCommand(), ShellLauncher.shellCommand(""));
        assertEquals(ShellLauncher.shellCommand(), ShellLauncher.shellCommand("   "));
        assertEquals(ShellLauncher.shellCommand(), ShellLauncher.shellCommand(null));
    }

    @Test
    void thereIsAlwaysSomethingToLaunch() {
        List<String> command = ShellLauncher.shellCommand();
        assertFalse(command.isEmpty());
        assertFalse(command.get(0).isBlank());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theFallbackShellExistsOnThisMachine() {
        // The fallbacks are hardcoded paths. If one of them is wrong for this platform the app
        // opens a window with nothing in it.
        assertTrue(
                java.nio.file.Files.isExecutable(
                        java.nio.file.Path.of(ShellLauncher.shellCommand().get(0))),
                "not executable: " + ShellLauncher.shellCommand());
    }

    @Test
    void theEnvironmentDeclaresATerminalTypeAndKeepsThePath() {
        Map<String, String> env = ShellLauncher.environment();
        // Without TERM, ncurses programs — vim, less, top — refuse to start or draw nothing.
        assertTrue(env.containsKey("TERM"), env.keySet().toString());
        assertFalse(env.get("TERM").isBlank());
        assertTrue(env.containsKey("PATH"), "the child shell needs a PATH to find anything");
    }

    @Test
    void aChildWithNoLocaleGetsOneThatAlwaysExists() {
        // en_US.UTF-8 has to have been generated; on a minimal image it has not been, and bash
        // printed a setlocale warning at every prompt. C.UTF-8 ships with glibc.
        Map<String, String> env = new java.util.HashMap<>();
        ShellLauncher.ensureUtf8Locale(env);
        assertEquals("C.UTF-8", env.get("LANG"));
    }

    @Test
    void anyLocaleVariableTheUserSetIsRespected() {
        // LC_ALL, LC_CTYPE and LANG all decide the character set; any one of them means someone
        // chose, and a terminal that overrides a choice is a terminal with a mystery setting.
        for (String variable : java.util.List.of("LC_ALL", "LC_CTYPE", "LANG")) {
            Map<String, String> env = new java.util.HashMap<>(Map.of(variable, "de_DE.ISO-8859-1"));
            ShellLauncher.ensureUtf8Locale(env);
            assertEquals(Map.of(variable, "de_DE.ISO-8859-1"), env, variable);
        }
    }

    // ---------------------------------------------------------------- validation

    private static java.util.function.Predicate<java.nio.file.Path> present(String... paths) {
        java.util.Set<java.nio.file.Path> set = new java.util.HashSet<>();
        for (String p : paths) set.add(java.nio.file.Path.of(p));
        return set::contains;
    }

    @Test
    void theShellFieldIsToldWhyItsPathWillBeIgnored() {
        var exists = present("/bin/zsh", "/etc/passwd");
        var executable = present("/bin/zsh");
        assertEquals(ShellLauncher.PathStatus.EMPTY, ShellLauncher.validateShellPath("  ", exists, executable));
        assertEquals(ShellLauncher.PathStatus.OK, ShellLauncher.validateShellPath("/bin/zsh", exists, executable));
        assertEquals(
                ShellLauncher.PathStatus.NOT_FOUND, ShellLauncher.validateShellPath("/bin/zshh", exists, executable));
        assertEquals(
                ShellLauncher.PathStatus.NOT_EXECUTABLE,
                ShellLauncher.validateShellPath("/etc/passwd", exists, executable));
    }

    @Test
    void aProfileCommandIsJudgedByItsProgramAlone() {
        var exists = present("/usr/bin/ssh", "/opt/tools/wsl.exe");
        var executable = present("/usr/bin/ssh", "/opt/tools/wsl.exe");
        java.util.function.Function<String, String> env = Map.of("PATH", "/usr/bin")::get;
        // A bare name is looked up on the path, the way the shell would.
        assertEquals(
                ShellLauncher.PathStatus.OK,
                ShellLauncher.validateCommand("ssh -t work tmux", env, exists, executable));
        assertEquals(
                ShellLauncher.PathStatus.NOT_FOUND,
                ShellLauncher.validateCommand("wsl -d Ubuntu", env, exists, executable));
        // A path is checked as a path; the arguments after it are not ours to judge.
        assertEquals(
                ShellLauncher.PathStatus.OK,
                ShellLauncher.validateCommand("/opt/tools/wsl.exe -d Ubuntu", env, exists, executable));
        assertEquals(ShellLauncher.PathStatus.EMPTY, ShellLauncher.validateCommand("", env, exists, executable));
    }

    @Test
    void aStartingDirectoryHasToBeOne() {
        var exists = present("/home/me", "/home/me/notes.txt");
        var isDirectory = present("/home/me");
        assertEquals(ShellLauncher.PathStatus.OK, ShellLauncher.validateDirectory("/home/me", exists, isDirectory));
        assertEquals(
                ShellLauncher.PathStatus.NOT_A_DIRECTORY,
                ShellLauncher.validateDirectory("/home/me/notes.txt", exists, isDirectory));
        assertEquals(
                ShellLauncher.PathStatus.NOT_FOUND, ShellLauncher.validateDirectory("/home/you", exists, isDirectory));
        assertEquals(ShellLauncher.PathStatus.EMPTY, ShellLauncher.validateDirectory(null, exists, isDirectory));
    }
}
