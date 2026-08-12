package com.termina.ui;

import java.util.List;

import javafx.scene.Node;

import com.termina.shell.Profile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which mark a profile gets, and whether that mark actually draws. */
class ProfileIconsTest {

    /** An SVGPath is a Node, and constructing one asks the toolkit for the platform stylesheet. */
    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
    }

    private static Profile profile(String name, String... command) {
        return Profile.of(name.toLowerCase(java.util.Locale.ROOT), name, List.of(command), Profile.Source.DISCOVERED);
    }

    @Test
    @DisplayName("every mark parses to a shape with a size")
    void everyGlyphDraws() {
        // The check this class exists for. JavaFX's SVG parser is stricter than a browser's about
        // packed elliptical-arc flags — `a1 1 0 000-.5` rather than `a1 1 0 0 0 -.5` — and the way
        // it fails is silence: the path is rejected, the node has no geometry, and the menu row
        // shows an empty gap where the icon should be. Nothing throws and nothing is logged.
        for (String key : ProfileIcons.keys()) {
            Node node = ProfileIcons.byKey(key);
            assertTrue(node.getBoundsInLocal().getWidth() > 0, key + " has no width");
            assertTrue(node.getBoundsInLocal().getHeight() > 0, key + " has no height");
        }
    }

    @Test
    @DisplayName("a WSL profile is marked by its distribution, not by wsl.exe")
    void wslProfilesTakeTheirDistroMark() {
        // Every WSL entry runs the same executable, so the command cannot tell Ubuntu from Debian.
        assertEquals("ubuntu", ProfileIcons.iconKeyFor(profile("Ubuntu (WSL)", "wsl.exe", "-d", "Ubuntu")));
        assertEquals("debian", ProfileIcons.iconKeyFor(profile("Debian (WSL)", "wsl.exe", "-d", "Debian")));
        assertEquals("arch", ProfileIcons.iconKeyFor(profile("Arch (WSL)", "wsl.exe", "-d", "Arch")));
        assertEquals("opensuse", ProfileIcons.iconKeyFor(profile("openSUSE-Tumbleweed (WSL)", "wsl.exe", "-d", "x")));
    }

    @Test
    @DisplayName("a distribution with no mark of its own falls back to the penguin, not the prompt")
    void unknownDistributionsGetTux() {
        assertEquals("linux", ProfileIcons.iconKeyFor(profile("NixOS (WSL)", "wsl.exe", "-d", "NixOS")));
    }

    @Test
    @DisplayName("unix shells are matched on the executable")
    void shellsTakeTheirOwnMark() {
        assertEquals("bash", ProfileIcons.iconKeyFor(profile("Bash", "/bin/bash", "-l")));
        assertEquals("zsh", ProfileIcons.iconKeyFor(profile("Zsh", "/usr/local/bin/zsh", "-l")));
        assertEquals("fish", ProfileIcons.iconKeyFor(profile("Fish", "/opt/homebrew/bin/fish", "-l")));
        // Git Bash is bash, and its command says so even though its name does not.
        assertEquals(
                "bash", ProfileIcons.iconKeyFor(profile("Git Bash", "C:\\Program Files\\Git\\bin\\bash.exe", "-i")));
    }

    @Test
    @DisplayName("the executable is matched, not the whole line")
    void doesNotMatchAnywhereInTheCommand() {
        // Both of these mention a shell they are not: one as an argument, one as a directory. A
        // substring match over the command line would give each of them the wrong mark.
        assertEquals("prompt", ProfileIcons.iconKeyFor(profile("Wrapper", "/bin/sh", "-c", "bash --version")));
        assertEquals("prompt", ProfileIcons.iconKeyFor(profile("Scratch", "/home/zsh/bin/dash", "-l")));
    }

    @Test
    @DisplayName("Microsoft's shells get the generic prompt, because their marks are not ours to use")
    void windowsShellsGetThePrompt() {
        // PowerShell, Windows and Windows Terminal were all removed from Simple Icons over
        // trademark. A lookalike drawn from memory would be both wrong and a claim we cannot make.
        assertEquals("prompt", ProfileIcons.iconKeyFor(profile("PowerShell", "pwsh.exe", "-NoLogo")));
        assertEquals("prompt", ProfileIcons.iconKeyFor(profile("Command Prompt", "C:\\Windows\\System32\\cmd.exe")));
    }

    @Test
    @DisplayName("nothing at all still gets a mark")
    void nullIsSafe() {
        assertEquals("prompt", ProfileIcons.iconKeyFor(null));
        assertTrue(ProfileIcons.forProfile(null).getBoundsInLocal().getWidth() > 0);
    }
}
