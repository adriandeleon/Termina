package com.termina.ui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrast and colour-token rules for the window themes and the app's own stylesheets (ICON-09 to
 * ICON-12, Pauta 6.0.1). Pure: it reads the CSS text and does the WCAG arithmetic, and
 * {@code Color.web} needs no toolkit. Editora's test of the same name, on the two themes Termina
 * shares with it; the walk of every rule for subtle text is the one the rule asks for and
 * Editora's does not make yet.
 *
 * <p>{@code ThemeTest} is the terminal palettes' test; this one is the chrome's.
 */
class StyleContrastTest {

    private static final String STYLES = "/com/termina/styles/";

    /** The two window themes: Editora Light and Editora Dark. */
    private static final List<String> THEMES = List.of("editora-light", "editora-dark");

    /** The app's own sheets, as apart from the themes: every rule in these is walked. */
    private static final List<String> OWN_SHEETS = List.of("app.css", "settings.css", "ui-font.css");

    private static final List<String> ROLES = List.of("accent", "success", "warning", "danger");

    private static String read(String path) throws Exception {
        try (var in = StyleContrastTest.class.getResourceAsStream(STYLES + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
        }
    }

    /** The {@code -color-*} tokens of a theme's {@code .root} palette. */
    private static Map<String, String> palette(String theme) throws Exception {
        String css = read(theme + ".css");
        int start = css.indexOf(".root {");
        String block = css.substring(start, css.indexOf("\n}", start));
        Map<String, String> tokens = new HashMap<>();
        Matcher m = Pattern.compile("(-color-[a-z0-9-]+)\\s*:\\s*([^;]+);").matcher(block);
        while (m.find()) tokens.put(m.group(1), m.group(2).trim());
        return tokens;
    }

    /** A token's colour, aliases followed. */
    private static Color color(Map<String, String> palette, String token) {
        String value = palette.get(token);
        assertTrue(value != null, "no " + token + " in the theme");
        while (palette.containsKey(value)) value = palette.get(value);
        return Color.web(value);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** One rule of a sheet: its selectors, each on one line, and its declarations. */
    private record Rule(String sheet, List<String> selectors, String body) {}

    /** Every rule of the app's own sheets. */
    private static List<Rule> ownRules() throws Exception {
        List<Rule> rules = new ArrayList<>();
        for (String sheet : OWN_SHEETS) {
            Matcher m = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(read(sheet));
            while (m.find()) {
                List<String> selectors = new ArrayList<>();
                for (String s : m.group(1).split(",")) selectors.add(s.strip().replaceAll("\\s+", " "));
                rules.add(new Rule(sheet, selectors, m.group(2)));
            }
        }
        return rules;
    }

    @Test
    void theWalkReadsEveryOwnSheet() throws Exception {
        // A walk that found no rules would pass everything.
        List<Rule> rules = ownRules();
        for (String sheet : OWN_SHEETS) {
            assertTrue(rules.stream().anyMatch(rule -> rule.sheet().equals(sheet)), sheet + " has no rules");
        }
        assertTrue(rules.size() > 100, "rules walked: " + rules.size());
        assertEquals(21.0, contrast(Color.BLACK, Color.WHITE), 0.001);
    }

    /** ICON-09: secondary text is the muted role, 4.5:1 on every ground it is set on. */
    @Test
    void mutedTextReachesAaOnTheDefaultSubtleAndOverlayGrounds() throws Exception {
        for (String theme : THEMES) {
            Map<String, String> p = palette(theme);
            for (String ground : List.of("-color-bg-default", "-color-bg-subtle", "-color-bg-overlay")) {
                double ratio = contrast(color(p, "-color-fg-muted"), color(p, ground));
                assertTrue(ratio >= 4.5, theme + ": fg-muted on " + ground + " is " + ratio);
            }
            // And the subtle role is what the rule says it is: under AA, so not for text to be read.
            assertTrue(contrast(color(p, "-color-fg-subtle"), color(p, "-color-bg-default")) < 4.5, theme);
        }
    }

    /**
     * The selectors that may set text in the subtle role: disabled and decorative ones only. A rule
     * of any other selector that does fails, so a new one is a decision made here.
     */
    private static final Set<String> SUBTLE_ALLOWED = Set.of(
            // A disabled menu row's icon, dimmed with its label.
            ".menu-item:disabled .menu-icon");

    /** ICON-09: no rule of the app's own sheets sets readable text in the subtle role. */
    @Test
    void noOwnRuleSetsReadableTextInTheSubtleRole() throws Exception {
        Pattern subtleInk = Pattern.compile("-fx-(?:text-fill|fill)\\s*:\\s*-color-fg-subtle\\b");
        List<String> wrong = new ArrayList<>();
        int allowed = 0;
        for (Rule rule : ownRules()) {
            if (!subtleInk.matcher(rule.body()).find()) continue;
            for (String selector : rule.selectors()) {
                if (SUBTLE_ALLOWED.contains(selector)) allowed++;
                else wrong.add(rule.sheet() + " " + selector);
            }
        }
        assertEquals(List.of(), wrong, "text set in -color-fg-subtle, which is under 4.5:1");
        // The list names what exists: an entry no rule matches any more has to go.
        assertEquals(SUBTLE_ALLOWED.size(), allowed, "an allowed selector no longer sets subtle ink");
    }

    /** ICON-10: text in a role's tint reads on that role's subtle fill and on both grounds. */
    @Test
    void tintedTextReachesAaOnItsOwnFillAndOnBothGrounds() throws Exception {
        for (String theme : THEMES) {
            Map<String, String> p = palette(theme);
            for (String role : ROLES) {
                Color fg = color(p, "-color-" + role + "-fg");
                for (String ground : List.of("-color-" + role + "-subtle", "-color-bg-subtle", "-color-bg-default")) {
                    double ratio = contrast(fg, color(p, ground));
                    assertTrue(ratio >= 4.5, theme + ": " + role + "-fg on " + ground + " is " + ratio);
                }
            }
        }
    }

    /** ICON-11: ink on an emphasis fill is the theme's on-emphasis colour, never a fixed white. */
    @Test
    void inkOnAnEmphasisFillIsTheThemesOnEmphasisColour() throws Exception {
        for (String theme : THEMES) {
            Map<String, String> p = palette(theme);
            for (String role : ROLES) {
                double ratio = contrast(color(p, "-color-fg-emphasis"), color(p, "-color-" + role + "-emphasis"));
                assertTrue(ratio >= 4.5, theme + ": fg-emphasis on " + role + "-emphasis is " + ratio);
            }
        }
        // Every own rule that paints an emphasis fill and sets its ink sets it to that token.
        Pattern emphasisFill = Pattern.compile("-fx-background-color\\s*:[^;]*-color-[a-z]+-emphasis");
        Pattern ink = Pattern.compile("-fx-(?:text-fill|fill)\\s*:\\s*([^;]+);");
        for (Rule rule : ownRules()) {
            if (!emphasisFill.matcher(rule.body()).find()) continue;
            Matcher m = ink.matcher(rule.body());
            while (m.find()) {
                assertEquals("-color-fg-emphasis", m.group(1).trim(), rule.sheet() + " " + rule.selectors());
            }
        }
        // And no own rule reaches for a fixed white or black ink at all.
        Pattern fixedInk = Pattern.compile("-fx-(?:text-fill|fill)\\s*:\\s*(white|black|#fff(?:fff)?|#000(?:000)?)\\b");
        for (Rule rule : ownRules()) {
            assertFalse(fixedInk.matcher(rule.body()).find(), rule.sheet() + " " + rule.selectors());
        }
    }

    /** JavaFX's ladder(): the stops' colours, interpolated at the reference's brightness (.3r+.59g+.11b). */
    private static Color ladder(Color ref, List<Double> offsets, List<Color> colors) {
        double b = 0.3 * ref.getRed() + 0.59 * ref.getGreen() + 0.11 * ref.getBlue();
        if (b <= offsets.get(0)) return colors.get(0);
        for (int i = 1; i < offsets.size(); i++) {
            double from = offsets.get(i - 1);
            double to = offsets.get(i);
            if (b <= to) return colors.get(i - 1).interpolate(colors.get(i), (b - from) / (to - from));
        }
        return colors.get(colors.size() - 1);
    }

    /**
     * ICON-06, MENU-15: the keyboard's colour is one token, a ladder over the theme's ground, and
     * reads at 4.5:1 on the default and subtle surfaces of both themes, the palette chip's tint
     * included; and the chords are drawn in it wherever the app draws one.
     */
    @Test
    void theKeyboardColourReadsOnBothThemesAndEveryChordIsDrawnInIt() throws Exception {
        String app = read("app.css");
        Matcher decl = Pattern.compile("-state-key:\\s*ladder\\(-color-bg-default,([^;]+)\\);")
                .matcher(app);
        assertTrue(decl.find(), "-state-key is a ladder of the theme's ground");
        List<Double> offsets = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        Matcher stop = Pattern.compile("(#[0-9a-fA-F]{6})\\s+([0-9.]+)%").matcher(decl.group(1));
        while (stop.find()) {
            colors.add(Color.web(stop.group(1)));
            offsets.add(Double.parseDouble(stop.group(2)) / 100);
        }
        assertTrue(colors.size() >= 2, "stops read: " + colors.size());
        for (String theme : THEMES) {
            Map<String, String> p = palette(theme);
            Color ink = ladder(color(p, "-color-bg-default"), offsets, colors);
            for (String surface : List.of("-color-bg-default", "-color-bg-subtle", "-color-bg-overlay")) {
                Color ground = color(p, surface);
                Color chip = ground.interpolate(ink, 0.15); // the chip's tint over that surface
                double worst = Math.min(contrast(ink, ground), contrast(ink, chip));
                assertTrue(worst >= 4.5, theme + ": the keyboard colour on " + surface + " is " + worst);
            }
        }
        // The menus' chord column, in the bar and in the right-click menus, and the palette's chords.
        for (String selector : List.of(
                ".menu-item .menu-item-chord",
                ".context-menu .menu-item > .accelerator-text",
                ".command-palette .list-cell .keybinding",
                ".command-palette .palette-prefix")) {
            boolean found = false;
            for (Rule rule : ownRules()) {
                if (!rule.selectors().contains(selector)) continue;
                found = true;
                assertTrue(rule.body().contains("-fx-text-fill: -state-key"), selector + ": " + rule.body());
            }
            assertTrue(found, "no rule for " + selector);
        }
        // The two menu columns are the same size, in pixels: a menu's font is not its opener's.
        for (String selector : List.of(".menu-item .menu-item-chord", ".context-menu .menu-item > .accelerator-text")) {
            for (Rule rule : ownRules()) {
                if (rule.selectors().contains(selector)) {
                    assertTrue(rule.body().contains("-fx-font-size: 11px"), selector + ": " + rule.body());
                }
            }
        }
    }

    /** ICON-12: one focus ring colour, named once on the root, 3:1 on every surface. */
    @Test
    void theFocusRingIsAtLeastThreeToOneOnEverySurface() throws Exception {
        List<String> named = new ArrayList<>();
        for (Rule rule : ownRules()) {
            Matcher m = Pattern.compile("-focus-ring\\s*:\\s*([^;]+);").matcher(rule.body());
            while (m.find()) named.add(rule.selectors() + " " + m.group(1).trim());
        }
        assertEquals(List.of("[.root] -color-accent-emphasis"), named, "the ring is named once, on the root");
        for (String theme : THEMES) {
            Map<String, String> p = palette(theme);
            for (String surface :
                    List.of("-color-bg-default", "-color-bg-subtle", "-color-bg-inset", "-color-bg-overlay")) {
                double ratio = contrast(color(p, "-color-accent-emphasis"), color(p, surface));
                assertTrue(ratio >= 3.0, theme + ": ring on " + surface + " is " + ratio);
            }
        }
    }
}
