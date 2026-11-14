package com.termina.ui;

import javafx.scene.input.KeyCode;

/**
 * Which key flips a settings switch (KBD-18, Pauta 6.0.1). On its own, away from {@link
 * SettingSwitch}: that is a control, and a control's class cannot be touched before the toolkit
 * has started, which a test of a rule should never need.
 */
final class SwitchKeys {

    private SwitchKeys() {}

    /** An unmodified Space or Enter flips it; a chord is somebody else's. */
    static boolean toggles(KeyCode code, boolean modified) {
        return !modified && (code == KeyCode.SPACE || code == KeyCode.ENTER);
    }
}
