package com.termina.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * The monospace faces installed on this machine.
 *
 * <p>JavaFX exposes no "is this fixed-pitch?" query, so each family is measured: a face is
 * monospace when {@code i} and {@code M} have the same advance. That is the standard test, and it
 * matters here beyond tidiness — the renderer places every glyph on a fixed cell grid, so a
 * proportional face would misalign every column on screen.
 *
 * <p>Measuring a few hundred families costs enough to be worth doing once, so the result is cached.
 */
public final class MonospaceFonts {

    /**
     * Preferred faces, best first. Tried before the measured list so a machine with a good terminal
     * font gets it by default rather than whatever sorts first alphabetically.
     */
    private static final List<String> PREFERRED = List.of(
            "JetBrains Mono",
            "SF Mono",
            "Menlo",
            "Cascadia Mono",
            "Cascadia Code",
            "Consolas",
            "DejaVu Sans Mono",
            "Liberation Mono",
            "Ubuntu Mono",
            "Noto Sans Mono",
            "Monospaced");

    private static List<String> cached;

    private MonospaceFonts() {}

    /** Installed monospace families, preferred ones first, then the rest alphabetically. */
    public static synchronized List<String> available() {
        if (cached != null) return cached;

        List<String> families = Font.getFamilies();
        Set<String> ordered = new LinkedHashSet<>();
        for (String preferred : PREFERRED) {
            if (families.contains(preferred)) ordered.add(preferred);
        }
        List<String> measured = new ArrayList<>();
        for (String family : families) {
            if (ordered.contains(family)) continue;
            if (isMonospace(family)) measured.add(family);
        }
        measured.sort(String.CASE_INSENSITIVE_ORDER);
        ordered.addAll(measured);

        // "Monospaced" is a JavaFX logical family that always resolves to something fixed-pitch,
        // so the list can never come back empty.
        if (ordered.isEmpty()) ordered.add("Monospaced");
        cached = List.copyOf(ordered);
        return cached;
    }

    /** The family to actually use for a stored preference, which may be blank or uninstalled. */
    public static String resolve(String preference) {
        List<String> families = available();
        if (preference != null && !preference.isBlank() && families.contains(preference)) {
            return preference;
        }
        // A font the user chose on another machine — or removed since — falls back rather than
        // silently rendering in a proportional face.
        return families.get(0);
    }

    static boolean isMonospace(String family) {
        Font font = Font.font(family, 14);
        if (font == null) return false;
        // Compare a narrow glyph against a wide one. Equal advances means fixed pitch.
        double narrow = advance("i", font);
        double wide = advance("M", font);
        if (narrow <= 0 || wide <= 0) return false;
        return Math.abs(narrow - wide) < 0.01;
    }

    private static double advance(String text, Font font) {
        Text probe = new Text(text);
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
    }
}
