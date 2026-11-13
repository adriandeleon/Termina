package com.termina.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command line. */
class CommandLineTest {

    @Test
    void noArgumentsJustStarts() {
        CommandLine cli = CommandLine.parse();
        assertTrue(cli.shouldRun());
        assertFalse(cli.hasCommand());
        assertNull(cli.error());
    }

    @Test
    void helpAndVersionStopBeforeStarting() {
        for (String arg : new String[] {"-h", "--help"}) {
            assertTrue(CommandLine.parse(arg).help(), arg);
            assertFalse(CommandLine.parse(arg).shouldRun(), arg);
        }
        for (String arg : new String[] {"-v", "-V", "--version"}) {
            assertTrue(CommandLine.parse(arg).version(), arg);
            assertFalse(CommandLine.parse(arg).shouldRun(), arg);
        }
    }

    @Test
    void aTitleIsAcceptedInXtermsSpellingAndGnomes() {
        assertEquals("", CommandLine.parse().title());
        assertEquals("logs", CommandLine.parse("-T", "logs").title());
        assertEquals("logs", CommandLine.parse("--title", "logs").title());
        assertEquals("logs", CommandLine.parse("--title=logs").title());
        assertNotNull(CommandLine.parse("-T").error());
        // x-terminal-emulator callers pass -T before -e; the title must not swallow the command.
        CommandLine cli = CommandLine.parse("-T", "top", "-e", "top", "-d", "1");
        assertEquals("top", cli.title());
        assertEquals(List.of("top", "-d", "1"), cli.command());
    }

    @Test
    void aWorkingDirectoryIsAcceptedInBothSpellings() {
        assertEquals("/tmp", CommandLine.parse("-d", "/tmp").workingDirectory());
        assertEquals("/tmp", CommandLine.parse("--working-directory", "/tmp").workingDirectory());
        assertEquals("/tmp", CommandLine.parse("--working-directory=/tmp").workingDirectory());
    }

    @Test
    void everythingAfterDashEIsTheCommand() {
        // xterm's rule, and the reason -e has to come last: `-e ls -l` must pass -l to ls rather
        // than reject it as an unknown option of ours.
        CommandLine cli = CommandLine.parse("-e", "ls", "-l", "--color");
        assertTrue(cli.hasCommand());
        assertEquals(List.of("ls", "-l", "--color"), cli.command());
        assertTrue(cli.shouldRun());
    }

    @Test
    void theEqualsFormOfCommandTakesItsArgumentsToo() {
        CommandLine cli = CommandLine.parse("--command=vim", "notes.txt");
        assertEquals(List.of("vim", "notes.txt"), cli.command());
    }

    @Test
    void optionsBeforeDashEStillApply() {
        CommandLine cli = CommandLine.parse("-d", "/tmp", "-e", "pwd");
        assertEquals("/tmp", cli.workingDirectory());
        assertEquals(List.of("pwd"), cli.command());
    }

    @Test
    void aCommandThatWasNotSuppliedIsAnError() {
        assertNotNull(CommandLine.parse("-e").error());
        assertNotNull(CommandLine.parse("--command=").error());
    }

    @Test
    void aMissingValueIsAnError() {
        assertNotNull(CommandLine.parse("-d").error());
        assertNotNull(CommandLine.parse("--config-dir").error());
    }

    @Test
    void anUnknownOptionIsRefusedRatherThanIgnored() {
        // Ignoring it looks exactly like the option existing and doing nothing.
        CommandLine cli = CommandLine.parse("--colour=blue");
        assertNotNull(cli.error());
        assertTrue(cli.error().contains("--colour=blue"), cli.error());
        assertFalse(cli.shouldRun());
    }

    @Test
    void aStrayArgumentSaysHowToRunACommand() {
        CommandLine cli = CommandLine.parse("vim");
        assertNotNull(cli.error());
        assertTrue(cli.error().contains("-e"), cli.error());
    }

    @Test
    void theConfigDirectoryIsAcceptedInBothSpellings() {
        assertEquals("/tmp/c", CommandLine.parse("--config-dir", "/tmp/c").configDir());
        assertEquals("/tmp/c", CommandLine.parse("--config-dir=/tmp/c").configDir());
    }

    @Test
    void theUsageTextNamesEveryOption() {
        // A flag that exists but is undocumented may as well not: the usage text is the only place
        // anybody looks.
        String usage = CommandLine.usage("termina");
        for (String option : new String[] {"--help", "--version", "--working-directory", "--command", "--config-dir"}) {
            assertTrue(usage.contains(option), "usage omits " + option);
        }
    }

    @Test
    void reportIsAnswerWithoutStartingTheApp() {
        CommandLine cli = CommandLine.parse("--report");
        org.junit.jupiter.api.Assertions.assertTrue(cli.report());
        org.junit.jupiter.api.Assertions.assertFalse(cli.shouldRun(), "--report prints and exits; no window");
        org.junit.jupiter.api.Assertions.assertTrue(CommandLine.usage("termina").contains("--report"));
    }

    @Test
    void theLaunchersJvmOptionsAreSkippedWithTheirValues() {
        // CLI-12 (Pauta 6.0.1): by name, in both forms a launcher writes, and never refused.
        CommandLine spaced = CommandLine.parse(
                "--add-exports", "javafx.graphics/com.sun.glass.ui=com.termina",
                "--add-opens", "javafx.graphics/com.sun.javafx.font=com.termina",
                "-p", "/opt/termina/lib",
                "--title", "kept");
        assertNull(spaced.error());
        assertTrue(spaced.shouldRun());
        assertEquals("kept", spaced.title());

        CommandLine joined = CommandLine.parse(
                "--add-exports=javafx.graphics/com.sun.glass.ui=com.termina",
                "--add-opens=javafx.graphics/com.sun.javafx.font=com.termina",
                "-d",
                "/tmp");
        assertNull(joined.error());
        assertEquals("/tmp", joined.workingDirectory());

        // The last argument with its value missing is still not an error of ours.
        assertNull(CommandLine.parse("--add-opens").error());
    }

    @Test
    void anOptionThatOnlyLooksLikeALaunchersIsStillRefused() {
        assertEquals(
                "unknown option: --add-modules",
                CommandLine.parse("--add-modules", "x").error());
        assertEquals("unknown option: -P", CommandLine.parse("-P", "x").error());
        assertEquals(
                "unknown option: --add-export=x",
                CommandLine.parse("--add-export=x").error());
    }

    @Test
    void afterDashETheLaunchersOptionsBelongToTheCommand() {
        CommandLine cli = CommandLine.parse("-e", "java", "-p", "mods");
        assertEquals(List.of("java", "-p", "mods"), cli.command());
    }

    @Test
    void everyOptionLineOfTheUsageTextStartsInTheSameColumns() {
        // Two rows once sat ten columns left of the rest: a text block is stripped to its
        // shallowest line.
        CommandLine.usage("termina")
                .lines()
                .filter(line -> line.contains("--"))
                .filter(line -> !line.startsWith("Usage") && !line.startsWith("itself"))
                .forEach(line -> assertTrue(line.matches("^  (-\\w,|   ) --.*"), line));
    }
}
