package com.termina.ui;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.scene.text.Font;

/**
 * The face that draws private-use code points: the Nerd Font icons {@code eza}, {@code lsd} and
 * every powerline prompt put on screen.
 *
 * <p>No terminal face we bundle has a glyph there, so JavaFX goes down its fallback list — fontconfig's
 * ordering for <em>sans</em>, which is about text, not icons — and takes the first face that maps
 * the code point. For most icons that is the Nerd Font. But a private-use code point has no agreed
 * meaning, and other fonts put their own glyphs there: on a machine with the Microsoft core fonts
 * and MathJax installed, Arial claims U+F016 and MathJax_WinIE6 claims U+E271, both ahead of the
 * Nerd Font in that list. {@code eza} gives a symlink to a file those two icons, so
 * {@code vmlinuz} and {@code initrd.img} came out with a stray accent beside them while every
 * other icon in the listing was right.
 *
 * <p>Asking for a Nerd Font by name for these code points takes the list out of it. One is always
 * there to ask for — {@link Fonts#ICONS} is bundled, so a machine with no Nerd Font installed gets
 * the icons too rather than whatever its fallback list holds. When the terminal face is itself a
 * Nerd Font it covers them already.
 */
final class IconFont {

    /**
     * A Nerd Fonts family name, in either naming scheme: {@code Hack Nerd Font Mono}, or the short
     * {@code JetBrainsMono NFM} that newer releases put in the family field. Anything after the
     * suffix — {@code JetBrainsMono NF ExtraBold} — is a weight registered as a family of its own.
     */
    private static final Pattern NERD = Pattern.compile("(.+?) (?:Nerd Font( Mono| Propo)?|NF([MP])?)");

    private IconFont() {}

    /**
     * Whether {@code codePoint} is private use, in the BMP or in either supplementary plane.
     * JediTerm's {@link com.jediterm.terminal.util.CharUtils#DWC} filler is one of these, so the
     * renderer has to have dealt with it first.
     */
    static boolean isPrivateUse(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }

    /**
     * Whether {@code codePoint} is one of Powerline's separators or their extras. These are drawn
     * to the height of the line, so that a prompt's segments meet, and are never shrunk to a cell.
     */
    static boolean isPowerline(int codePoint) {
        return codePoint >= 0xE0A0 && codePoint <= 0xE0D7;
    }

    /** The bundled face's icons are this many em wide: squares, since it has no letters to match. */
    private static final double SYMBOLS_ICON_EM = 1.0;

    /** Where the centre of the bundled face's em box sits above its baseline: 0.8 em up, 0.2 down. */
    private static final double SYMBOLS_EM_CENTRE = 0.3;

    /** The size to draw the bundled face at for its icons to be one cell wide. */
    static double cellSize(double charWidth) {
        return charWidth / SYMBOLS_ICON_EM;
    }

    /**
     * How far below the text baseline to draw the bundled face at {@code iconSize} for its icons
     * to sit centred in the row. Centred on the row rather than left on the text's baseline, where
     * an icon smaller than the text would sit low, beside the letters' feet.
     */
    static double cellBaselineShift(double ascent, double lineHeight, double iconSize) {
        return lineHeight / 2 - ascent + SYMBOLS_EM_CENTRE * iconSize;
    }

    /** Whether any code point of {@code text} is private use. */
    static boolean containsPrivateUse(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Every BMP private-use char is in one range, and every supplementary one is a pair
            // whose high surrogate is at least U+DB80; anything below either cannot be one.
            if (c >= 0xE000 && c <= 0xF8FF) return true;
            if (c >= 0xDB80 && c <= 0xDBFF && isPrivateUse(text.codePointAt(i))) return true;
        }
        return false;
    }

    /**
     * The installed family to draw private-use code points in, or empty to leave them to the
     * terminal face and its fallbacks.
     *
     * <p>Empty when {@code primary} is itself a Nerd Font. Otherwise, best first: the Nerd Font
     * build of the terminal's own face, then the symbols-only build — which is bundled, so in the
     * app the search ends here — then any other. Within each,
     * the plain variant before {@code Mono}: its icons are drawn larger and may lean into the
     * space after them, which is the look a listing is laid out for, and what the fallback list
     * gave every icon it did get right. {@code Propo} is proportional and never chosen.
     */
    static Optional<String> chooseFamily(String primary, Collection<String> installed) {
        if (primary != null && NERD.matcher(primary).matches()) return Optional.empty();
        String own = primary == null ? "" : squash(primary);
        return installed.stream()
                .filter(family -> {
                    Matcher m = NERD.matcher(family);
                    return m.matches() && !" Propo".equals(m.group(2)) && !"P".equals(m.group(3));
                })
                .min(Comparator.comparingInt((String family) -> rank(family, own))
                        .thenComparing(Comparator.naturalOrder()));
    }

    private static int rank(String family, String own) {
        Matcher m = NERD.matcher(family);
        m.matches();
        String base = squash(m.group(1));
        int source = base.equals(own) ? 0 : base.equals("symbols") ? 1 : 2;
        boolean mono = m.group(2) != null || m.group(3) != null;
        return source * 2 + (mono ? 1 : 0);
    }

    /** {@code JetBrains Mono} and {@code JetBrainsMono} name the same face. */
    private static String squash(String family) {
        return family.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static Set<String> installed;
    private static final Map<String, Optional<String>> CHOSEN = new HashMap<>();

    /** {@link #chooseFamily} against this machine's fonts, once per terminal face. */
    static synchronized Optional<String> familyFor(String primary) {
        if (installed == null) installed = Set.copyOf(Font.getFamilies());
        return CHOSEN.computeIfAbsent(primary, p -> chooseFamily(p, installed));
    }
}
