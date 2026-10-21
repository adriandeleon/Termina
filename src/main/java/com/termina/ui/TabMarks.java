package com.termina.ui;

/**
 * When a tab that is not being looked at should say something happened in it.
 *
 * <p>Two things are worth a mark: a bell, which is a program asking for attention, and output
 * arriving after a quiet spell, which is how a build finishing or a prompt returning looks from
 * outside. Selecting the tab clears both — the user has now looked.
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
        BELL
    }

    /** A bell rang. Nothing to say about the tab being looked at; a bell outranks activity. */
    static Mark onBell(boolean selected, Mark current) {
        return selected ? Mark.NONE : Mark.BELL;
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
        if (current == Mark.BELL) return Mark.BELL;
        if (current == Mark.ACTIVITY) return Mark.ACTIVITY;
        return nowMs - lastOutputMs >= quietMs ? Mark.ACTIVITY : Mark.NONE;
    }

    /** The tab was selected: whatever it had to say has been seen. */
    static Mark onSelected() {
        return Mark.NONE;
    }
}
