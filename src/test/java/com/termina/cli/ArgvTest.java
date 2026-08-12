package com.termina.cli;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The command line a profile is stored as has to read back as the argv it was written from, or a
 * shell that worked when it was typed stops working the next time the application starts.
 */
class ArgvTest {

    @Test
    @DisplayName("plain words")
    void splitsOnWhitespace() {
        assertEquals(List.of("wsl.exe", "-d", "Ubuntu"), Argv.split("wsl.exe -d Ubuntu"));
        assertEquals(List.of("bash", "-l"), Argv.split("  bash   -l  "));
    }

    @Test
    @DisplayName("a quoted path with a space stays one argument")
    void honoursQuotes() {
        assertEquals(
                List.of("C:\\Program Files\\Git\\bin\\bash.exe", "--login"),
                Argv.split("\"C:\\Program Files\\Git\\bin\\bash.exe\" --login"));
        assertEquals(List.of("say", "hello there"), Argv.split("say 'hello there'"));
    }

    @Test
    @DisplayName("a backslash is a literal — Windows paths must not need escaping")
    void backslashIsNotAnEscape() {
        assertEquals(List.of("C:\\Windows\\System32\\cmd.exe"), Argv.split("C:\\Windows\\System32\\cmd.exe"));
    }

    @Test
    @DisplayName("nothing, or only spaces, is no arguments")
    void handlesEmptyInput() {
        assertEquals(List.of(), Argv.split(""));
        assertEquals(List.of(), Argv.split("   "));
        assertEquals(List.of(), Argv.split(null));
    }

    @Test
    @DisplayName("an argv renders as a line that splits back into the same argv")
    void roundTrips() {
        for (List<String> argv : List.of(
                List.of("wsl.exe", "-d", "Ubuntu"),
                List.of("C:\\Program Files\\Git\\bin\\bash.exe", "--login", "-i"),
                List.of("/bin/zsh", "-l"),
                List.of("nu"),
                List.of("sh", "-c", "echo hello world"))) {
            assertEquals(argv, Argv.split(Argv.join(argv)), "round trip of " + argv);
        }
    }

    @Test
    @DisplayName("an empty argument is quoted, so it does not vanish on the way back")
    void keepsEmptyArguments() {
        List<String> argv = List.of("cmd", "/c", "start", "", "https://example.com");
        assertEquals(argv, Argv.split(Argv.join(argv)));
    }

    @Test
    @DisplayName("an argument containing a quote is wrapped in the other kind")
    void picksTheQuoteThatIsNotInTheText() {
        assertEquals(List.of("say \"hi\""), Argv.split(Argv.join(List.of("say \"hi\""))));
        assertEquals(List.of("it's here"), Argv.split(Argv.join(List.of("it's here"))));
    }

    @Test
    @DisplayName("nothing joins to nothing")
    void joinsEmpty() {
        assertEquals("", Argv.join(List.of()));
        assertEquals("", Argv.join(null));
    }
}
