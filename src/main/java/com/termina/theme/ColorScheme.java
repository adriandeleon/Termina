package com.termina.theme;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/**
 * A terminal colour scheme as another terminal described it: sixteen ANSI colours, a background and
 * a foreground, and the cursor and selection colours where the format has them.
 *
 * <p>Colours are {@code 0xRRGGBB}, so nothing here needs JavaFX and every importer is tested as plain
 * data. Taken verbatim — a ported scheme is that scheme, low-contrast corners and all; see the
 * "ported themes are faithful" convention.
 *
 * @param cursor the cursor's colour, or null where the format has none
 * @param selection the selection's colour, or null where the format has none
 */
public record ColorScheme(
        String name,
        int background,
        int foreground,
        int[] ansi,
        @Nullable Integer cursor,
        @Nullable Integer selection) {

    public ColorScheme {
        if (name == null || name.isBlank()) name = com.termina.i18n.Messages.tr("theme.importedName");
        name = name.strip();
        if (ansi == null || ansi.length != 16) throw new IllegalArgumentException("a scheme has 16 ANSI colours");
        ansi = ansi.clone();
    }

    @Override
    public int[] ansi() {
        return ansi.clone();
    }

    /** {@code #rrggbb}. */
    public static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xffffff);
    }

    /** {@code 0xRRGGBB} from 0..1 components, rounded as every terminal that writes these rounds. */
    static int rgb(double red, double green, double blue) {
        return channel(red) << 16 | channel(green) << 8 | channel(blue);
    }

    private static int channel(double value) {
        if (Double.isNaN(value)) return 0;
        return (int) Math.round(Math.max(0, Math.min(1, value)) * 255);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ColorScheme s
                && s.name.equals(name)
                && s.background == background
                && s.foreground == foreground
                && Arrays.equals(s.ansi, ansi)
                && java.util.Objects.equals(s.cursor, cursor)
                && java.util.Objects.equals(s.selection, selection);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, background, foreground, Arrays.hashCode(ansi), cursor, selection);
    }
}
