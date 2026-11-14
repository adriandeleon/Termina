package com.termina.ui;

import org.jspecify.annotations.Nullable;

/**
 * The strings a frame draws, kept from one frame to the next (PERF-09, Pauta 6.0.1).
 *
 * <p>The canvas draws a {@code String}, and the emulator hands the painter each style run as a
 * view onto the line's own characters. Copying every run to a new string every frame made a
 * repaint of a screen that had not changed allocate a string and an array for each run on it, sixty
 * times a second while a cursor blinks or a selection is dragged. A run's text is looked up here
 * by its characters instead, without copying them: the same text gives back the string made for it
 * the last time, and only text not seen before is copied.
 *
 * <p>A fixed table, one string a slot, the newer replacing the older on a collision: nothing to
 * evict and nothing that grows. A screen is a few hundred runs, so with {@link #SLOTS} slots two
 * runs on screen at once seldom share one; when they do, each frame copies those two and no more.
 * The painter's, so only ever used on the FX thread.
 */
final class RunText {

    /** A power of two, several times the runs on a large screen. */
    static final int SLOTS = 4096;

    private final @Nullable String[] slots = new String[SLOTS];

    /** Every code point drawn by itself, by its number: see {@link #of(int)}. */
    private final @Nullable String[] @Nullable [] glyphPages = new String[0x110000 >> PAGE_BITS][];

    private static final int PAGE_BITS = 8;

    /** The run's characters as a string: the one made for the same characters before, if any. */
    String of(CharSequence run) {
        int length = run.length();
        int hash = length;
        for (int i = 0; i < length; i++) hash = 31 * hash + run.charAt(i);
        int slot = (hash ^ (hash >>> 16)) & (SLOTS - 1);
        String kept = slots[slot];
        if (kept != null && kept.length() == length && kept.contentEquals(run)) return kept;
        String made = run.toString();
        slots[slot] = made;
        return made;
    }

    /**
     * One code point as a string, for a run drawn a glyph at a time (wide characters, icons): made
     * once for each code point ever drawn and read from then on, a page of 256 at a time.
     */
    String of(int codePoint) {
        @Nullable String[] page = glyphPages[codePoint >> PAGE_BITS];
        if (page == null) {
            page = new String[1 << PAGE_BITS];
            glyphPages[codePoint >> PAGE_BITS] = page;
        }
        int index = codePoint & ((1 << PAGE_BITS) - 1);
        String kept = page[index];
        if (kept != null) return kept;
        String made = Character.toString(codePoint);
        page[index] = made;
        return made;
    }
}
