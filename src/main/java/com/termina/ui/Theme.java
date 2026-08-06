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
            });

    private final String id;
    private final String displayName;
    private final boolean dark;
    private final TerminalPalette palette;

    Theme(String id, String displayName, boolean dark, Color background, Color foreground, String[] ansi) {
        this.id = id;
        this.displayName = displayName;
        this.dark = dark;
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
        var url = Theme.class.getResource("/com/termina/styles/" + id + ".css");
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
