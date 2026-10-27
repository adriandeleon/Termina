package com.termina.ui;

import java.util.EnumSet;
import java.util.Set;

import javafx.scene.input.MouseButton;

/**
 * Which held buttons the running program was told about, so it hears the drag and the release of a
 * press it saw, and of no other.
 *
 * <p>A press can be kept from the program for several reasons — it opened a link, it dismissed the
 * context menu, Shift bypassed reporting, it landed on the scrollbar — and each used to leave the
 * release to be reported anyway: a mouse-aware program was told a button went up that it never saw
 * go down, which some take as a click. The opposite case matters as much: a press that was reported
 * is owed its release even if Shift is down by then, or the program believes the button is still
 * held.
 *
 * <p>Per button, since two can be held at once.
 */
final class ReportedPresses {

    private final Set<MouseButton> reported = EnumSet.noneOf(MouseButton.class);

    /** A press, and whether the program was told of it. */
    void pressed(MouseButton button, boolean toldProgram) {
        if (toldProgram) reported.add(button);
        else reported.remove(button);
    }

    /** Whether a drag with this button held belongs to the program. */
    boolean owesDrag(MouseButton button) {
        return reported.contains(button);
    }

    /** Whether this button's release goes to the program. Answers once: the press is then over. */
    boolean owesRelease(MouseButton button) {
        return reported.remove(button);
    }
}
