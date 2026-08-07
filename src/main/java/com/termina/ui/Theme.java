package com.termina.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.paint.Color;

/**
 * A theme: the control stylesheet for the application's own windows, plus the palette the terminal
 * surface draws with.
 *
 * <p>Both are "Caret &amp; Ink", carried over from Editora — Caret teal on Ink navy. The control
 * stylesheets are AtlantaFX-derived and self-contained (their only external references are modena
 * assets that resolve out of {@code javafx.controls}), so they are applied with
 * {@code Application.setUserAgentStylesheet} directly and AtlantaFX itself is not a dependency here.
 *
 * <p>The ANSI colours are derived from the matching Editora <em>editor</em> theme's syntax palette:
 * keyword becomes red, string blue, escape green, type yellow, function magenta, and the Caret
 * accent becomes cyan. That keeps a shell's `ls` colours recognisably the same family as the
 * editor's code colours.
 */
public enum Theme {
    EDITORA_DARK(
            "editora-dark",
            "Editora Dark",
            true,
            "editora-dark",
            Color.web("#171a24"), // the editor surface, a shade below the window background
            Color.web("#e8eaf3"),
            new String[] {
                "#12141d", // 0 black — Abyss
                "#ff7b72", // 1 red — keyword
                "#7ee787", // 2 green — escape/tag
                "#ffa657", // 3 yellow — type/annotation
                "#79c0ff", // 4 blue — number/constant
                "#d2a8ff", // 5 magenta — function
                "#43dec5", // 6 cyan — Caret teal
                "#c6cad8", // 7 white — held below bright white so the two are distinguishable
                "#6c7288", // 8 bright black
                "#ffa198", // 9 bright red
                "#a2f0aa", // 10 bright green
                "#ffc793", // 11 bright yellow
                "#a5d6ff", // 12 bright blue
                "#e2c5ff", // 13 bright magenta
                "#6fdcc9", // 14 bright cyan
                "#e8eaf3", // 15 bright white
            }),

    EDITORA_LIGHT(
            "editora-light",
            "Editora Light",
            false,
            "editora-light",
            Color.web("#ffffff"),
            Color.web("#1b1e2a"), // Ink
            new String[] {
                "#1b1e2a", // 0 black
                "#cf222e", // 1 red
                "#1a7f37", // 2 green
                "#953800", // 3 yellow — rendered as a dark amber, since yellow on white is unreadable
                "#0550ae", // 4 blue
                "#8250df", // 5 magenta
                "#0e8577", // 6 cyan — Caret teal, darkened for a light ground
                // On a light background ANSI "white" has to be dark or every program that sets it
                // writes invisible text. Every light terminal theme does this.
                "#575d6e", // 7 white
                "#8b91a3", // 8 bright black
                "#a40e26", // 9 bright red
                "#116329", // 10 bright green
                "#7d4e00", // 11 bright yellow
                "#0a3069", // 12 bright blue
                "#6639ba", // 13 bright magenta
                "#0b6e62", // 14 bright cyan
                "#1b1e2a", // 15 bright white
            }),

    /**
     * macOS Terminal's "Clear Dark", colours taken from Apple's own profile
     * ({@code Terminal.app/Contents/Resources/Initial Settings/Clear Dark.terminal}) rather than
     * matched by eye.
     *
     * <p>Apple ships it at 95% opacity over a blurred desktop. We have no window transparency, so
     * it renders opaque — the colours are exact, the translucency is not reproduced.
     */
    CLEAR_DARK(
            "clear-dark", "Clear Dark", true, "editora-dark", Color.web("#191d27"), Color.web("#e0e0e0"), new String[] {
                "#35424c", "#b45648", "#6caa71", "#c4ac62",
                "#6d96b4", "#bd7bcd", "#7ccbcd", "#dee5eb",
                "#465c6d", "#df6c5a", "#79be7e", "#e5c872",
                "#67b5ed", "#d389e5", "#84dde0", "#e5eff5",
            }),

    /**
     * macOS Terminal's "Clear Light", likewise taken from Apple's profile.
     *
     * <p>Apple ships it at 93% opacity; rendered opaque here for the same reason.
     *
     * <p>Its ANSI white (7) and bright white (15) sit close to the white background — see
     * {@code ThemeTest}. That is Apple's choice, kept rather than corrected, because the point of a
     * ported theme is that it matches the terminal it was ported from.
     */
    CLEAR_LIGHT(
            "clear-light",
            "Clear Light",
            false,
            "editora-light",
            Color.web("#ffffff"),
            Color.web("#2d3840"),
            new String[] {
                "#2d3840", "#b45648", "#6caa71", "#c4ac62",
                "#5685a8", "#ad64be", "#69c6c9", "#c1c8cc",
                "#506573", "#df6c5a", "#79be7e", "#e5c872",
                "#49a2e1", "#d389e5", "#77e1e5", "#d8e1e7",
            });

    private final String id;
    private final String displayName;
    private final boolean dark;
    private final String chrome;
    private final TerminalPalette palette;

    /**
     * @param chrome the control stylesheet to use for the application's own windows. A ported
     *     terminal palette brings no opinion about how a settings window should look, so it
     *     borrows whichever Caret &amp; Ink sheet matches its brightness. That also keeps the two
     *     vendored 165 KB stylesheets as the only two.
     */
    Theme(
            String id,
            String displayName,
            boolean dark,
            String chrome,
            Color background,
            Color foreground,
            String[] ansi) {
        this.id = id;
        this.displayName = displayName;
        this.dark = dark;
        this.chrome = chrome;
        Color[] colors = new Color[ansi.length];
        for (int i = 0; i < ansi.length; i++) colors[i] = Color.web(ansi[i]);
        this.palette = new TerminalPalette(background, foreground, colors);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isDark() {
        return dark;
    }

    public TerminalPalette palette() {
        return palette;
    }

    /** The control stylesheet URL, for {@code Application.setUserAgentStylesheet}. */
    public String stylesheet() {
        var url = Theme.class.getResource("/com/termina/styles/" + chrome + ".css");
        if (url == null) throw new IllegalStateException("missing stylesheet for theme " + id);
        return url.toExternalForm();
    }

    public static Theme byId(String id, Theme fallback) {
        for (Theme t : values()) {
            if (t.id.equals(id)) return t;
        }
        return fallback;
    }

    /** Display name to theme, in declaration order — what a picker shows. */
    public static Map<String, Theme> byDisplayName() {
        Map<String, Theme> map = new LinkedHashMap<>();
        for (Theme t : values()) map.put(t.displayName, t);
        return map;
    }
}
