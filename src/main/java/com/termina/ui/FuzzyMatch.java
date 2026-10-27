package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * How a typed query is matched against a command title, and how well — Editora's matcher, brought
 * over whole so the two palettes rank the same way.
 *
 * <p>The pickers' question has two halves: does this candidate match what was typed, and how well?
 * A boolean answers only the first, which lets a scattered accidental match sit beside a clean
 * prefix hit. {@link #of} returns a {@link Match} carrying a relative {@code score} <em>and</em> the
 * matched character runs, so a caller can both order its results and embolden the characters
 * responsible for the ranking.
 *
 * <p><b>Scoring.</b> Every matched character earns {@link #SCORE_MATCH}; on top of that a character
 * earns a bonus for landing on a word boundary (start of string, after a separator, a camelCase
 * hump, a letter→digit transition) or for continuing a run begun by the previous one. Non-adjacent
 * steps are penalised, and the whole match is penalised for how far it is spread. The net effect is
 * the ordering people expect: an exact prefix beats an acronym beats a scattered subsequence, and
 * {@code ct} finds "Close Tab" over "Actual Size" because it lands on two word starts rather than
 * inside one word.
 *
 * <p><b>Best alignment, not the first one.</b> The match is computed by dynamic programming over the
 * candidate rather than greedily left to right. A greedy scan takes the first place each query
 * character fits, which is not usually the best: for {@code nw} against "New Window" it would seize
 * the {@code w} of "New" and never reach the one that starts "Window".
 *
 * <p><b>Multiple terms.</b> Whitespace splits the query into terms that must <em>all</em> match, in
 * any order — so "tab close" finds "Close Tab", which a single ordered subsequence cannot.
 *
 * <p><b>Cost.</b> This runs on the FX thread on every keystroke over the whole command list, so a
 * non-match must be cheap: each term first runs an O(n) subsequence scan and returns at once if the
 * characters are not even present, and only a surviving candidate allocates the matrices.
 *
 * <p><b>Case is folded per character against the original string</b>, never by matching a lowercased
 * copy — {@code String.toLowerCase} is not length-preserving ({@code "İ"} U+0130 becomes two
 * characters), so a copy's indices drift past such a character and the returned ranges would overrun
 * the string the caller then substrings.
 */
final class FuzzyMatch {

    private FuzzyMatch() {}

    /**
     * A successful match: a relative {@code score} (higher is better) and the half-open
     * {@code [start,end)} character ranges of the candidate that matched, in ascending order with
     * adjacent runs coalesced.
     *
     * <p>Scores are only comparable between candidates scored against the <em>same</em> query.
     */
    record Match(int score, int[][] ranges) {}

    /** Base value of a single matched character. */
    static final int SCORE_MATCH = 16;
    /** Start of the string, or the character after a non-alphanumeric separator. */
    static final int BONUS_BOUNDARY = 10;
    /** A camelCase hump, or the first digit of a run. */
    static final int BONUS_CAMEL = 8;
    /**
     * The <em>first query character</em>'s bonus counts this many times, wherever in the candidate it
     * lands — how strongly that character anchors the match matters more than any later one.
     *
     * <p>Not a bonus for sitting at index 0 of the candidate. That shape made "Undo History" outrank
     * "Edit: Undo" for {@code undo} in Editora, purely because one title happens to begin with the
     * word while the other contains it as its whole verb. Position is a caller-level tie-break.
     */
    static final int BONUS_FIRST_MULTIPLIER = 2;
    /** Continuing the run begun by the previous matched character. */
    static final int BONUS_CONSECUTIVE = 12;
    /** The typed character had the same case as the candidate's — a tie-break, deliberately small. */
    static final int BONUS_EXACT_CASE = 1;
    /** A step that is not adjacent to the previous matched character. */
    static final int PENALTY_GAP = -5;
    /** Per character of slack between the first and last matched character. */
    static final int PENALTY_SPREAD = -1;

    /** Longest candidate the matrices are built over; a longer candidate is matched on its tail. */
    static final int MAX_SCAN = 400;

    /** Returned by {@link #scoreOf} for a candidate that does not match. */
    static final int NO_SCORE = Integer.MIN_VALUE;

    /**
     * A matrix cell no path reaches. Never added to — every use is guarded by an equality check
     * first — so it cannot underflow into a spuriously attractive score.
     */
    private static final int UNREACHABLE = Integer.MIN_VALUE;

    /**
     * Scores {@code query} against {@code candidate}, or returns {@code null} when it does not match.
     *
     * <p>A blank query returns {@code null} rather than a neutral match: "show everything" is the
     * caller's decision, and the palette already special-cases the empty field before filtering.
     */
    static @Nullable Match of(String candidate, String query) {
        List<int[]> spans = new ArrayList<>();
        int total = run(candidate, query, spans);
        if (total == NO_SCORE) return null;
        return new Match(total, coalesce(spans, tailOffset(candidate)));
    }

    /**
     * The score {@link #of} would report, or {@link #NO_SCORE} when it would not match — without
     * building the highlight ranges, which are the expensive part and which the filter pass throws
     * away: the palette re-derives the highlight at render time for the rows it actually draws.
     * Scores are identical to {@code of(...).score()} by construction — same code path, spans simply
     * not collected — and {@code FuzzyMatchTest} pins that against drift.
     */
    static int scoreOf(String candidate, String query) {
        return run(candidate, query, null);
    }

    /** Scoring core shared by {@link #of} and {@link #scoreOf}; fills {@code spans} when non-null. */
    private static int run(String candidate, String query, @Nullable List<int[]> spans) {
        if (candidate == null || candidate.isEmpty() || query == null) return NO_SCORE;
        String q = query.strip();
        if (q.isEmpty()) return NO_SCORE;
        // A candidate longer than the scan cap is matched on its tail, where the distinguishing
        // part of anything that gets that long tends to be.
        int offset = tailOffset(candidate);
        String s = offset == 0 ? candidate : candidate.substring(offset);

        int total = 0;
        boolean matched = false;
        int from = 0;
        while (from < q.length()) {
            while (from < q.length() && Character.isWhitespace(q.charAt(from))) from++;
            if (from >= q.length()) break;
            int to = from;
            while (to < q.length() && !Character.isWhitespace(q.charAt(to))) to++;
            int termScore = matchTerm(s, q, from, to, spans);
            if (termScore == NO_SCORE) return NO_SCORE; // every term must match
            total += termScore;
            matched = true;
            from = to;
        }
        return matched ? total : NO_SCORE;
    }

    /** Where the matched window starts in {@code candidate} once the {@link #MAX_SCAN} cap applies. */
    private static int tailOffset(String candidate) {
        return Math.max(0, candidate.length() - MAX_SCAN);
    }

    /**
     * Matches one whitespace-delimited term of the query against {@code s}, appending one span per
     * matched character to {@code spans} and returning the term's score. Returns {@link #NO_SCORE} —
     * leaving {@code spans} untouched — when the term does not match.
     */
    private static int matchTerm(String s, String query, int qStart, int qEnd, @Nullable List<int[]> spans) {
        int m = qEnd - qStart;
        int n = s.length();
        if (m > n) return NO_SCORE;

        // Cheap rejection: a plain forward subsequence scan. This is what keeps a non-matching
        // candidate from ever allocating a matrix.
        boolean present = false;
        for (int i = 0, j = 0; j < n; j++) {
            if (eq(s.charAt(j), query.charAt(qStart + i)) && ++i == m) {
                present = true;
                break;
            }
        }
        if (!present) return NO_SCORE;

        // The window the matrices have to cover. Deliberately NOT the greedy scan's own span: greedy
        // finds the *earliest* end, and the best alignment frequently ends later. The sound bounds
        // are the first place the first character can sit and the last place the last one can.
        int lo = 0;
        while (lo < n && !eq(s.charAt(lo), query.charAt(qStart))) lo++;
        int hi = n;
        while (hi > lo && !eq(s.charAt(hi - 1), query.charAt(qEnd - 1))) hi--;

        int w = hi - lo;
        int[][] best = new int[m][w]; // best[i][j] = score of matching query[..i] with query[i] at lo+j
        int[][] from = new int[m][w]; // the column of query[i-1] that produced it, or -1
        for (int i = 0; i < m; i++) java.util.Arrays.fill(best[i], UNREACHABLE);

        for (int i = 0; i < m; i++) {
            char qc = query.charAt(qStart + i);
            int runningBest = UNREACHABLE; // max over previous row, strictly left of j-1
            int runningIdx = -1;
            for (int j = 0; j < w; j++) {
                if (i > 0 && j >= 2) {
                    int cand = best[i - 1][j - 2];
                    if (cand > runningBest) {
                        runningBest = cand;
                        runningIdx = j - 2;
                    }
                }
                int abs = lo + j;
                char sc = s.charAt(abs);
                if (!eq(sc, qc)) continue;
                int bonus = bonusAt(s, abs);
                int exact = sc == qc ? BONUS_EXACT_CASE : 0;
                if (i == 0) {
                    best[i][j] = SCORE_MATCH + bonus * BONUS_FIRST_MULTIPLIER + exact;
                    from[i][j] = -1;
                    continue;
                }
                int gain = SCORE_MATCH + bonus + exact;
                // Either continue the run from j-1, or jump from the best earlier column (<= j-2).
                int consec = j >= 1 && best[i - 1][j - 1] != UNREACHABLE
                        ? best[i - 1][j - 1] + BONUS_CONSECUTIVE
                        : UNREACHABLE;
                int jump = runningBest != UNREACHABLE ? runningBest + PENALTY_GAP : UNREACHABLE;
                if (consec == UNREACHABLE && jump == UNREACHABLE) continue;
                if (consec >= jump) {
                    best[i][j] = consec + gain;
                    from[i][j] = j - 1;
                } else {
                    best[i][j] = jump + gain;
                    from[i][j] = runningIdx;
                }
            }
        }

        int endCol = -1;
        int endScore = UNREACHABLE;
        for (int j = 0; j < w; j++) {
            if (best[m - 1][j] > endScore) {
                endScore = best[m - 1][j];
                endCol = j;
            }
        }
        if (endCol < 0 || endScore == UNREACHABLE) return NO_SCORE;

        int[] cols = new int[m];
        for (int i = m - 1, j = endCol; i >= 0; i--) {
            cols[i] = j;
            j = from[i][j];
        }
        if (spans != null) {
            for (int i = 0; i < m; i++) spans.add(new int[] {lo + cols[i], lo + cols[i] + 1});
        }
        int spread = cols[m - 1] - cols[0] + 1 - m;
        return endScore + spread * PENALTY_SPREAD;
    }

    /**
     * Bonus for matching at {@code j} — the signal that separates a match a human would call
     * meaningful from one that merely lands mid-word.
     */
    private static int bonusAt(String s, int j) {
        char c = s.charAt(j);
        if (j == 0) return BONUS_BOUNDARY;
        char prev = s.charAt(j - 1);
        if (!Character.isLetterOrDigit(prev)) return BONUS_BOUNDARY;
        if (Character.isLowerCase(prev) && Character.isUpperCase(c)) return BONUS_CAMEL;
        if (Character.isDigit(c) && !Character.isDigit(prev)) return BONUS_CAMEL;
        return 0;
    }

    /** Sorts the per-character spans and merges touching ones into runs, shifted by {@code offset}. */
    private static int[][] coalesce(List<int[]> spans, int offset) {
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] span : spans) {
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && span[0] <= last[1]) {
                last[1] = Math.max(last[1], span[1]);
            } else {
                out.add(new int[] {span[0], span[1]});
            }
        }
        if (offset != 0) {
            for (int[] r : out) {
                r[0] += offset;
                r[1] += offset;
            }
        }
        return out.toArray(new int[0][]);
    }

    private static boolean eq(char a, char b) {
        return a == b || Character.toLowerCase(a) == Character.toLowerCase(b);
    }
}
