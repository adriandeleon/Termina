package com.termina.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.jediterm.terminal.util.CharUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a scrollback search finds, and where it says it is. */
class ScrollbackSearchTest {

    private static List<ScrollbackSearch.Match> find(String query, boolean regex, int firstLine, String... rows) {
        return ScrollbackSearch.find(
                List.of(rows),
                new ArrayList<>(Collections.nCopies(rows.length, false)),
                firstLine,
                ScrollbackSearch.compile(query, regex));
    }

    private static ScrollbackSearch.Span span(int line, int from, int to) {
        return new ScrollbackSearch.Span(line, from, to);
    }

    @Test
    void findsEveryOccurrenceTopToBottomInBufferLines() {
        var matches = find("err", false, -2, "err one", "fine", "two err err");
        assertEquals(3, matches.size());
        assertEquals(List.of(span(-2, 0, 3)), matches.get(0).spans());
        assertEquals(List.of(span(0, 4, 7)), matches.get(1).spans());
        assertEquals(List.of(span(0, 8, 11)), matches.get(2).spans());
    }

    @Test
    void lowerCaseMatchesEitherCaseAndACapitalMakesItExact() {
        assertEquals(2, find("error", false, 0, "Error", "error").size());
        assertEquals(1, find("Error", false, 0, "Error", "error").size());
        assertTrue(ScrollbackSearch.caseSensitive("Error"));
        assertFalse(ScrollbackSearch.caseSensitive("error 42"));
    }

    @Test
    void plainTextIsLiteralAndRegexIsNot() {
        assertEquals(0, find("a.c", false, 0, "abc").size());
        assertEquals(1, find("a.c", true, 0, "abc").size());
        assertEquals(1, find("(", false, 0, "f(x)").size());
    }

    @Test
    void aRegexStillBeingTypedSearchesForNothingRatherThanThrowing() {
        assertNull(ScrollbackSearch.compile("foo(", true));
        assertNull(ScrollbackSearch.compile("", false));
        assertTrue(find("foo(", true, 0, "foo(").isEmpty());
    }

    @Test
    void aWordWrappedAcrossTheEdgeIsFoundAsOneMatchOnTwoRows() {
        // "hello" reached the right edge after "hel" and carried on — one logical line.
        var matches = ScrollbackSearch.find(
                List.of("say hel", "lo there"), List.of(true, false), 5, ScrollbackSearch.compile("hello", false));
        assertEquals(1, matches.size());
        assertEquals(List.of(span(5, 4, 7), span(6, 0, 2)), matches.getFirst().spans());
    }

    @Test
    void aNewlineIsNotAWrapAndDoesNotJoinRows() {
        assertTrue(find("hello", false, 0, "say hel", "lo there").isEmpty());
    }

    @Test
    void wideCharactersMatchWithoutTheirPlaceholderAndCoverBothCells() {
        String dwc = String.valueOf(CharUtils.DWC);
        // 日本 occupies four slots: each character followed by its placeholder cell.
        var matches = find("日本", false, 0, "x日" + dwc + "本" + dwc + "y");
        assertEquals(1, matches.size());
        assertEquals(List.of(span(0, 1, 5)), matches.getFirst().spans());
    }

    @Test
    void unwrittenCellsReadAsSpaces() {
        assertEquals(1, find("a b", false, 0, "a\0b").size());
    }

    @Test
    void aPatternThatMatchesNothingFindsNothingInsteadOfLooping() {
        assertTrue(find("x*", true, 0, "abc").isEmpty());
        assertEquals(2, find("x*", true, 0, "axbxx").size());
    }

    @Test
    void stopsCountingAtTheCap() {
        String[] rows = new String[ScrollbackSearch.MAX_MATCHES + 50];
        java.util.Arrays.fill(rows, "x");
        assertEquals(ScrollbackSearch.MAX_MATCHES, find("x", false, 0, rows).size());
        assertEquals("1000+", ScrollbackSearch.total(ScrollbackSearch.MAX_MATCHES));
        assertEquals("7", ScrollbackSearch.total(7));
    }

    @Test
    void startsFromTheNewestMatchAtOrAboveTheBottomOfTheView() {
        var matches = find("x", false, -10, "x", "", "", "", "", "x", "", "", "", "", "", "", "", "", "", "x");
        // Lines -10, -5 and 5. A view whose bottom is line 0 starts on -5, not on the one below it.
        assertEquals(1, ScrollbackSearch.initial(matches, 0));
        assertEquals(2, ScrollbackSearch.initial(matches, 20));
        assertEquals(0, ScrollbackSearch.initial(matches, -20));
        assertEquals(-1, ScrollbackSearch.initial(List.of(), 0));
    }

    @Test
    void stepsWrapAtEitherEnd() {
        assertEquals(1, ScrollbackSearch.step(2, 3, true));
        assertEquals(2, ScrollbackSearch.step(0, 3, true));
        assertEquals(0, ScrollbackSearch.step(2, 3, false));
        assertEquals(-1, ScrollbackSearch.step(0, 0, true));
    }

