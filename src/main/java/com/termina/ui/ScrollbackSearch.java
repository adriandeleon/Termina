package com.termina.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.jediterm.terminal.util.CharUtils;
import org.jspecify.annotations.Nullable;

/**
 * Finds a query in the scrollback and the screen, as runs of buffer cells.
 *
 * <p>Pure: it is handed the rows' text, already copied out of the buffer under its lock, and knows
 * nothing about drawing. That keeps the lock held for a copy rather than for a regex over ten
 * thousand lines, and it is what makes the matching testable without a terminal.
 *
 * <p>Matched against <em>logical</em> lines — a row and the rows a soft wrap continued it onto —
 * because a word that reached the right edge and carried on is still one word, and a search that
 * cannot find it is the kind of miss nobody suspects. The link scanner joins rows for the same
 * reason.
 *
 * <p>The text a match is tried against is not the row's raw slots. The second cell of a
 * double-width character holds {@link CharUtils#DWC}, a placeholder that is not text, and left in it
 * would put an invisible character between every pair of CJK characters a query could contain.
 * Unwritten cells are NUL, which reads as a space on screen and is matched as one.
 */
final class ScrollbackSearch {

    /** Past this many the count says "1000+" rather than the search scanning on into a flood. */
    static final int MAX_MATCHES = 1000;

    /** Rows copied per hold of the buffer lock: a scan never holds the emulator up for a whole buffer. */
    static final int CHUNK_ROWS = 2000;

    /**
     * Rows of the buffer as the search reads them, a chunk at a time from the bottom up.
     *
     * <p>The numbering is fixed at the first read; a reader whose rows have moved since (output
     * scrolled them up) translates. History rows do not change once written, only move and age out,
     * so a chunk read later is the same text the first read would have seen.
     */
    interface Rows {
        /**
         * Rows {@code [from, to)}. Fewer comes back when the top of that range has aged out of the
         * history — the rows returned are then the bottom ones, ending at {@code to}, and there is
         * nothing above them. Null when the rows cannot be read in this numbering any more (they
         * were rebuilt by a reflow, or the history was cleared): the search stops with what it has.
         */
        @Nullable
        Chunk read(int from, int to);
    }

    /** Consecutive rows: each one's text, and whether it continues onto the next. */
    record Chunk(List<String> texts, List<Boolean> wrapped) {}

    private ScrollbackSearch() {}

    /**
     * One row's part of a match: a buffer line, and the slots it covers, end exclusive.
     *
     * @param line 0 is the top of the live screen; negative reaches into history
     */
    record Span(int line, int fromSlot, int toSlot) {}

    /** A match: one span per row it crosses, top to bottom. */
    record Match(List<Span> spans) {

        Match {
            spans = List.copyOf(spans);
        }

        int firstLine() {
            return spans.getFirst().line();
        }

        int lastLine() {
            return spans.getLast().line();
        }

        int fromSlot() {
            return spans.getFirst().fromSlot();
        }

        /** The same match, moved up by {@code rows}: what output arriving does to it. */
        Match shiftedUp(int rows) {
            List<Span> moved = new ArrayList<>(spans.size());
            for (Span span : spans) moved.add(new Span(span.line() - rows, span.fromSlot(), span.toSlot()));
            return new Match(moved);
        }
    }

