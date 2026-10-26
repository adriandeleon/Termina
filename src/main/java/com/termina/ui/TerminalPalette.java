package com.termina.ui;

import javafx.scene.paint.Color;

import com.jediterm.terminal.TerminalColor;

/**
 * The colours a terminal draws with: a background, a default foreground, and the sixteen ANSI
 * colours.
 *
 * <p>Indices 16-255 are deliberately <b>not</b> part of a palette. They are a fixed 6x6x6 cube and a
 * 24-step grey ramp defined by xterm, and programs compute those indices arithmetically — a theme
 * that restyled them would break the arithmetic rather than recolour anything.
 *
 * @param background the terminal surface
 * @param foreground text with no explicit colour
 * @param ansi the sixteen ANSI colours, 0-7 normal and 8-15 bright
 */
public record TerminalPalette(Color background, Color foreground, Color[] ansi, Color cursor, Color selection) {

    /** Indices 16-255, identical in every theme. Built once. */
    private static final Color[] EXTENDED = buildExtended();

    public TerminalPalette {
        if (ansi.length != 16) throw new IllegalArgumentException("expected 16 ANSI colours");
        ansi = ansi.clone();
    }

    /**
     * A palette with no cursor or selection colour of its own: the cursor is drawn in the foreground
     * and the selection washed in blue (see {@code TerminalView}). The built-in themes are all this;
     * an imported scheme that names the two keeps them.
     */
    public TerminalPalette(Color background, Color foreground, Color[] ansi) {
        this(background, foreground, ansi, null, null);
    }

    private static Color[] buildExtended() {
        Color[] colors = new Color[240];
        // 16-231: a 6x6x6 cube. The levels are xterm's and are not evenly spaced — the jump from 0
        // to the next is deliberately large so "dark" stays dark.
        int[] levels = {0, 95, 135, 175, 215, 255};
        int index = 0;
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    colors[index++] = Color.rgb(levels[r], levels[g], levels[b]);
                }
            }
        }
        // 232-255: 24 greys from near-black to near-white.
        for (int i = 0; i < 24; i++) {
            int v = 8 + i * 10;
            colors[index++] = Color.rgb(v, v, v);
        }
        return colors;
    }

    /** Resolves a cell's colour, or {@code fallback} when the cell specifies none. */
    public Color resolve(TerminalColor color, Color fallback) {
        if (color == null) return fallback;
        if (color.isIndexed()) {
            int i = color.getColorIndex();
            if (i >= 0 && i < 16) return ansi[i];
            if (i >= 16 && i < 256) return EXTENDED[i - 16];
            return fallback;
        }
        com.jediterm.core.Color rgb = color.toColor();
        return rgb == null ? fallback : TrueColors.of(rgb.getRed(), rgb.getGreen(), rgb.getBlue());
    }

    /**
     * Truecolour values, cached.
     *
     * <p>{@link #resolve} runs twice per style run per frame, and a 24-bit colour used to be a new
     * {@link Color} each time — every frame of a truecolour editor theme allocated two per run of
     * the screen. Direct-mapped and bounded: a program can walk all sixteen million colours, and
     * a collision only costs the allocation this replaces. Unsynchronised by design: a slot holds
     * an immutable object, and a reader checks the colour it found before using it, so a race can
     * only cost an allocation.
     */
    static final class TrueColors {
        private static final int SLOTS = 4096;
        private static final Color[] CACHE = new Color[SLOTS];

        private TrueColors() {}

        static Color of(int red, int green, int blue) {
            int key = (red & 0xff) << 16 | (green & 0xff) << 8 | (blue & 0xff);
            int slot = (key ^ (key >>> 12)) & (SLOTS - 1);
            Color cached = CACHE[slot];
            if (cached != null && matches(cached, key)) return cached;
            Color made = Color.rgb(red & 0xff, green & 0xff, blue & 0xff);
            CACHE[slot] = made;
            return made;
        }

        private static boolean matches(Color color, int key) {
            int r = (int) Math.round(color.getRed() * 255);
            int g = (int) Math.round(color.getGreen() * 255);
            int b = (int) Math.round(color.getBlue() * 255);
            return (r << 16 | g << 8 | b) == key;
        }
    }

    /**
     * Dim (SGR 2), blended toward the background rather than made translucent, so a dim cell over a
     * coloured background does not pick up that background's hue.
     */
    public static Color dim(Color color, Color over) {
        return color.interpolate(over, 0.45);
    }

    @Override
    public Color[] ansi() {
        return ansi.clone();
    }
}
