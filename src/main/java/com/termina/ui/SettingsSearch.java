package com.termina.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How well a Settings row answers a query (SET-03, Pauta 2.0.2). The scoring is Editora's
 * {@code ui.SettingsSearch.score} and {@code weigh}, verbatim apart from the synonyms, which Termina
 * does not have: a change to the ranking belongs in both projects.
 *
 * <p>A row is found by its title, its description, the text its controls show, its section
 * heading, its page's name and its English keywords. A hit in the title outweighs one in the
 * description, which outweighs one in the visible text, then a heading, then a keyword; within
 * each, a whole word outweighs the start of a word, which outweighs a hit inside one. Every word
 * of the query has to be found somewhere in the row.
 *
 * <p>The visible text is Termina's addition (SET-03, Pauta 6.0.1): a button's label, a choice's
 * options, a field's prompt. Without it a row was found by what it is called but not by what it
 * shows, in the UI's language, where the keywords are English only.
 */
final class SettingsSearch {

    /**
     * A row's text by where it shows; each part weighs differently.
     *
     * @param visible what the row's controls show: labels, options, prompts
     */
    record Texts(String title, String description, String visible, String headings, String keywords) {

        /** A row with nothing to show but its title and description. */
        Texts(String title, String description, String headings, String keywords) {
            this(title, description, "", headings, keywords);
        }
    }

    private static final int TITLE = 8;
    private static final int DESCRIPTION = 4;
    private static final int VISIBLE = 3;
    private static final int HEADING = 2;
    private static final int KEYWORD = 1;

    /**
     * Added to a row's score when the query names its page, so typing a page's name opens it: "sync"
     * means the Sync page, though the Keyboard page's "Sync Settings Now" has the word in a title.
     */
    static final int NAMES_THE_PAGE = 1000;

    private SettingsSearch() {}

    /** The row's score: 0 when some word of the query is nowhere in it; 1 for every row on a blank query. */
    static int score(String query, Texts texts) {
        List<String> words = words(query);
        if (words.isEmpty()) return 1;
        int total = 0;
        for (String w : words) {
            int s = Math.max(
                    Math.max(weigh(texts.title(), w) * TITLE, weigh(texts.description(), w) * DESCRIPTION),
                    Math.max(weigh(texts.headings(), w) * HEADING, weigh(texts.keywords(), w) * KEYWORD));
            s = Math.max(s, weigh(texts.visible(), w) * VISIBLE);
            if (s == 0) return 0;
            total += s;
        }
        return total;
    }

    /** {@code score}, raised by {@link #NAMES_THE_PAGE} when the query is in the page's name. */
    static int landing(int score, String query, String pageTitle) {
        if (score == 0 || words(query).isEmpty()) return score;
        String q = String.join(" ", words(query));
        return pageTitle.toLowerCase(Locale.ROOT).contains(q) ? score + NAMES_THE_PAGE : score;
    }

    /** 4 when {@code word} is a whole word of {@code text}, 3 when it starts one, 1 inside one, 0 when absent. */
    static int weigh(String text, String word) {
        if (text == null || text.isEmpty()) return 0;
        String hay = text.toLowerCase(Locale.ROOT);
        int found = 0;
        for (int i = hay.indexOf(word); i >= 0; i = hay.indexOf(word, i + 1)) {
            if (i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1))) {
                int end = i + word.length();
                if (end == hay.length() || !Character.isLetterOrDigit(hay.charAt(end))) return 4;
                found = 3;
            } else {
                found = Math.max(found, 1);
            }
        }
        return found;
    }

    /** The query's words, lower-cased. */
    static List<String> words(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (String w : q.split("\\s+")) out.add(w);
        return out;
    }

    /** The index of the best score, the first of them on a tie, or -1 when nothing matched. */
    static int bestOf(int[] scores) {
        int best = -1;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > 0 && (best < 0 || scores[i] > scores[best])) best = i;
        }
        return best;
    }
}