    @Test
    void aMatchMovesWithItsText() {
        var match = find("x", false, 0, "", "ax").getFirst();
        assertEquals(List.of(span(-2, 1, 2)), match.shiftedUp(3).spans());
        assertEquals(0, ScrollbackSearch.indexAt(List.of(match), 1, 1));
        assertEquals(-1, ScrollbackSearch.indexAt(List.of(match), 1, 0));
    }

    @Test
    void keepsTheNewestMatchesWhenThereAreTooMany() {
        // A long build log: the matches on screen are the newest, and they are the ones kept.
        int total = ScrollbackSearch.MAX_MATCHES + 500;
        String[] rows = new String[total];
        java.util.Arrays.fill(rows, "x");
        int firstLine = -(total - 10);
        var matches = find("x", false, firstLine, rows);
        assertEquals(ScrollbackSearch.MAX_MATCHES, matches.size());
        assertEquals(firstLine + total - 1, matches.getLast().firstLine(), "the bottom row is in");
        assertEquals(firstLine + 500, matches.getFirst().firstLine(), "the oldest 500 are the ones dropped");
        // Still top to bottom, and the search starts near the bottom of the view.
        assertTrue(matches.getFirst().firstLine() < matches.getLast().firstLine());
        assertEquals(matches.size() - 1 - 9, ScrollbackSearch.initial(matches, 0));
    }

    @Test
    void oneLineWithTooManyMatchesKeepsItsLastOnes() {
        String row = "x".repeat(ScrollbackSearch.MAX_MATCHES + 3);
        var matches = find("x", false, 0, row);
        assertEquals(ScrollbackSearch.MAX_MATCHES, matches.size());
        assertEquals(3, matches.getFirst().fromSlot());
    }

    @Test
    void stopsReadingOnceItHasTheNewestMatches() {
        // Every row matches: the thousand it keeps are in the bottom chunk, and nothing above is read.
        int total = 50_000;
        List<int[]> reads = new java.util.ArrayList<>();
        var matches = ScrollbackSearch.find(
                -total,
                0,
                (from, to) -> {
                    reads.add(new int[] {from, to});
                    return new ScrollbackSearch.Chunk(
                            java.util.Collections.nCopies(to - from, "x"),
                            java.util.Collections.nCopies(to - from, false));
                },
                ScrollbackSearch.compile("x", false),
                ScrollbackSearch.CHUNK_ROWS);
        assertEquals(ScrollbackSearch.MAX_MATCHES, matches.size());
        assertEquals(1, reads.size());
        assertEquals(-ScrollbackSearch.CHUNK_ROWS, reads.getFirst()[0]);
    }

    @Test
    void readInChunksItFindsWhatOneReadFinds() {
        // Wrapped lines crossing chunk edges are carried up and searched whole.
        List<String> rows = new java.util.ArrayList<>();
        List<Boolean> wrapped = new java.util.ArrayList<>();
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 300; i++) {
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < 6; c++) row.append("abx ".charAt(random.nextInt(4)));
            rows.add(row.toString());
            wrapped.add(random.nextInt(3) > 0);
        }
        java.util.regex.Pattern pattern = ScrollbackSearch.compile("ab", false);
        var whole = ScrollbackSearch.find(rows, wrapped, -250, pattern);
        for (int chunk : new int[] {1, 2, 3, 7, 64}) {
            var chunked = ScrollbackSearch.find(
                    -250,
                    50,
                    (from, to) -> new ScrollbackSearch.Chunk(
                            rows.subList(from + 250, to + 250), wrapped.subList(from + 250, to + 250)),
                    pattern,
                    chunk);
            assertEquals(whole, chunked, "chunk " + chunk);
        }
        assertTrue(whole.stream().anyMatch(m -> m.spans().size() > 1), "some match crosses a wrap");
    }

    @Test
    void historyThatAgedOutDuringTheScanEndsIt() {
        // The reader returns only the bottom rows of a range whose top has gone.
        var matches = ScrollbackSearch.find(
                -10,
                0,
                (from, to) -> {
                    int top = Math.max(from, -3);
                    return new ScrollbackSearch.Chunk(
                            java.util.Collections.nCopies(to - top, "x"),
                            java.util.Collections.nCopies(to - top, false));
                },
                ScrollbackSearch.compile("x", false),
                4);
        assertEquals(
                List.of(-3, -2, -1),
                matches.stream().map(ScrollbackSearch.Match::firstLine).toList());
    }

    @Test
    void rowsRebuiltDuringTheScanEndItWithWhatItHas() {
        int[] reads = {0};
        var matches = ScrollbackSearch.find(
                -8,
                0,
                (from, to) -> reads[0]++ == 0
                        ? new ScrollbackSearch.Chunk(
                                java.util.Collections.nCopies(to - from, "x"),
                                java.util.Collections.nCopies(to - from, false))
                        : null,
                ScrollbackSearch.compile("x", false),
                4);
        // Line -4 was the chunk's top row: whether it continues a line from above was never learned,
        // so it was carried for the next read, and went with it.
        assertEquals(
                List.of(-3, -2, -1),
                matches.stream().map(ScrollbackSearch.Match::firstLine).toList());
    }
}
