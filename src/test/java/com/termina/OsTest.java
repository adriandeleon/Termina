package com.termina;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OS-02 (Pauta 6.0.1): one class says which system this is, and no other works it out. */
class OsTest {

    @Test
    void anOsNameIsOneOfTheThreeSystemsOrNone() {
        assertEquals(Os.Kind.WINDOWS, Os.of("Windows 11"));
        assertEquals(Os.Kind.WINDOWS, Os.of("windows server 2022"));
        assertEquals(Os.Kind.MAC, Os.of("Mac OS X"));
        assertEquals(Os.Kind.MAC, Os.of("Darwin"));
        assertEquals(Os.Kind.LINUX, Os.of("Linux"));
        assertEquals(Os.Kind.OTHER, Os.of("FreeBSD"));
        assertEquals(Os.Kind.OTHER, Os.of(""));
        assertEquals(Os.Kind.OTHER, Os.of(null));
        // "Darwin" has "win" in it: the spelling three classes used for Windows took it for one.
        assertFalse(Os.of("Darwin") == Os.Kind.WINDOWS);
    }

    @Test
    void theCurrentSystemIsExactlyOneOfThem() {
        int yes = (Os.isWindows() ? 1 : 0) + (Os.isMac() ? 1 : 0) + (Os.isLinux() ? 1 : 0);
        assertEquals(Os.current() == Os.Kind.OTHER ? 0 : 1, yes);
        assertEquals(Os.of(Os.name()), Os.current());
        assertTrue(Os.description().contains(Os.name()), Os.description());
        assertTrue(Os.description().endsWith(")"), Os.description());
    }

    /** OS-03: a test can run as any of them, and giving the override back restores the real one. */
    @Test
    void aTestCanRunAsAnotherSystemAndGiveItBack() {
        Os.Kind real = Os.current();
        try {
            for (Os.Kind as : Os.Kind.values()) {
                Os.runAs(as);
                assertEquals(as, Os.current());
                assertEquals(as == Os.Kind.WINDOWS, Os.isWindows());
                assertEquals(as == Os.Kind.MAC, Os.isMac());
                assertEquals(as == Os.Kind.LINUX, Os.isLinux());
            }
        } finally {
            Os.runAs(null);
        }
        assertEquals(real, Os.current());
    }

    /** The override is the tests': nothing in the app runs as a system it is not on. */
    @Test
    void onlyTestsUseTheOverride() throws IOException {
        List<String> callers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.endsWith("Os.java")) continue;
                if (Files.readString(file).contains("Os.runAs(")) callers.add(file.toString());
            }
        }
        assertEquals(List.of(), callers);
    }

    /** The rule's own check, kept as a test: only {@code Os.java} reads the property or the separators. */
    @Test
    void noOtherClassReadsOsNameOrTellsWindowsByASeparator() throws IOException {
        List<String> others = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.endsWith(Path.of("com", "termina", "Os.java"))) continue;
                String text = Files.readString(file);
                if (text.contains("\"os.name\"")) others.add(file + ": reads os.name");
                if (text.matches("(?s).*(separatorChar|pathSeparatorChar)\\s*[=!]=.*")) {
                    others.add(file + ": compares a separator");
                }
            }
        }
        assertEquals(List.of(), others);
    }
}
