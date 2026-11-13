package com.termina.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.termina.Os;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * CLI-10 (Pauta 6.0.1): a process the app starts on macOS or Linux is given the login shell's
 * {@code PATH} once it is known, and every place that starts one goes through the one helper.
 */
class ChildPathTest {

    @Test
    void aChildGetsThePublishedPathExceptOnWindowsOrBeforeAnyoneAsked() {
        String inherited = System.getenv("PATH");
        for (Os.Kind unix : List.of(Os.Kind.MAC, Os.Kind.LINUX, Os.Kind.OTHER)) {
            ProcessBuilder builder = new ProcessBuilder("true");
            assertSame(builder, ChildPath.apply(builder, unix, "/usr/bin:/opt/homebrew/bin"));
            assertEquals("/usr/bin:/opt/homebrew/bin", builder.environment().get("PATH"));
        }
        ProcessBuilder windows = new ProcessBuilder("true");
        ChildPath.apply(windows, Os.Kind.WINDOWS, "/usr/bin:/opt/homebrew/bin");
        assertEquals(inherited, windows.environment().get("PATH"), "Windows keeps the inherited one");

        ProcessBuilder early = new ProcessBuilder("true");
        ChildPath.apply(early, Os.Kind.LINUX, null);
        assertEquals(inherited, early.environment().get("PATH"), "nobody has asked the login shell yet");
    }

    /**
     * The rule's own check, on the source: every {@code new ProcessBuilder} is in a file that
     * applies the path, but for the three that are outside it by what they start.
     */
    @Test
    void everyPlaceThatStartsAProcessAppliesIt() throws IOException {
        Set<String> outside = Set.of(
                "LoginShellPath.java", // the question itself: the login shell, asked for its PATH
                "ShellDiscovery.java", // reg.exe and wsl.exe: Windows only
                "GitRunner.java"); // given the same PATH by its caller, with the rest of git's environment
        List<String> bare = new ArrayList<>();
        int starts = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                String name = file.getFileName().toString();
                // A mention in a comment is not a start.
                long made = source.lines()
                        .filter(line -> line.contains("new ProcessBuilder(")
                                && !line.strip().startsWith("*")
                                && !line.strip().startsWith("//"))
                        .count();
                if (made == 0 || name.equals("ChildPath.java")) continue;
                starts += made;
                if (outside.contains(name)) continue;
                long applied = source.lines()
                        .filter(line -> line.contains("ChildPath.apply("))
                        .count();
                if (applied < made) bare.add(name + ": " + made + " started, " + applied + " given the PATH");
            }
        }
        assertEquals(List.of(), bare);
        assertEquals(12, starts, "every place a process is started; a new one is looked at here");
    }
}