    /**
     * The pattern a query searches for, or null when there is nothing to search for — an empty
     * query, or a regular expression that does not compile yet because it is still being typed.
     *
     * <p>Case follows the query: all lower case matches either case, and a capital anywhere makes
     * it exact. That is the rule vim, ripgrep and most editors call smart case, and it saves a
     * toggle: typing "error" finds every Error, and typing "Error" is asking for that one.
     */
    static @Nullable Pattern compile(String query, boolean regex) {
        if (query == null || query.isEmpty()) return null;
        int flags = caseSensitive(query) ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException incomplete) {
            return null;
        }
    }

    /** Smart case: exact once the query has an upper-case letter in it. */
    static boolean caseSensitive(String query) {
        return !query.equals(query.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The newest {@link #MAX_MATCHES} matches of {@code pattern}, top to bottom, over rows held in
     * memory. See {@link #find(int, int, Rows, Pattern, int)}.
     *
     * @param rows the text of each row, consecutively, starting at {@code firstLine}
     * @param wrapped whether each row continues onto the next — a soft wrap, not a newline
     * @param firstLine the buffer line of {@code rows[0]}, negative for history
     */
    static List<Match> find(List<String> rows, List<Boolean> wrapped, int firstLine, @Nullable Pattern pattern) {
        return find(
                firstLine,
                firstLine + rows.size(),
                (from, to) -> new Chunk(
                        rows.subList(from - firstLine, to - firstLine),
                        wrapped.subList(from - firstLine, to - firstLine)),
                pattern,
                CHUNK_ROWS);
    }

    /**
     * The newest {@link #MAX_MATCHES} matches of {@code pattern} in lines {@code [firstLine,
     * endLine)}, returned top to bottom.
     *
     * <p>Scanned from the bottom up, a chunk of rows at a time, and stopped once the cap is reached.
     * The newest matches are the ones kept because they are the ones on screen: a search that kept
     * the oldest thousand of a long build log highlighted nothing in view and started far up in the
     * history. And bottom-up is what lets the scan stop early — on a query that matches everywhere,
     * the thousand newest are in the last few screens, and the hundred thousand rows above them are
     * never copied.
     *
     * <p>A logical line can cross a chunk's top edge, and it is not searched until all of it has
     * been read: the rows above the topmost line known to start in the chunk are carried up and
     * joined to the next one.
     *
     * @param chunkRows rows per read; a parameter so a test can make lines cross chunk edges
     */
    static List<Match> find(int firstLine, int endLine, Rows source, @Nullable Pattern pattern, int chunkRows) {
        if (pattern == null) return new ArrayList<>();
        // Bottom up, one list per logical line; each list in its own top-to-bottom order.
        List<List<Match>> newestFirst = new ArrayList<>();
        int count = 0;
        List<String> carriedTexts = List.of();
        List<Boolean> carriedWrapped = List.of();
        int end = endLine;
        while (count < MAX_MATCHES && end > firstLine) {
            int from = Math.max(firstLine, end - Math.max(1, chunkRows));
            Chunk chunk = source.read(from, end);
            if (chunk == null) break;
            int read = chunk.texts().size();
            int top = end - read;
            // Nothing above: the first line, or history that has aged out above what came back.
            boolean atTop = top <= firstLine || read < end - from;
            List<String> texts = new ArrayList<>(read + carriedTexts.size());
            texts.addAll(chunk.texts());
            texts.addAll(carriedTexts);
            List<Boolean> wrapped = new ArrayList<>(read + carriedWrapped.size());
            wrapped.addAll(chunk.wrapped());
            wrapped.addAll(carriedWrapped);
            int lineEnd = texts.size() - 1;
            for (int i = lineEnd; i >= 0 && count < MAX_MATCHES; i--) {
                boolean starts = i == 0 ? atTop : !wrapped.get(i - 1);
                if (!starts) continue;
                List<Match> found = searchLogicalLine(texts, i, lineEnd, top, pattern);
                newestFirst.add(found);
                count += found.size();
                lineEnd = i - 1;
            }
            if (atTop || count >= MAX_MATCHES || read == 0) break;
            carriedTexts = new ArrayList<>(texts.subList(0, lineEnd + 1));
            carriedWrapped = new ArrayList<>(wrapped.subList(0, lineEnd + 1));
            end = top;
        }
        List<Match> out = new ArrayList<>(Math.min(count, MAX_MATCHES));
        for (int i = newestFirst.size() - 1; i >= 0; i--) out.addAll(newestFirst.get(i));
        // The topmost line read can take the count past the cap; its oldest matches are the ones to go.
        return out.size() > MAX_MATCHES ? new ArrayList<>(out.subList(out.size() - MAX_MATCHES, out.size())) : out;
    }

    /**
     * Rows {@code from} to {@code to}, inclusive, as one line of text with a map back to cells: its
     * matches, top to bottom — the newest {@link #MAX_MATCHES} of them, should one line hold more.
     */
    private static List<Match> searchLogicalLine(List<String> rows, int from, int to, int firstLine, Pattern pattern) {
        Deque<Match> out = new ArrayDeque<>();
        StringBuilder text = new StringBuilder();
        // Per character of the joined text: its row, and the slot it starts at and how many it
        // covers — two for a wide character, whose placeholder cell is left out of the text.
        List<int[]> cells = new ArrayList<>();
        for (int row = from; row <= to; row++) {
            String raw = rows.get(row);
            for (int slot = 0; slot < raw.length(); slot++) {
                char c = raw.charAt(slot);
                if (c == CharUtils.DWC) continue;
                int width = slot + 1 < raw.length() && raw.charAt(slot + 1) == CharUtils.DWC ? 2 : 1;
                text.append(c == 0 ? ' ' : c);
                cells.add(new int[] {firstLine + row, slot, width});
            }
        }
        Matcher m = pattern.matcher(text);
        int at = 0;
        while (at <= text.length() && m.find(at)) {
            if (m.end() == m.start()) {
                // A pattern that can match nothing ("a*") matches nothing everywhere. Stepping past
                // it is what keeps the loop moving; reporting it would highlight no cells at all.
                at = m.end() + 1;
                continue;
            }
            out.addLast(new Match(spansOf(cells, m.start(), m.end())));
            if (out.size() > MAX_MATCHES) out.removeFirst();
            at = m.end();
        }
        return new ArrayList<>(out);
    }

    private static List<Span> spansOf(List<int[]> cells, int start, int end) {
        List<Span> spans = new ArrayList<>();
        int line = cells.get(start)[0];
        int fromSlot = cells.get(start)[1];
        int toSlot = fromSlot;
        for (int i = start; i < end; i++) {
            int[] cell = cells.get(i);
            if (cell[0] != line) {
                spans.add(new Span(line, fromSlot, toSlot));
                line = cell[0];
                fromSlot = cell[1];
            }
            toSlot = cell[1] + cell[2];
        }
        spans.add(new Span(line, fromSlot, toSlot));
        return spans;
    }

    /**
     * The match to start on: the last one that begins at or above {@code bottomLine}.
     *
     * <p>A terminal is searched for something recent far more often than for something old, and
     * the newest match is the one nearest where the user is looking — so the search starts at the
     * bottom of the view and works up, as iTerm2's does. Falls back to the first match below when
     * every match is under the view.
     */
    static int initial(List<Match> matches, int bottomLine) {
        if (matches.isEmpty()) return -1;
        for (int i = matches.size() - 1; i >= 0; i--) {
            if (matches.get(i).firstLine() <= bottomLine) return i;
        }
        return 0;
    }

    /**
     * The next match in a direction, wrapping at either end.
     *
     * @param older towards the top of the scrollback, which is what Enter does
     */
    static int step(int current, int count, boolean older) {
        if (count == 0) return -1;
        if (current < 0) return older ? count - 1 : 0;
        return Math.floorMod(current + (older ? -1 : 1), count);
    }

    /**
     * The match covering a position — the one to keep current when the results are recomputed under
     * it, so new output does not move the highlight the user has stepped to.
     */
    static int indexAt(List<Match> matches, int line, int slot) {
        for (int i = 0; i < matches.size(); i++) {
            Match match = matches.get(i);
            if (match.firstLine() == line && match.fromSlot() == slot) return i;
        }
        return -1;
    }

    /** The total as the bar shows it: the count, or "1000+" once the search stopped counting. */
    static String total(int count) {
        return count >= MAX_MATCHES ? MAX_MATCHES + "+" : String.valueOf(count);
    }
}
