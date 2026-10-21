package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.text.Text;

/**
 * Turns a palette row's title into {@link Text} runs with the characters that matched the query set
 * apart, so the user can see <em>why</em> a row is in the list and why it ranked where it did.
 *
 * <p>The ranges come from {@link FuzzyMatch} — the same call that produced the row's score — rather
 * than from a second, independent matcher. A palette that ranks with one algorithm and emboldens
 * with another can highlight characters the ranking was not reasoning about.
 */
final class MatchText {

    private MatchText() {}

    /** Style class for the run(s) that matched the query. */
    static final String MATCHED = "palette-match";

    /** Style class for the rest of the title. */
    static final String PLAIN = "palette-cell-text";

    /**
     * Runs for {@code text} against {@code query}, with unmatched runs styled {@link #PLAIN}. Nothing
     * is highlighted for a blank query, which is exactly the "no filter typed yet" state.
     */
    static List<Text> runs(String text, String query) {
        String s = text == null ? "" : text;
        List<Text> parts = new ArrayList<>();
        FuzzyMatch.Match m = FuzzyMatch.of(s, query);
        if (m == null) {
            parts.add(run(s, PLAIN));
            return parts;
        }
        int pos = 0;
        for (int[] r : m.ranges()) {
            // Defensive: a range outside the string would throw inside a cell factory, which JavaFX
            // swallows into a blank row rather than a stack trace. FuzzyMatch guarantees this cannot
            // happen; the clamp means a future bug degrades to a missing highlight, not a blank list.
            int start = Math.max(pos, Math.min(r[0], s.length()));
            int end = Math.max(start, Math.min(r[1], s.length()));
            if (start > pos) parts.add(run(s.substring(pos, start), PLAIN));
            if (end > start) parts.add(run(s.substring(start, end), MATCHED));
            pos = end;
        }
        if (pos < s.length()) parts.add(run(s.substring(pos), PLAIN));
        return parts;
    }

    /** The title with each matched run in square brackets — {@code [C]lose [T]ab} — for a capture report. */
    static String describe(String text, String query) {
        StringBuilder sb = new StringBuilder();
        for (Text t : runs(text, query)) {
            if (t.getStyleClass().contains(MATCHED)) {
                sb.append('[').append(t.getText()).append(']');
            } else {
                sb.append(t.getText());
            }
        }
        return sb.toString();
    }

    private static Text run(String s, String styleClass) {
        Text t = new Text(s);
        t.getStyleClass().add(styleClass);
        return t;
    }
}
