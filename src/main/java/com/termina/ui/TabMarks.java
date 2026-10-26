package com.termina.ui;

/**
 * When a tab that is not being looked at should say something happened in it.
 *
 * <p>Three things are worth a mark: a notification (OSC 9 or 777), which is a program saying in
 * words that something happened; a bell, which is a program asking for attention; and output
 * arriving after a quiet spell, which is how a build finishing or a prompt returning looks from
 * outside. They rank in that order — a stronger mark is never replaced by a weaker one — and
 * selecting the tab clears them all: the user has now looked.
 *
 * <p>The quiet spell is the whole of the second rule. A tab that streams continuously is marked
 * once, when the stream starts, and stays marked; without the gap a new tab's own first prompt,
 * printed a moment after it was opened and left, would mark it as news. Pure, because "when does
 * the dot appear" is exactly the kind of rule that is argued about from a screenshot.
 */
final class TabMarks {

    private TabMarks() {}

    /** Output within this of the last is the same burst, not news. */
    static final long QUIET_MS = 2_000;

    enum Mark {
        NONE,
        ACTIVITY,
        BELL,
        NOTIFY
    }

    /** A bell rang. Nothing to say about the tab being looked at; a bell outranks activity. */
    static Mark onBell(boolean selected, Mark current) {
        if (selected) return Mark.NONE;
        return current == Mark.NOTIFY ? Mark.NOTIFY : Mark.BELL;
    }

    /** A program posted a notification. The strongest mark there is, for a tab not being looked at. */
    static Mark onNotify(boolean selected) {
        return selected ? Mark.NONE : Mark.NOTIFY;
    }

    /**
     * Whether a notification goes to the desktop as well as marking the tab: not when its pane is
     * the one being typed in, in the window in front, because then the user is already looking at
     * it. Any of the three not being so — another window in front, another tab selected, another
     * pane of the split active — and the program's message would otherwise go unseen.
     */
    static boolean postsToDesktop(boolean windowFocused, boolean tabSelected, boolean paneActive) {
        return !(windowFocused && tabSelected && paneActive);
    }

    /**
     * Output arrived.
     *
     * @param nowMs when
     * @param lastOutputMs when the previous output arrived — the tab's opening counts as output
     * @return the mark to show: a bell already showing is kept, since it is the stronger claim
     */
    static Mark onOutput(boolean selected, Mark current, long nowMs, long lastOutputMs, long quietMs) {
        if (selected) return Mark.NONE;
        if (current == Mark.NOTIFY || current == Mark.BELL || current == Mark.ACTIVITY) return current;
        return nowMs - lastOutputMs >= quietMs ? Mark.ACTIVITY : Mark.NONE;
    }

    /** The tab was selected: whatever it had to say has been seen. */
    static Mark onSelected() {
        return Mark.NONE;
    }
}
