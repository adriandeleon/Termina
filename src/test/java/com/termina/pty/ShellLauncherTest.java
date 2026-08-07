package com.termina.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Choosing the shell to launch.
 *
 * <p>The path with the least margin for error in the whole application: get it wrong and there is
 * no terminal at all, which is not a degraded experience but a blank window.
 */
class ShellLauncherTest {

    @Test
    void aConfiguredShellIsUsed() {
        // /bin/sh exists and is executable on every platform this runs on bar Windows.
        List<String> command = ShellLauncher.shellCommand("/bin/sh");
        assertEquals("/bin/sh", command.get(0));
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
        assertTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(ShellLauncher.shellCommand().get(0))),
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
}
