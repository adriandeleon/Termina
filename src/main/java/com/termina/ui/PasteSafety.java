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

    /** True when the text would submit at least one line on its own. */
    static boolean needsConfirmation(String text) {
        if (text == null) return false;
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }
}
