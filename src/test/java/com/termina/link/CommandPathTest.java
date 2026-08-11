package com.termina.link;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding the program a configured command names. */
class CommandPathTest {

    /** Stands in for the filesystem, so the rule is the only thing under test. */
    private static java.util.function.Predicate<Path> exists(String... paths) {
        Set<String> present = Set.of(paths);
        return path -> present.contains(path.toString());
    }

    @Test
    void aBareNameIsLookedUpInOrder() {
        // First match wins, which is what a shell does — the earlier directory is the one the user
        // put first for a reason.
        Path found = CommandPath.resolve(
                "editora",
                List.of(Path.of("/usr/bin"), Path.of("/opt/homebrew/bin"), Path.of("/home/dev/.local/bin")),
                exists("/opt/homebrew/bin/editora", "/home/dev/.local/bin/editora"));
        assertEquals(Path.of("/opt/homebrew/bin/editora"), found);
    }

    @Test
    void aCommandThatIsAlreadyAPathIsUsedAsGiven() {
        String app = "/Applications/Editora.app/Contents/MacOS/Editora";
        assertEquals(Path.of(app), CommandPath.resolve(app, List.of(), exists(app)));
    }

    @Test
    void aPathThatIsNotRunnableResolvesToNothing() {
        // Naming a file that is not a program is a different mistake from naming nothing, and both
        // have to end as "no" rather than as an argv nobody can launch.
        assertNull(CommandPath.resolve("/Applications/Editora.app", List.of(), exists("/usr/bin/true")));
    }

    @Test
    void anUnknownNameResolvesToNothing() {
        assertNull(CommandPath.resolve("editora", List.of(Path.of("/usr/bin")), exists("/usr/bin/vi")));
        assertNull(CommandPath.resolve("", List.of(Path.of("/usr/bin")), exists("/usr/bin/editora")));
        assertNull(CommandPath.resolve(null, List.of(), exists()));
    }

    @Test
    void aSeparatorIsWhatMakesSomethingAPath() {
        assertTrue(CommandPath.looksLikePath("/usr/bin/editora"));
        assertTrue(CommandPath.looksLikePath("./editora"));
        assertTrue(CommandPath.looksLikePath("C:\\Program Files\\Editora\\editora.exe"));
        assertFalse(CommandPath.looksLikePath("editora"));
        assertFalse(CommandPath.looksLikePath(null));
    }

    @Test
    void blankPathEntriesAreDroppedRatherThanReadAsTheCurrentDirectory() {
        // An empty entry means "here" to a shell, and "here" for a GUI process is wherever it was
        // launched from — which is not somewhere to be looking for programs.
        String path = "/usr/bin" + java.io.File.pathSeparator + java.io.File.pathSeparator + "/bin";
        assertEquals(List.of(Path.of("/usr/bin"), Path.of("/bin")), CommandPath.entries(path));
        assertEquals(List.of(), CommandPath.entries(""));
        assertEquals(List.of(), CommandPath.entries(null));
    }

    @Test
    void thePathIsReadFromBetweenTheMarkers() {
        // A login shell prints whatever the user's profile prints, and the PATH has to be told apart
        // from the greeting, the version notice and the fortune.
        String output = "Welcome back!\nnvm: using v22\n__P__/usr/bin:/opt/homebrew/bin__P__";
        assertEquals("/usr/bin:/opt/homebrew/bin", CommandPath.extractMarked(output, "__P__"));
    }

    @Test
    void outputWithNoMarkersYieldsNothing() {
        assertEquals("", CommandPath.extractMarked("command not found", "__P__"));
        assertEquals("", CommandPath.extractMarked("__P__unterminated", "__P__"));
        assertEquals("", CommandPath.extractMarked(null, "__P__"));
    }
}
