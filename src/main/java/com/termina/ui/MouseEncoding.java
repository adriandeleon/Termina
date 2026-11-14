package com.termina.ui;

import com.jediterm.terminal.emulator.mouse.MouseButtonCodes;
import com.jediterm.terminal.emulator.mouse.MouseButtonModifierFlags;

/**
 * Maps a mouse button and modifiers onto the button codes and modifier flags JediTerm's mouse
 * encoder expects. Imports no JavaFX type (QUAL-05, Pauta 2.0.2): the view reads the event and
 * passes the button by its {@code MouseButton} name and the modifiers as they are.
 *
 * <p>Only this translation is ours. The escape sequences themselves are JediTerm's — there are four
 * incompatible wire formats (X10, UTF-8, URXVT, SGR) and the active one is chosen by the program
 * running in the terminal, so encoding by hand would mean tracking that state too.
 *
 * <p>The mapping mirrors JediTerm's own AWT adapter, which is the implementation its encoder was
 * written against. Two consequences are worth stating because they look like mistakes:
 *
 * <ul>
 *   <li>The <b>motion flag is not set here</b>. The encoder derives it from the event type; adding
 *       it would set the bit twice and report a drag as a different button.
 *   <li>The <b>scroll codes read backwards</b>. {@code SCROLLDOWN} is sent when the wheel turns
 *       <em>up</em>, because the names describe which way the content moves, not the wheel.
 * </ul>
 */
final class MouseEncoding {

    private MouseEncoding() {}

    /** Button code for a press, release, or drag; {@code NONE} for a button we do not report. */
    static int buttonCode(String button) {
        return switch (button) {
            case "PRIMARY" -> MouseButtonCodes.LEFT;
            case "MIDDLE" -> MouseButtonCodes.MIDDLE;
            // JediTerm's own adapter drops this one, because its Swing UI opens a context menu on
            // right-click. Ours yields to a program that asked for the mouse, and TUIs that use the
            // right button (Midnight Commander, some file pickers) are better served by reporting it.
            case "SECONDARY" -> MouseButtonCodes.RIGHT;
            default -> MouseButtonCodes.NONE;
        };
    }

    /**
     * Wheel direction as a button code.
     *
     * @param deltaY JavaFX scroll delta: positive when the wheel turns up/away from the user.
     */
    static int wheelButtonCode(double deltaY) {
        if (deltaY == 0) return MouseButtonCodes.NONE;
        return deltaY > 0 ? MouseButtonCodes.SCROLLDOWN : MouseButtonCodes.SCROLLUP;
    }

    /** Modifier bits. Shift is included for completeness; callers treat it as a reporting bypass. */
    static int modifierFlags(boolean control, boolean shift, boolean meta) {
        int flags = 0;
        if (control) flags |= MouseButtonModifierFlags.MOUSE_BUTTON_CTRL_FLAG;
        if (shift) flags |= MouseButtonModifierFlags.MOUSE_BUTTON_SHIFT_FLAG;
        if (meta) flags |= MouseButtonModifierFlags.MOUSE_BUTTON_META_FLAG;
        return flags;
    }

    /** Largest scroll a single event may report, so a flung trackpad cannot spam the shell. */
    static final int MAX_SCROLL_UNITS = 10;

    /**
     * Converts a pixel scroll delta into whole lines.
     *
     * <p>Sign follows AWT's convention (positive means scrolling down), since that is what the
     * encoder and the alternate-screen arrow-key fallback were written against. A delta smaller
     * than one line still yields one, or a slow trackpad drag would scroll nothing at all.
     */
    static int unitsToScroll(double deltaY, double lineHeight) {
        if (deltaY == 0) return 0;
        double lines = lineHeight > 0 ? -deltaY / lineHeight : -Math.signum(deltaY);
        int units = (int) Math.round(lines);
        if (units == 0) units = lines > 0 ? 1 : -1;
        return Math.max(-MAX_SCROLL_UNITS, Math.min(MAX_SCROLL_UNITS, units));
    }
}
