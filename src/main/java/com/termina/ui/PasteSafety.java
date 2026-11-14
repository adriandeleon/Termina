package com.termina.ui;

/**
 * When a paste deserves a second look.
 *
 * <p>A newline in pasted text is an Enter the user did not press: the shell runs the line the
 * moment it arrives, and everything after the first newline runs too. A web page can put anything
 * on the clipboard, including things that are not visible on it. Every current terminal asks before
 * a multi-line paste, or can; the dialog and the setting that turns it off live with the window,
 * and this is the rule they consult.
 *
 * <p>Bracketed paste does not change the answer. A shell that enabled it will not execute the text
 * on arrival, but a shell is not the only thing that reads from a terminal, and the mode is the
 * program's to turn off.
 */
final class PasteSafety {

    private PasteSafety() {}

    /**
     * Past this many characters the prompt shows only the start (PERF-12, Pauta 2.0.2): its preview
     * is a text area, and one holding a whole log file lays out every line of it before it shows.
     */
    static final int PREVIEW_CHARS = 64 * 1024;

    /** Past this the paste always asks, with its size, whatever its shape and the setting. */
    static final int LARGE_CHARS = 1024 * 1024;

    /** Past this it is refused: more than any program reads from a terminal on purpose. */
    static final int MAX_CHARS = 16 * 1024 * 1024;

    /**
     * Heap a pasted character costs on its way out, held at once: the clipboard's string, the
     * sanitised copy, the encoded copy (two bytes a character each) and its UTF-8 bytes.
     */
    static final int BYTES_IN_FLIGHT_PER_CHAR = 8;

    /** What a paste's size alone allows. */
    enum Size {
        NORMAL,
        /** Asks first, with the size, and previews only the start. */
        LARGE,
        /** Not sent at all. */
        REFUSED
    }

    /** Decided on the length before the text is copied or encoded. */
    static Size bySize(int length, long freeHeap) {
        if (length > MAX_CHARS || (long) length * BYTES_IN_FLIGHT_PER_CHAR > freeHeap) return Size.REFUSED;
        return length > LARGE_CHARS ? Size.LARGE : Size.NORMAL;
    }

    /** The heap this process can still take, as {@link #bySize} wants it. */
    static long freeHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }

    /** What the prompt shows of {@code text}: all of it, or its start and a line saying so. */
    static String preview(String text) {
        if (text.length() <= PREVIEW_CHARS) return text;
        int end = PREVIEW_CHARS;
        // Not between the halves of a surrogate pair, which would draw as a broken glyph.
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "\n…";
    }

    /** True when the text would submit at least one line on its own. */
    static boolean needsConfirmation(String text) {
        if (text == null) return false;
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }
}
