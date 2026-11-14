package com.termina.ui;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The search behind a find bar over plain text, the Debug Log's (FIND-01, FIND-02, FIND-06, Pauta
 * 6.0.1): where a query is found, which match the search starts on, and where a step goes. Pure,
 * so it is tested without a window.
 *
 * <p>The terminal has a search of its own ({@link ScrollbackSearch}): it reads rows out of a
 * buffer that moves, newest first. This one reads a string from its start.
 */
final class TextFind {

    /** Past this the count says "1000+" and the rest are not stepped to, as in the terminal's bar. */
    static final int MAX_MATCHES = 1000;

    private TextFind() {}

    /**
     * Where {@code query} is found in {@code text}, in order, as the offset each match starts at;
     * at most {@link #MAX_MATCHES} + 1 of them, the extra one saying there were more. The case is
     * smart, as in the terminal's bar: a query all in lower case matches either case, and a capital
     * in it makes the match exact. An empty query finds nothing.
     */
    static int[] matches(String text, String query) {
        if (query.isEmpty() || text.isEmpty()) return new int[0];
        boolean exact = !query.equals(query.toLowerCase(Locale.ROOT));
        Pattern pattern =
                Pattern.compile(Pattern.quote(query), exact ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Matcher matcher = pattern.matcher(text);
        int[] starts = new int[MAX_MATCHES + 1];
        int count = 0;
        int from = 0;
        while (count < starts.length && from <= text.length() && matcher.find(from)) {
            starts[count++] = matcher.start();
            // Past the whole match: overlapping matches would select text already selected.
            from = matcher.end();
        }
        return java.util.Arrays.copyOf(starts, count);
    }

    /**
     * The match a search starts on: the first at or after {@code anchor}, the caret as it was when
     * the bar opened, so refining the query does not run away down the document (FIND-05); the
     * first of all when there is none after it. -1 when nothing matched.
     */
    static int startAt(int[] starts, int anchor) {
        int stepped = Math.min(starts.length, MAX_MATCHES);
        if (stepped == 0) return -1;
        for (int i = 0; i < stepped; i++) {
            if (starts[i] >= anchor) return i;
        }
        return 0;
    }

    /**
     * The match after {@code current}, or before it, wrapping at the ends (FIND-06). -1 when
     * there are none.
     */
    static int step(int current, int[] starts, boolean forward) {
        int stepped = Math.min(starts.length, MAX_MATCHES);
        if (stepped == 0) return -1;
        if (current < 0) return forward ? 0 : stepped - 1;
        return Math.floorMod(current + (forward ? 1 : -1), stepped);
    }

    /**
     * Whether a log entry's line passes a filter (FIND-03): every word typed is somewhere in it,
     * whatever its case. A blank filter passes everything.
     */
    static boolean passes(String line, String filter) {
        String lowered = line.toLowerCase(Locale.ROOT);
        for (String word : filter.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty() && !lowered.contains(word)) return false;
        }
        return true;
    }
}
