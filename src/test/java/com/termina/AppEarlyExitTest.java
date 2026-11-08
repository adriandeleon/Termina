package com.termina;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.termina.cli.CommandLine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a command line that opens no window prints and exits with. */
class AppEarlyExitTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private Integer run(String... args) {
        return App.earlyExit(
                CommandLine.parse(args),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void helpVersionAndAMistakeEachExitAndALaunchDoesNot() {
        assertEquals(0, run("--help"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("--version"), out.toString(StandardCharsets.UTF_8));
        out.reset();
        assertEquals(0, run("--version"));
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith(AppInfo.NAME + " "));
        assertEquals(2, run("--no-such-option"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--help"));
        assertNull(run(), "an ordinary launch goes on to open a window");
    }
}
