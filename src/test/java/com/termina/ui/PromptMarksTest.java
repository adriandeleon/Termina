package com.termina.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the marks a shell sends become commands, and where a jump lands. */
class PromptMarksTest {

    private static PromptMarks.Pos at(long line) {
        return new PromptMarks.Pos(line, 0);
    }

    /** One command per prompt line, each finished on the line before the next prompt. */
    private static PromptMarks session(long... prompts) {
        PromptMarks marks = new PromptMarks();
        for (long prompt : prompts) {
            marks.finished(at(prompt), 0);
            marks.prompt(at(prompt));
            marks.outputStarts(at(prompt + 1));
        }
        return marks;
    }

    @Test
    void aCommandIsAPromptItsOutputAndItsEnd() {
        PromptMarks marks = new PromptMarks();
        marks.prompt(at(10));
        marks.outputStarts(at(11));
        marks.finished(at(15), 1);
        PromptMarks.Command command = marks.all().getFirst();
        assertEquals(at(10), command.prompt());
        assertEquals(at(11), command.output());
        assertEquals(at(15), command.end());
        assertTrue(command.failed());
    }

    @Test
    void aRedrawnPromptIsTheSamePrompt() {
        PromptMarks marks = new PromptMarks();
        marks.prompt(at(4));
        marks.prompt(at(4));
        assertEquals(1, marks.all().size());
    }

    @Test
    void anEndWithNoCommandRunningIsIgnored() {
        // Shells send D before their first prompt, for a command that never existed.
        PromptMarks marks = new PromptMarks();
        marks.finished(at(0), 0);
        assertTrue(marks.isEmpty());
        marks.prompt(at(1));
        marks.finished(at(2), 0);
        marks.finished(at(9), 1);
        assertEquals(at(2), marks.all().getFirst().end());
        assertFalse(marks.all().getFirst().failed());
    }

    @Test
    void previousIsTheNearestPromptStrictlyAboveTheTopOfTheView() {
        List<PromptMarks.Command> all = session(0, 10, 20).all();
        assertEquals(at(10), PromptMarks.previous(all, 15).prompt());
        // At the top already: the one before it, not itself again.
        assertEquals(at(0), PromptMarks.previous(all, 10).prompt());
        assertNull(PromptMarks.previous(all, 0));
    }

    @Test
    void nextIsTheNearestPromptBelowTheTopOfTheView() {
        List<PromptMarks.Command> all = session(0, 10, 20).all();
        assertEquals(at(20), PromptMarks.next(all, 10).prompt());
        assertNull(PromptMarks.next(all, 20));
    }

    @Test
    void theLastOutputIsTheNewestFinishedCommandThatPrintedSomething() {
        PromptMarks marks = session(0, 10);
        // The prompt at 10 is still running: its output has no end yet.
        assertEquals(at(0), PromptMarks.lastWithOutput(marks.all()).prompt());
        marks.finished(at(14), 0);
        assertEquals(at(10), PromptMarks.lastWithOutput(marks.all()).prompt());
    }

    @Test
    void forgetsPromptsThatScrolledOutOfTheHistory() {
        PromptMarks marks = session(0, 10, 20);
        marks.dropAbove(5);
        assertEquals(at(10), marks.all().getFirst().prompt());
    }

    @Test
    void aRemapThatLosesThePromptDropsTheCommand() {
        PromptMarks marks = session(0, 10);
        marks.remap(pos -> pos.line() < 5 ? null : new PromptMarks.Pos(pos.line() + 100, pos.column()));
        assertEquals(1, marks.all().size());
        assertEquals(at(110), marks.all().getFirst().prompt());
    }

    @Test
    void staysBounded() {
        PromptMarks marks = new PromptMarks();
        for (int i = 0; i < PromptMarks.MAX_COMMANDS + 5; i++) marks.prompt(at(i));
        assertEquals(PromptMarks.MAX_COMMANDS, marks.all().size());
        assertEquals(at(5), marks.all().getFirst().prompt());
    }
}
