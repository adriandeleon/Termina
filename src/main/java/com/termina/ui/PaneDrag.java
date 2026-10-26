package com.termina.ui;

import javafx.scene.input.DataFormat;

/**
 * Picking a pane up and dropping it on a tab.
 *
 * <p>A pane has no title bar to grab, and a plain drag inside it is a selection, so the gesture is a
 * drag with a chord held: Cmd+Option+Shift on macOS, Ctrl+Alt+Shift elsewhere. Three modifiers
 * because every smaller set is spoken for — Option or Alt alone is a block selection, Shift alone
 * bypasses a program's mouse reporting, Cmd or Ctrl alone opens a link — and Shift being one of the
 * three keeps the press away from a program that has grabbed the mouse, the way a Shift-drag
 * selection is.
 */
final class PaneDrag {

    /** Identifies our own drag, so a drop from another application is never taken for a pane. */
    static final DataFormat FORMAT = new DataFormat("application/x-termina-pane");

    private PaneDrag() {}

    /** Whether a press with these modifiers picks the pane up. */
    static boolean starts(boolean mac, boolean shift, boolean control, boolean alt, boolean meta) {
        if (!shift || !alt) return false;
        return mac ? meta && !control : control && !meta;
    }
}
