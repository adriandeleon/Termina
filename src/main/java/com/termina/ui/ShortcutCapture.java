package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

/**
 * What a key pressed into the settings window's chord-recording field becomes.
 *
 * <p>A chord is recorded by pressing it, not by spelling it — which means the field has to decide,
 * per press, whether what arrived is a chord at all. A modifier on its own is the start of one and
 * is waited out. A bare letter or digit is refused: bound at the scene level it would be taken from
 * the shell on every press, and "x" opening the palette is not a shortcut anyone meant. Function
 * keys are the exception, because F-keys are chords in their own right (F11 is full screen already).
 */
final class ShortcutCapture {

    /** What a press into the recording field means. */
    enum Verdict {
        /** A modifier on its own, or a key with no code: wait for the rest. */
        IGNORE,
        /** A key the shell would own: refuse, and say why. */
        NEEDS_MODIFIER,
        /** A chord worth binding. */
        OK
    }

    private ShortcutCapture() {}

    /** Decides a press. Shift alone is not a modifier here: Shift+X is a capital letter. Pure. */
    static Verdict decide(KeyCode code, boolean control, boolean alt, boolean meta) {
        if (code == null || code == KeyCode.UNDEFINED || code.isModifierKey()) return Verdict.IGNORE;
        if (code.isFunctionKey()) return Verdict.OK;
        return control || alt || meta ? Verdict.OK : Verdict.NEEDS_MODIFIER;
    }

    /** The chord a press names, with exactly the modifiers held; null for a press that is not one. */
    static KeyCombination combination(KeyEvent e) {
        return combination(e.getCode(), e.isControlDown(), e.isAltDown(), e.isMetaDown(), e.isShiftDown());
    }

    /** Pure form of {@link #combination(KeyEvent)}. */
    static KeyCombination combination(KeyCode code, boolean control, boolean alt, boolean meta, boolean shift) {
        if (decide(code, control, alt, meta) != Verdict.OK) return null;
        List<KeyCombination.Modifier> modifiers = new ArrayList<>();
        if (control) modifiers.add(KeyCombination.CONTROL_DOWN);
        if (alt) modifiers.add(KeyCombination.ALT_DOWN);
        if (meta) modifiers.add(KeyCombination.META_DOWN);
        if (shift) modifiers.add(KeyCombination.SHIFT_DOWN);
        return new KeyCodeCombination(code, modifiers.toArray(KeyCombination.Modifier[]::new));
    }
}
