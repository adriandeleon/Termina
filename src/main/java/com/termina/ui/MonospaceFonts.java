package com.termina.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

import org.jspecify.annotations.Nullable;

/**
 * The monospace faces installed on this machine.
 *
 * <p>JavaFX exposes no "is this fixed-pitch?" query, so each family is measured: a face is
 * monospace when {@code i} and {@code M} have the same advance. That is the standard test, and it
 * matters here beyond tidiness — the renderer places every glyph on a fixed cell grid, so a
 * proportional face would misalign every column on screen.
 *
 * <p>Measuring a few hundred families costs enough to be worth doing once, so the result is cached
 * — and it is done only for the font picker. Opening a tab needs one family, not the list: the
 * scan used to run from the first terminal's field initialiser, on the FX thread ahead of the first
 * paint, and measured 98ms for 185 families on a Linux desktop. {@link #resolve} answers from the
 * one family it was asked about.
 */
public final class MonospaceFonts {

    /**
     * Preferred faces, best first. Tried before the measured list so a machine with a good terminal
     * font gets it by default rather than whatever sorts first alphabetically.
     *
     * <p>The bundled families lead, because they are the only ones guaranteed to be there — a
     * default naming a system font gives a different terminal on each platform.
     */
    private static final List<String> PREFERRED = concat(
            Fonts.BUNDLED_MONO,
            List.of(
                    "SF Mono",
                    "Menlo",
                    "Cascadia Mono",
                    "Cascadia Code",
                    "Consolas",
                    "DejaVu Sans Mono",
                    "Liberation Mono",
                    "Ubuntu Mono",
                    "Noto Sans Mono",
                    "Monospaced"));

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static @Nullable List<String> cached;

    /** Installed family names, which {@link #resolve} needs and the full scan also starts from. */
    private static @Nullable Set<String> installed;

    /** Families already measured, so resolving the same stored preference per tab is free. */
    private static final java.util.Map<String, Boolean> MEASURED = new java.util.HashMap<>();

    private MonospaceFonts() {}

    /** Installed monospace families, preferred ones first, then the rest alphabetically. */
    public static synchronized List<String> available() {
        // The picker lists every bundled family, including the ones deferred past the first frame.
        // Never read on the FX thread, though (PERF-01, Pauta 6.0.1): a caller there gets the
        // families registered so far, and the rest are registered on a worker, which forgets this
        // list so the next caller sees them.
        if (javafx.application.Platform.isFxApplicationThread()) {
            if (!Fonts.restLoaded()) Fonts.loadRestInBackground(MonospaceFonts::forget);
        } else if (Fonts.loadRest()) {
            forget();
        }
        if (cached != null) return cached;

        Set<String> families = installed();
        Set<String> ordered = new LinkedHashSet<>();
        for (String preferred : PREFERRED) {
            if (families.contains(preferred)) ordered.add(preferred);
        }
        List<String> measured = new ArrayList<>();
        for (String family : families) {
            if (ordered.contains(family) || family.equals(Fonts.ICONS)) continue;
            if (measuredMonospace(family)) measured.add(family);
        }
        measured.sort(String.CASE_INSENSITIVE_ORDER);
        ordered.addAll(measured);

        // "Monospaced" is a JavaFX logical family that always resolves to something fixed-pitch,
        // so the list can never come back empty.
        if (ordered.isEmpty()) ordered.add("Monospaced");
        cached = List.copyOf(ordered);
        return cached;
    }

    /**
     * Makes the list on a worker, after the first frame: the deferred families registered and
     * every installed one measured, so the font picker's first opening only reads it (PERF-01).
     * Measuring is two text layouts a family, and on a machine with a few hundred families the
     * first Settings window spent a quarter of a second of the FX thread on it, which the stall
     * sampler showed.
     *
     * @return the worker, for a test to wait on
     */
    public static Thread warmInBackground() {
        Thread worker = new Thread(MonospaceFonts::available, "termina-fonts");
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    /** Whether the list is made, so asking for it measures nothing. */
    static synchronized boolean isListed() {
        return cached != null;
    }

    /** The family to actually use for a stored preference, which may be blank or uninstalled. */
    public static synchronized String resolve(String preference) {
        // A bundled family the first frame did not need — a profile's — is registered on first use.
        if (preference != null && Fonts.ensure(preference)) forget();
        // The same answer the full list would give — a preferred family is trusted, any other must
        // measure as monospace — without measuring every other family to get it.
        if (preference != null
                && !preference.isBlank()
                && installed().contains(preference)
                && !preference.equals(Fonts.ICONS)) {
            if (PREFERRED.contains(preference) || measuredMonospace(preference)) return preference;
        }
        // A font the user chose on another machine — or removed since — falls back rather than
        // silently rendering in a proportional face.
        return defaultFamily();
    }

    /** What {@code available().get(0)} would be: the best preferred family that is installed. */
    public static synchronized String defaultFamily() {
        if (cached != null) return cached.get(0);
        for (String preferred : PREFERRED) {
            if (installed().contains(preferred)) return preferred;
        }
        return available().get(0);
    }

    /** Drops what was read of the installed families, after more were registered. */
    public static synchronized void forget() {
        cached = null;
        installed = null;
    }

    private static Set<String> installed() {
        if (installed == null) {
            // Plus the bundled families loaded since JavaFX first listed its own: see
            // Fonts.loadedFamilies.
            Set<String> all = new java.util.HashSet<>(Font.getFamilies());
            all.addAll(Fonts.loadedFamilies());
            installed = Set.copyOf(all);
        }
        return installed;
    }

    private static boolean measuredMonospace(String family) {
        return MEASURED.computeIfAbsent(family, MonospaceFonts::isMonospace);
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
