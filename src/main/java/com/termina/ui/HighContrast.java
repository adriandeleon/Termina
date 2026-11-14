package com.termina.ui;

import java.util.Map;

/**
 * Whether the system's high-contrast mode is on (ACC-09, Pauta 6.0.1).
 *
 * <p>Windows says so through JavaFX's platform preferences, under the key
 * {@value #KEY}. JavaFX applies its own high-contrast stylesheet only over its built-in Modena
 * theme, which the app's own control themes replace, so without following the key the app ignores
 * a setting the person turned on to read the screen at all. While it is on, everything is drawn
 * with the high-contrast theme ({@link Themes#drawn}); when it ends, with the theme that was
 * chosen. No other system reports such a mode this way, and there the answer is always no.
 */
public final class HighContrast {

    /** The platform preference Windows sets while its high-contrast mode is on. */
    public static final String KEY = "Windows.SPI.HighContrast";

    private HighContrast() {}

    /** Whether {@code preferences}, JavaFX's platform preferences, say the mode is on. Pure. */
    public static boolean isOn(Map<?, ?> preferences) {
        return Boolean.TRUE.equals(preferences.get(KEY));
    }
}
