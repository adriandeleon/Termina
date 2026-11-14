package com.termina.ui;

/**
 * Which chrome a window shows in each of its modes (WIN-10, Pauta 6.0.1): one truth table, with no
 * JavaFX in it. After Editora's {@code ui.Chrome}.
 *
 * <ul>
 *   <li><b>Zen</b> shows none: no menu bar, no tab strip, no status bar. The terminal and nothing
 *       else.
 *   <li><b>Expert</b> keeps only the status bar: the one row that reports, for someone who runs
 *       everything from the keyboard.
 *   <li><b>Simple</b> hides nothing. It changes what the menu bar lists, a shorter set for someone
 *       new ({@link MenuBarModel#menus(boolean)}).
 * </ul>
 *
 * <p>A mode never writes a setting. What a row shows is "the setting says so, and no mode in force
 * hides it", so leaving the mode brings back exactly what was there. Zen and Expert are a window's
 * own and last until it closes; Simple is a setting, since a newcomer's menus should still be
 * short tomorrow.
 *
 * <p>In every mode the Command Palette's chord still opens it, and it lists the command that
 * leaves the mode: with the menu bar gone, that is the way out.
 */
final class Chrome {

    private Chrome() {}

    /**
     * The two modes that strip chrome, as a window holds them. At most one is on: entering one
     * leaves the other.
     */
    record Modes(boolean zen, boolean expert) {

        static final Modes NONE = new Modes(false, false);

        Modes {
            if (zen && expert) throw new IllegalArgumentException("Zen and Expert are one at a time");
        }

        /** Zen or Expert: what the menu bar and the tab strip are hidden by. */
        boolean focus() {
            return zen || expert;
        }

        /** Zen switched: on from anything else, off from Zen. */
        Modes toggleZen() {
            return new Modes(!zen, false);
        }

        /** Expert switched: on from anything else, off from Expert. */
        Modes toggleExpert() {
            return new Modes(false, !expert);
        }
    }

    /** The menu bar in the window: when the setting has it and neither Zen nor Expert is on. */
    static boolean menuBar(boolean setting, Modes modes) {
        return setting && !modes.focus();
    }

    /**
     * The tab strip: when the settings and the number of tabs would show it
     * ({@link WindowRules#shouldShowTabBar}) and neither Zen nor Expert is on. The tabs are still
     * there, and still reached by their chords and by name in the palette.
     */
    static boolean tabBar(boolean wouldShow, Modes modes) {
        return wouldShow && !modes.focus();
    }

    /** The status bar: when the setting has it and Zen is not on. Expert keeps it. */
    static boolean statusBar(boolean setting, Modes modes) {
        return setting && !modes.zen();
    }
}
