package com.termina.ui;

import java.util.Locale;

/**
 * How a typed query is matched against a command name, and how well.
 *
 * <p>Subsequence matching, not substring: the point of a palette is that "ct" finds "Close Tab"
 * without typing either word. That alone matches far too much, though — "ct" is a subsequence of
 * almost every multi-word label — so the score is what does the real work, and the score is the part
 * that is easy to get subtly wrong. Hence pure, and tested.
 *
 * <p>Three things earn points, in the order a person would rank them: a character that begins a
 * word, a character that continues the previous match, and a match that starts near the front of
 * the label. Nothing here is novel; it is the shape every fuzzy finder converges on.
 */
final class CommandMatcher {

    /** Returned when the query is not a subsequence of the label at all. */
    static final int NO_MATCH = -1;

    private static final int WORD_START_BONUS = 12;
    private static final int CONSECUTIVE_BONUS = 6;
    private static final int LEADING_PENALTY = 1;

    private CommandMatcher() {}

    /**
     * @return a score, higher being better, or {@link #NO_MATCH}. An empty query matches everything
     *     with an equal score, which leaves the caller's own order intact — for a palette that is
     *     the menu order, which is the order the user already knows.
     */
    static int score(String query, String label) {
        if (label == null) return NO_MATCH;
        if (query == null || query.isBlank()) return 0;

        String q = query.toLowerCase(Locale.ROOT).replace(" ", "");
        String text = label.toLowerCase(Locale.ROOT);

        int score = 0;
        int at = 0;
        int previousMatch = -2;
        for (int i = 0; i < q.length(); i++) {
            char wanted = q.charAt(i);
            int found = text.indexOf(wanted, at);
            if (found < 0) return NO_MATCH;

            if (found == 0 || !Character.isLetterOrDigit(text.charAt(found - 1))) {
                score += WORD_START_BONUS;
            }
            if (found == previousMatch + 1) {
                score += CONSECUTIVE_BONUS;
            }
            // Only the first character pays for its distance from the front. Charging every one
            // would punish a long label for being long, which is not what anybody means.
            if (i == 0) {
                score -= Math.min(found, 10) * LEADING_PENALTY;
            }
            previousMatch = found;
            at = found + 1;
        }
        return score;
    }
}
