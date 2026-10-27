package com.termina.theme;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A Terminal {@code .terminal} profile: an XML property list whose colours are each an {@code
 * NSColor} archived with {@code NSKeyedArchiver} into a <em>binary</em> property list, stored as
 * {@code <data>}. Two formats deep, which is why it is the one format here that needs a reader of
 * its own ({@link BinaryPlist}).
 *
 * <p>An archived colour carries its components as ASCII — {@code NSRGB} is {@code "0.1 0.2 0.3"} —
 * or, for a grey, {@code NSWhite}. The components are taken as sRGB, as the rest of the importers'
 * are: converting Apple's calibrated (generic) RGB would mean a colour-management pass that changes
 * every value slightly, and a port is meant to carry the file's numbers.
 *
 * <p>A profile may leave colours out, in which case Terminal draws its own defaults; those defaults
 * fill the gaps here, so a profile that only sets a background still imports as Terminal shows it.
 */
final class AppleTerminalProfile {

    private static final String[] ANSI_KEYS = {
        "ANSIBlackColor", "ANSIRedColor", "ANSIGreenColor", "ANSIYellowColor",
        "ANSIBlueColor", "ANSIMagentaColor", "ANSICyanColor", "ANSIWhiteColor",
        "ANSIBrightBlackColor", "ANSIBrightRedColor", "ANSIBrightGreenColor", "ANSIBrightYellowColor",
        "ANSIBrightBlueColor", "ANSIBrightMagentaColor", "ANSIBrightCyanColor", "ANSIBrightWhiteColor",
    };

    /** Terminal's own palette, for a profile that does not set one. */
    private static final int[] TERMINAL_DEFAULTS = {
        0x000000, 0x990000, 0x00a600, 0x999900, 0x0000b2, 0xb200b2, 0x00a6b2, 0xbfbfbf,
        0x666666, 0xe50000, 0x00d900, 0xe5e500, 0x0000ff, 0xe500e5, 0x00e5e5, 0xe5e5e5,
    };

    private AppleTerminalProfile() {}

    static ColorScheme read(byte[] file, String fallbackName) throws ThemeImportException {
        if (!(PlistXml.parse(file) instanceof Map<?, ?> profile)) {
            throw new ThemeImportException(ThemeImportException.Reason.NOT_FORMAT, "Terminal");
        }
        if (!profile.containsKey("BackgroundColor") && !profile.containsKey("TextColor")) {
            throw new ThemeImportException(ThemeImportException.Reason.NO_SCHEMES);
        }
        int[] ansi = TERMINAL_DEFAULTS.clone();
        for (int i = 0; i < 16; i++) {
            Integer colour = colour(profile.get(ANSI_KEYS[i]));
            if (colour != null) ansi[i] = colour;
        }
        Integer background = colour(profile.get("BackgroundColor"));
        Integer foreground = colour(profile.get("TextColor"));
        Object name = profile.get("name");
        return new ColorScheme(
                name instanceof String s && !s.isBlank() ? s : fallbackName,
                background == null ? 0xffffff : background,
                foreground == null ? 0x000000 : foreground,
                ansi,
                colour(profile.get("CursorColor")),
                colour(profile.get("SelectionColor")));
    }

    /** One archived colour, or null when the key is absent. */
    static @Nullable Integer colour(@Nullable Object data) throws ThemeImportException {
        if (data == null) return null;
        if (!(data instanceof byte[] archive)) throw new ThemeImportException(ThemeImportException.Reason.BAD_COLOUR);
        if (!(BinaryPlist.parse(archive) instanceof Map<?, ?> top)
                || !(top.get("$objects") instanceof List<?> objects)
                || !(top.get("$top") instanceof Map<?, ?> roots)
                || !(roots.get("root") instanceof BinaryPlist.Uid root)
                || root.index() < 0
                || root.index() >= objects.size()
                || !(objects.get(root.index()) instanceof Map<?, ?> color)) {
            throw new ThemeImportException(ThemeImportException.Reason.BAD_COLOUR);
        }
        if (color.get("NSRGB") instanceof byte[] rgb) {
            double[] c = components(rgb, 3);
            return ColorScheme.rgb(c[0], c[1], c[2]);
        }
        if (color.get("NSWhite") instanceof byte[] white) {
            double w = components(white, 1)[0];
            return ColorScheme.rgb(w, w, w);
        }
        if (color.get("NSComponents") instanceof byte[] components) {
            double[] c = components(components, 3);
            return ColorScheme.rgb(c[0], c[1], c[2]);
        }
        throw new ThemeImportException(ThemeImportException.Reason.BAD_COLOUR);
    }

    /** The leading numbers of an ASCII component list, which ends in a NUL. */
    private static double[] components(byte[] ascii, int wanted) throws ThemeImportException {
        String text =
                new String(ascii, StandardCharsets.US_ASCII).replace('\0', ' ').strip();
        String[] parts = text.split("\\s+");
        if (parts.length < wanted) throw new ThemeImportException(ThemeImportException.Reason.BAD_COLOUR);
        double[] out = new double[wanted];
        try {
            for (int i = 0; i < wanted; i++) out[i] = Double.parseDouble(parts[i]);
        } catch (NumberFormatException e) {
            throw new ThemeImportException(e, ThemeImportException.Reason.BAD_COLOUR);
        }
        return out;
    }
}
