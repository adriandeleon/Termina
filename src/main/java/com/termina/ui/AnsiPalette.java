package com.termina.ui;

import com.jediterm.terminal.TerminalColor;
import javafx.scene.paint.Color;

/**
 * Resolves a {@link TerminalColor} to a JavaFX colour.
 *
 * <p>A terminal colour is either indexed (0-255) or direct RGB (a "truecolor" SGR sequence).
 * Indices 0-15 are the theme's own ANSI colours; 16-231 are a fixed 6x6x6 cube and 232-255 a
 * 24-step grey ramp, both defined by xterm and identical in every terminal — programs compute
 * those indices arithmetically, so they are not ours to restyle.
 */
public final class AnsiPalette {

    /** The 16 ANSI colours. This one is a mild, dark-background-friendly set. */
    private static final Color[] ANSI = {
        Color.rgb(0x1c, 0x1f, 0x26), // 0 black
        Color.rgb(0xe0, 0x60, 0x60), // 1 red
        Color.rgb(0x6f, 0xc2, 0x76), // 2 green
        Color.rgb(0xd8, 0xa6, 0x57), // 3 yellow
        Color.rgb(0x62, 0x9f, 0xd8), // 4 blue
        Color.rgb(0xb1, 0x83, 0xd6), // 5 magenta
        Color.rgb(0x4f, 0xb8, 0xc0), // 6 cyan
        Color.rgb(0xc3, 0xc7, 0xd1), // 7 white
        Color.rgb(0x54, 0x5a, 0x69), // 8 bright black
        Color.rgb(0xf0, 0x7c, 0x7c), // 9 bright red
        Color.rgb(0x8a, 0xd9, 0x91), // 10 bright green
        Color.rgb(0xef, 0xc0, 0x74), // 11 bright yellow
        Color.rgb(0x7f, 0xb8, 0xef), // 12 bright blue
        Color.rgb(0xc9, 0x9e, 0xeb), // 13 bright magenta
        Color.rgb(0x6d, 0xd3, 0xdb), // 14 bright cyan
        Color.rgb(0xe8, 0xeb, 0xf0), // 15 bright white
    };

    /** Default foreground/background when a cell carries no explicit colour. */
    public static final Color DEFAULT_FOREGROUND = Color.rgb(0xc3, 0xc7, 0xd1);

    public static final Color DEFAULT_BACKGROUND = Color.rgb(0x14, 0x16, 0x1c);

    /** Indices 16-255, precomputed once: the cube and the grey ramp. */
    private static final Color[] EXTENDED = buildExtended();

    private AnsiPalette() {}

    private static Color[] buildExtended() {
        Color[] colors = new Color[256];
        System.arraycopy(ANSI, 0, colors, 0, 16);
        // 16-231: 6x6x6 cube. The level steps are xterm's, not evenly spaced — the gap from 0 to
        // the next level is deliberately large so "dark" stays dark.
        int[] levels = {0, 95, 135, 175, 215, 255};
        int index = 16;
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

    /** Resolves a colour, or {@code fallback} when the cell specifies none. */
    public static Color resolve(TerminalColor color, Color fallback) {
        if (color == null) return fallback;
        if (color.isIndexed()) {
            int i = color.getColorIndex();
            return i >= 0 && i < EXTENDED.length ? EXTENDED[i] : fallback;
        }
        com.jediterm.core.Color rgb = color.toColor();
        if (rgb == null) return fallback;
        return Color.rgb(rgb.getRed(), rgb.getGreen(), rgb.getBlue());
    }

    /**
     * Dim (SGR 2) rendered by blending toward the background rather than lowering opacity, so a
     * dim cell drawn over a coloured background does not pick up that background's hue.
     */
    public static Color dim(Color color, Color background) {
        return color.interpolate(background, 0.45);
    }
}
