package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How many lines the paste prompt says it is about to send. */
class PastePromptTest {

    @Test
    void countsLinesTheWayAShellWillRunThem() {
        assertEquals(0, TerminalWindow.lineCount(""));
        assertEquals(1, TerminalWindow.lineCount("ls"));
        assertEquals(2, TerminalWindow.lineCount("ls\npwd"));
        // A trailing break still ends a line: the shell runs it.
        assertEquals(2, TerminalWindow.lineCount("ls\n"));
        // Every convention counts once.
        assertEquals(3, TerminalWindow.lineCount("a\r\nb\rc"));
    }
}
