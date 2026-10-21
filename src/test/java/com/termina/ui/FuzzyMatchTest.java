package com.termina.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.termina.ui.FuzzyMatch.Match;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ranking <em>is</em> the product here, so most of this asserts which candidate wins rather than
 * merely that both matched. Absolute scores are deliberately never asserted: they are relative, and
 * pinning them would make every future tuning change a test rewrite.
 */
class FuzzyMatchTest {

    /** The candidates that match {@code query}, best first — the palette's eye view. */
    private static List<String> rank(String query, String... candidates) {
        record Scored(String name, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (String c : candidates) {
            Match m = FuzzyMatch.of(c, query);
            if (m != null) scored.add(new Scored(c, m.score()));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        return scored.stream().map(Scored::name).toList();
    }

    /** Asserts {@code winner} outranks {@code loser} for {@code query} (both must match). */
    private static void beats(String query, String winner, String loser) {
        Match w = FuzzyMatch.of(winner, query);
        Match l = FuzzyMatch.of(loser, query);
        assertNotNull(w, () -> "'" + query + "' should match " + winner);
        assertNotNull(l, () -> "'" + query + "' should match " + loser);
        assertTrue(
                w.score() > l.score(),
                () -> "'" + query + "': expected " + winner + " (" + w.score() + ") to outrank " + loser + " ("
                        + l.score() + ")");
    }

    /** Flattens ranges into the substring they cover, so an expectation reads as the emboldened text. */
    private static String matched(String candidate, String query) {
        Match m = FuzzyMatch.of(candidate, query);
        if (m == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int[] r : m.ranges()) {
            if (!sb.isEmpty()) sb.append('|');
            sb.append(candidate, r[0], r[1]);
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("matching at all")
    class Matching {

        @Test
        void initialsFindTheirCommand() {
            // The whole reason for a palette: two keys, no full word typed.
            assertNotNull(FuzzyMatch.of("Close Tab", "ct"));
            assertNotNull(FuzzyMatch.of("New Window", "nw"));
        }

        @Test
        void rejectsCharactersNotPresent() {
            assertNull(FuzzyMatch.of("Close Tab", "zz"));
        }

        @Test
        void rejectsCharactersPresentButOutOfOrder() {
            assertNull(FuzzyMatch.of("Tab", "bat"));
        }

        @Test
        void rejectsAQueryLongerThanTheCandidate() {
            assertNull(FuzzyMatch.of("ab", "abcdef"));
        }

        @Test
        void blankQueryIsNotAMatch() {
            // "Show everything" is the caller's decision, not a neutral match from here.
            assertNull(FuzzyMatch.of("Close Tab", ""));
            assertNull(FuzzyMatch.of("Close Tab", "   "));
        }

        @Test
        void nullsAreNotAMatch() {
            assertNull(FuzzyMatch.of(null, "x"));
            assertNull(FuzzyMatch.of("x", null));
            assertNull(FuzzyMatch.of("", "x"));
        }

        @Test
        void matchIsCaseInsensitive() {
            assertNotNull(FuzzyMatch.of("Close Tab", "CT"));
            assertNotNull(FuzzyMatch.of("CLOSE TAB", "ct"));
        }
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        void initialsBeatALetterBuriedInAWord() {
            // "nt" is a subsequence of both, but only one is the initials.
            beats("nt", "New Tab", "Next Tab");
        }

        @Test
        void initialsWinEvenWhenAnEarlierLetterCouldHaveMatched() {
            // "ct": the t of "Actual" comes first; a leftmost matcher never reaches the T of "Tab".
            beats("ct", "Close Tab", "Actual Size");
        }

        @Test
        void aRunOfCharactersBeatsAScatteredMatch() {
            beats("tab", "Close Tab", "Terminal About Box");
        }

        @Test
        void aWordStartBeatsAMidWordLetter() {
            beats("c", "Copy", "Actual Size");
        }

        @Test
        void prefixBeatsInternalSubstring() {
            beats("main", "MainController", "DomainModel");
        }

        @Test
        void acronymOnHumpsBeatsMidWordMatch() {
            beats("mc", "MainController", "MyMacro");
        }

        @Test
        void shorterSpreadWins() {
            beats("mc", "MainController", "MainXXXXXXXXController");
        }

        @Test
        void positionInTheCandidateIsNotItselfAScore() {
            // Two identical matches differing only in how much text precedes them score the SAME.
            // Position is the palette's tie-break (shorter label), not a signal here.
            Match early = FuzzyMatch.of("cfg loader", "cfg");
            Match late = FuzzyMatch.of("the cfg loader", "cfg");
            assertNotNull(early);
            assertNotNull(late);
            assertEquals(early.score(), late.score());
        }

        @Test
        void exactCaseBreaksATie() {
            beats("MC", "MC", "mc");
        }

        @Test
        void rankingIsAWholeOrderNotJustAWinner() {
            List<String> ranked = rank("tog", "Toggle Fold", "View: Toggle Git Blame", "Stop Debugging");
            assertEquals("Toggle Fold", ranked.get(0), "a leading exact prefix must win outright");
            assertTrue(
                    ranked.indexOf("View: Toggle Git Blame") < ranked.indexOf("Stop Debugging"),
                    "a whole-word hit must outrank a scattered one: " + ranked);
        }
    }

    @Nested
    @DisplayName("best alignment, not the first one")
    class Alignment {

        @Test
        void picksTheWordStartOverTheEarlierMidWordCharacter() {
            // Greedy left-to-right takes the w of "New". The right answer is the W of "Window".
            assertEquals("N|W", matched("New Window", "nw"));
        }

        @Test
        void prefersAContiguousRunOverAnEarlierScatteredOne() {
            assertEquals("ab", matched("a_x_y_ab", "ab"));
        }
    }

    @Nested
    @DisplayName("matched ranges")
    class Ranges {

        @Test
        void contiguousMatchIsOneRange() {
            Match m = FuzzyMatch.of("Close Tab", "Clos");
            assertNotNull(m);
            assertArrayEquals(new int[][] {{0, 4}}, m.ranges());
        }

        @Test
        void adjacentCharactersCoalesceIntoRuns() {
            assertEquals("Main|roller", matched("MainController", "mainroller"));
        }

        @Test
        void rangesAreAscendingAndNonOverlapping() {
            Match m = FuzzyMatch.of("New Tab With Profile: Ubuntu (WSL)", "ntwu");
            assertNotNull(m);
            int prevEnd = -1;
            for (int[] r : m.ranges()) {
                assertTrue(r[0] < r[1], "range must be non-empty");
                assertTrue(r[0] >= prevEnd, "ranges must not overlap: " + java.util.Arrays.deepToString(m.ranges()));
                prevEnd = r[1];
            }
        }

        @Test
        void rangesStayInsideTheCandidateAcrossCaseFoldingHazards() {
            // "İ" (U+0130) lowercases to TWO chars, so a matcher working on a lowercased copy would
            // return indices that overrun the original — and the cell substrings by these.
            String candidate = "İstanbulTimeZone";
            Match m = FuzzyMatch.of(candidate, "tz");
            assertNotNull(m);
            for (int[] r : m.ranges()) {
                assertTrue(r[0] >= 0 && r[1] <= candidate.length(), "range " + r[0] + ".." + r[1] + " overruns");
                candidate.substring(r[0], r[1]); // must not throw — this is what the cell does
            }
        }
    }

    @Nested
    @DisplayName("multi-term queries")
    class MultiTerm {

        @Test
        void allTermsMustMatch() {
            assertNotNull(FuzzyMatch.of("Close Tab", "tab close"));
            assertNull(FuzzyMatch.of("Close Tab", "tab window"));
        }

        @Test
        void termsMatchOutOfOrder() {
            // A single ordered subsequence cannot find this.
            assertNull(FuzzyMatch.of("Close Tab", "tabclose"));
            assertNotNull(FuzzyMatch.of("Close Tab", "tab close"));
        }

        @Test
        void typingTheWordsOutIsNoWorseThanTheInitials() {
            assertNotNull(FuzzyMatch.of("Close Tab", "close tab"));
            assertNotNull(FuzzyMatch.of("Close Tab", "  close   tab  "));
        }

        @Test
        void rangesCoverEveryTerm() {
            assertEquals("Close|Tab", matched("Close Tab", "tab close"));
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        void aVeryLongCandidateIsMatchedOnItsTailWithCorrectIndices() {
            String candidate = "x".repeat(FuzzyMatch.MAX_SCAN + 120) + "MainController";
            Match m = FuzzyMatch.of(candidate, "maincontroller");
            assertNotNull(m);
            assertEquals("MainController", candidate.substring(m.ranges()[0][0], m.ranges()[0][1]));
        }

        @Test
        void repeatedCharactersDoNotConfuseTheBacktrack() {
            assertEquals("aaa", matched("aaaa", "aaa"));
        }

        @Test
        void aQueryOfRepeatsOverALabelOfRepeatsStaysCheap() {
            // Every a in the query can sit on any a in the label and none of them lead anywhere,
            // because the final c is never there. Typing into the palette must not hang on it.
            String label = "a".repeat(60) + "b";
            String query = "a".repeat(28) + "c";
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(2), () -> assertNull(FuzzyMatch.of(label, query)));
        }
    }

    /**
     * {@link FuzzyMatch#scoreOf} exists so the filter pass can skip building highlight ranges it
     * discards. Its entire contract is that it agrees with {@link FuzzyMatch#of}, so that is asserted
     * exhaustively over awkward shapes: the empty string, a single character, a candidate longer than
     * {@link FuzzyMatch#MAX_SCAN}, mixed case, digits and separators.
     */
    @Nested
    @DisplayName("scoreOf agrees with of")
    class ScoreOnly {

        private final List<String> candidates = new ArrayList<>(List.of(
                "",
                "a",
                "getUserName",
                "GETUSERNAME",
                "get_user_name",
                "get-user-name",
                "HTMLParser",
                "parse2HTML",
                "Go to Tab 3: ~/src",
                "New Tab With Profile: Ubuntu (WSL)",
                "MyMacController",
                "listAll",
                "list"));

        private final List<String> queries = new ArrayList<>(List.of(
                "", " ", "a", "g", "gu", "gun", "getuser", "GetUser", "GUN", "tab 3", "3 tab", "zzz", "2h", "_", "-",
                "/", "list", "listall", "mc"));

        ScoreOnly() {
            candidates.add("x".repeat(FuzzyMatch.MAX_SCAN + 40) + "SymbolIndex");
            Random rnd = new Random(7);
            String alphabet = "abcABC_-/2 ";
            for (int i = 0; i < 300; i++) {
                StringBuilder sb = new StringBuilder();
                for (int n = rnd.nextInt(14); n > 0; n--) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
                candidates.add(sb.toString());
            }
        }

        @Test
        void scoreOfMatchesOfOnEveryPair() {
            for (String c : candidates) {
                for (String q : queries) {
                    Match m = FuzzyMatch.of(c, q);
                    int expected = m == null ? FuzzyMatch.NO_SCORE : m.score();
                    assertEquals(
                            expected, FuzzyMatch.scoreOf(c, q), "of/scoreOf disagree on \"" + c + "\" ~ \"" + q + "\"");
                }
            }
        }

        @Test
        void aNullCandidateOrQueryScoresAsNoMatchRatherThanThrowing() {
            assertEquals(FuzzyMatch.NO_SCORE, FuzzyMatch.scoreOf(null, "a"));
            assertEquals(FuzzyMatch.NO_SCORE, FuzzyMatch.scoreOf("a", null));
        }
    }
}
