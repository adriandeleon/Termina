package com.termina.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * The commands a shell has described with OSC 133: where each prompt began, where its output began
 * and ended, and how it exited.
 *
 * <p>Positions are recorded in the view's running coordinates — a buffer row plus the rows the text
 * had moved up by then, so they stay put as output scrolls without being shifted on every change.
 * A reflow is the exception and goes through {@link #remap}.
 *
 * <p>Written on the emulator thread as the marks arrive and read on the FX thread, so every access
 * is synchronised. The list is bounded: a long session at a prompt should not grow it forever.
 */
final class PromptMarks {

    /** More than any scrollback holds prompts for. */
    static final int MAX_COMMANDS = 2000;

    /**
     * A position in the buffer that survives scrolling.
     *
     * @param line the buffer row plus the rows the text had moved up when it was recorded
     * @param column the slot, as JediTerm indexes the line
     */
    record Pos(long line, int column) {}

    /**
     * One command. Everything but the prompt arrives later, and stays null when the shell never
     * says — a prompt that was abandoned with Ctrl+C has no output and no end.
     */
    record Command(Pos prompt, Pos output, Pos end, Integer exitCode) {

        boolean finished() {
            return end != null;
        }

        boolean failed() {
            return exitCode != null && exitCode != 0;
        }
    }

    private final Deque<Command> commands = new ArrayDeque<>();

    synchronized void prompt(Pos at) {
        // A shell that redraws its prompt (zsh on a resize, a prompt with a clock) sends A again
        // without running anything. The second is the same prompt, not a new command.
        Command last = commands.peekLast();
        if (last != null
                && last.output() == null
                && !last.finished()
                && last.prompt().line() == at.line()) return;
        commands.addLast(new Command(at, null, null, null));
        while (commands.size() > MAX_COMMANDS) commands.removeFirst();
    }

    synchronized void outputStarts(Pos at) {
        Command last = commands.peekLast();
        if (last == null || last.output() != null || last.finished()) return;
        replaceLast(new Command(last.prompt(), at, null, null));
    }

    /** The end of the command most recently started; a second report for it is ignored. */
    synchronized void finished(Pos at, Integer exitCode) {
        Command last = commands.peekLast();
        if (last == null || last.finished()) return;
        replaceLast(new Command(last.prompt(), last.output(), at, exitCode));
    }

    private void replaceLast(Command next) {
        commands.removeLast();
        commands.addLast(next);
    }

    synchronized List<Command> all() {
        return List.copyOf(commands);
    }

    synchronized boolean isEmpty() {
        return commands.isEmpty();
    }

    synchronized void clear() {
        commands.clear();
    }

    /** Forgets the commands whose prompts have scrolled out of the history. */
    synchronized void dropAbove(long firstLine) {
        while (!commands.isEmpty() && commands.peekFirst().prompt().line() < firstLine) commands.removeFirst();
    }

    /**
     * Moves every position through a reflow. A command whose prompt no longer maps anywhere is
     * dropped; a later part that does not map is forgotten, which leaves the command looking
     * unfinished rather than pointing at some other command's text.
     */
    synchronized void remap(Function<Pos, Pos> move) {
        List<Command> moved = new ArrayList<>(commands.size());
        for (Command command : commands) {
            Pos prompt = move.apply(command.prompt());
            if (prompt == null) continue;
            Pos output = command.output() == null ? null : move.apply(command.output());
            Pos end = command.end() == null ? null : move.apply(command.end());
            moved.add(new Command(prompt, output, end, end == null ? null : command.exitCode()));
        }
        commands.clear();
        commands.addAll(moved);
    }

    /**
     * The prompt to jump to from a view whose top row is {@code top}: the nearest one above it, or
     * null when there is none. Above, strictly — the one at the top of the view is where the last
     * jump landed, and stopping on it again would be a jump that does nothing.
     */
    static Command previous(List<Command> commands, long top) {
        Command found = null;
        for (Command command : commands) {
            if (command.prompt().line() < top) found = command;
        }
        return found;
    }

    /** The nearest prompt below the top of the view, or null. */
    static Command next(List<Command> commands, long top) {
        for (Command command : commands) {
            if (command.prompt().line() > top) return command;
        }
        return null;
    }

    /** The most recent command that has both started and finished its output, or null. */
    static Command lastWithOutput(List<Command> commands) {
        for (int i = commands.size() - 1; i >= 0; i--) {
            Command command = commands.get(i);
            if (command.output() != null && command.finished()) return command;
        }
        return null;
    }
}
