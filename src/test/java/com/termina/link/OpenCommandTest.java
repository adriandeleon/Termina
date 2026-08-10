package com.termina.link;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Turning a configured command into an argv, and the desktop opener into one. */
class OpenCommandTest {

    /**
     * The file as this platform writes it.
     *
     * <p>A substituted path is whatever {@code Path.toString()} gives, which is the form the command
     * being launched expects — and on Windows that is backslashes. Writing the expectation as a
     * literal would pin a POSIX separator and fail there, having tested nothing about the code.
     */
    private static String native_(String posix) {
        return Path.of(posix).toString();
    }

    @Test
    void substitutesTheFileAndPosition() {
        assertEquals(
                List.of("editora", native_("/src/Foo.java") + ":42:7"),
                OpenCommand.forTemplate("editora {file}:{line}:{column}", Path.of("/src/Foo.java"), 42, 7));
    }

    @Test
    void aPathWithSpacesStaysOneArgument() {
        // The reason the template is split before the substitution and not after: no quoting the
        // user could write would survive being torn apart here, because which file they click
        // decides where the spaces are.
        assertEquals(
                List.of("editora", native_("/My Files/a b.txt") + ":1"),
                OpenCommand.forTemplate("editora {file}:{line}", Path.of("/My Files/a b.txt"), 0, 0));
    }

    @Test
    void aMissingPositionBecomesLineOne() {
        // Not 0, which is a line no file has, and not empty, which leaves a dangling colon that
        // several editors take for part of the filename.
        assertEquals(
                List.of("code", "-g", native_("/src/Foo.java") + ":1:1"),
                OpenCommand.forTemplate("code -g {file}:{line}:{column}", Path.of("/src/Foo.java"), 0, 0));
    }

    @Test
    void noTemplateMeansNoCommand() {
        assertTrue(OpenCommand.forTemplate("", Path.of("/a"), 1, 1).isEmpty());
        assertTrue(OpenCommand.forTemplate("   ", Path.of("/a"), 1, 1).isEmpty());
        assertTrue(OpenCommand.forTemplate(null, Path.of("/a"), 1, 1).isEmpty());
        assertTrue(OpenCommand.forTemplate("editora {file}", null, 1, 1).isEmpty());
    }

    @Test
    void quotesInTheTemplateGroupItsOwnArguments() {
        assertEquals(
                List.of("/Applications/My Editor.app/Contents/MacOS/edit", native_("/a")),
                OpenCommand.forTemplate(
                        "'/Applications/My Editor.app/Contents/MacOS/edit' {file}", Path.of("/a"), 0, 0));
    }

    @Test
    void theDesktopOpenerIsPerPlatform() {
        assertEquals(List.of("open", "https://x"), OpenCommand.systemOpen("Mac OS X", "https://x"));
        assertEquals(List.of("xdg-open", "https://x"), OpenCommand.systemOpen("Linux", "https://x"));
        // The empty title is not a typo: without it `start` reads the target as the window title.
        assertEquals(List.of("cmd", "/c", "start", "", "https://x"), OpenCommand.systemOpen("Windows 11", "https://x"));
    }

    @Test
    void nothingToOpenIsNoCommand() {
        assertTrue(OpenCommand.systemOpen("Linux", "").isEmpty());
        assertTrue(OpenCommand.systemOpen("Linux", null).isEmpty());
    }
}
