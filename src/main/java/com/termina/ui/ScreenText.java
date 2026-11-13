package com.termina.ui;

/**
 * What a screen reader is given for the terminal's grid (ACC-03, Pauta 6.0.1): the lines on screen
 * as text, without the padding a grid has and a reader would otherwise spell out. Pure.
 */
final class ScreenText {

    private ScreenText() {}

    /**
     * The screen's rows as a reader hears them: trailing spaces off each line, and the blank
     * lines below the last one with anything on it dropped.
     */
    static String tidy(String rows) {
        String[] lines = rows.split("\n", -1);
        int last = lines.length - 1;
        while (last >= 0 && lines[last].isBlank()) last--;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i <= last; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i].stripTrailing());
        }
        return out.toString();
    }

    /** Whether to tell a listening screen reader the text changed: no more than twice a second. */
    static boolean announces(boolean accessibilityActive, long nowMs, long lastMs) {
        return accessibilityActive && nowMs - lastMs >= 500;
    }
}
