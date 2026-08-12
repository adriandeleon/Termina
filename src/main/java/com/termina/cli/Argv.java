package com.termina.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Turning a command line the user typed into an argument vector, and back again.
 *
 * <p>One parser rather than one per caller. Two of them would agree on every case anyone thought to
 * write down and disagree on the ones nobody did — and a command line that round-trips through the
 * settings file differently from how it was entered is a bug that shows up as a shell that will not
 * start, with nothing in the text to explain why.
 */
public final class Argv {

    private Argv() {}

    /**
     * Splits on whitespace, honouring single and double quotes.
     *
     * <p>Quotes are how a path with a space in it stays one argument. There is no escape character:
     * a backslash is a literal, because these strings are read and written on Windows as often as
     * anywhere else and {@code C:\Program Files} is not a mistake anyone should have to escape.
     */
    public static List<String> split(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null) return tokens;
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else current.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                started = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
                continue;
            }
            current.append(c);
            started = true;
        }
        if (started) tokens.add(current.toString());
        return tokens;
    }

    /**
     * The inverse: an argv rendered as a line {@link #split} reads back identically.
     *
     * <p>An argument is quoted when it contains whitespace or a quote of its own, and when it is
     * empty — an empty argument is real (it is how {@code start ""} names a window) and without
     * quotes it would vanish on the way back in.
     *
     * <p><b>One argument cannot survive this: one containing both a single and a double quote.</b>
     * The grammar has no escape character, so there is no quote left to wrap it in. Rather than
     * emit a line that reads back as something else, such an argument is rendered in double quotes
     * and loses its double quotes on the way in. Nothing in a shell command line looks like that in
     * practice, and adding escaping to buy the case would cost every Windows path a backslash rule.
     */
    public static String join(List<String> argv) {
        if (argv == null || argv.isEmpty()) return "";
        StringBuilder line = new StringBuilder();
        for (String argument : argv) {
            if (!line.isEmpty()) line.append(' ');
            line.append(quoteIfNeeded(argument == null ? "" : argument));
        }
        return line.toString();
    }

    private static String quoteIfNeeded(String argument) {
        boolean needs = argument.isEmpty();
        for (int i = 0; i < argument.length() && !needs; i++) {
            char c = argument.charAt(i);
            needs = Character.isWhitespace(c) || c == '"' || c == '\'';
        }
        if (!needs) return argument;
        // Double quotes unless the argument contains one, in which case single quotes carry it
        // through — there is no escape, so the quote that is not in the text is the one to use.
        char quote = argument.indexOf('"') >= 0 ? '\'' : '"';
        return quote + argument + quote;
    }
}
