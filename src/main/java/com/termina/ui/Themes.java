package com.termina.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.scene.paint.Color;

import com.termina.theme.ColorScheme;
import com.termina.theme.ThemeFile;
import com.termina.theme.ThemeImport;
import com.termina.theme.ThemeImportException;
import org.jspecify.annotations.Nullable;

/**
 * Every theme there is to choose from: the four built in, then the ones imported from other
 * terminals' files, which live as {@code <config>/themes/<name>.properties}.
 *
 * <p>The settings store a theme by id, as before. An imported theme's id is {@code user-<name>}, so
 * it can never collide with a built-in one, and an id that names nothing — a theme deleted by hand,
 * or one synced from a machine that has a theme this one does not — falls back to Editora Dark the
 * way an unknown built-in id always has.
 *
 * <p>The chrome follows the scheme's background: the dark control stylesheet over a dark
 * background, the light one over a light background. An imported scheme says nothing about how a
 * settings window should look, any more than Apple's Clear profiles did.
 */
public final class Themes {

    private static final Logger LOG = Logger.getLogger(Themes.class.getName());

    static final String USER_PREFIX = "user-";

    private static final ThemeEntry FALLBACK = ThemeEntry.of(Theme.EDITORA_DARK);

    private static volatile @Nullable Path directory;
    private static volatile List<ThemeEntry> imported = List.of();

    private Themes() {}

    /** Where imported themes are kept, read now. Called once at startup with the settings folder's. */
    public static synchronized void useDirectory(Path themes) {
        directory = themes;
        reload();
    }

    /** Re-reads the folder. A file that does not parse is skipped and logged, not fatal. */
    static synchronized void reload() {
        Path dir = directory;
        List<ThemeEntry> found = new ArrayList<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.properties")) {
                List<Path> sorted = new ArrayList<>();
                files.forEach(sorted::add);
                sorted.sort(null);
                for (Path file : sorted) {
                    try {
                        ColorScheme scheme = ThemeFile.read(Files.readString(file, StandardCharsets.UTF_8));
                        found.add(entry(idOf(file), scheme, file));
                    } catch (IOException | ThemeImportException e) {
                        LOG.log(Level.WARNING, "skipping theme " + file + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "cannot read themes in " + dir, e);
            }
        }
        imported = List.copyOf(found);
    }

    /** Built in first, in their order, then the imported ones by file name. */
    public static List<ThemeEntry> all() {
        List<ThemeEntry> out = new ArrayList<>();
        for (Theme theme : Theme.values()) out.add(ThemeEntry.of(theme));
        out.addAll(imported);
        return out;
    }

    /** The theme an id names, or Editora Dark. */
    public static ThemeEntry byId(String id) {
        for (ThemeEntry entry : all()) {
            if (entry.id().equals(id)) return entry;
        }
        return FALLBACK;
    }

    /** Display name to theme, in {@link #all} order — what a picker shows. */
    public static Map<String, ThemeEntry> byDisplayName() {
        Map<String, ThemeEntry> map = new LinkedHashMap<>();
        for (ThemeEntry entry : all()) map.putIfAbsent(entry.displayName(), entry);
        return map;
    }

    /**
     * Imports every scheme in a file, writing each to the themes folder, and returns them. A scheme
     * with the name of one imported before replaces it — importing a file again updates it rather
     * than adding a second copy.
     */
    public static synchronized List<ThemeEntry> importFile(Path file) throws IOException, ThemeImportException {
        Path dir = directory;
        if (dir == null) throw new IOException("no folder to keep themes in");
        byte[] content;
        try (var in = Files.newInputStream(file)) {
            content = in.readNBytes(8 * 1024 * 1024);
        }
        List<ColorScheme> schemes = ThemeImport.read(file.getFileName().toString(), content);
        Files.createDirectories(dir);
        Set<String> builtInNames = new HashSet<>();
        for (Theme theme : Theme.values()) builtInNames.add(theme.displayName().toLowerCase(Locale.ROOT));
        List<String> ids = new ArrayList<>();
        for (ColorScheme scheme : schemes) {
            String name = scheme.name();
            // A scheme called "Editora Dark" is somebody's copy; the picker must still tell them apart.
            if (builtInNames.contains(name.toLowerCase(Locale.ROOT))) name = name + " (imported)";
            ColorScheme named = new ColorScheme(
                    name, scheme.background(), scheme.foreground(), scheme.ansi(), scheme.cursor(), scheme.selection());
            String slug = slug(name);
            Path target = dir.resolve(slug + ".properties");
            Path temp = dir.resolve(slug + ".properties.tmp");
            Files.writeString(temp, ThemeFile.write(named), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            ids.add(USER_PREFIX + slug);
        }
        reload();
        List<ThemeEntry> out = new ArrayList<>();
        for (String id : ids) out.add(byId(id));
        return out;
    }

    /** Deletes an imported theme. A built-in one cannot be, and is left alone. */
    public static synchronized void remove(String id) throws IOException {
        ThemeEntry entry = byId(id);
        if (entry.isBuiltIn() || !entry.id().equals(id)) return;
        Files.deleteIfExists(entry.file());
        reload();
    }

    private static ThemeEntry entry(String id, ColorScheme scheme, Path file) {
        TerminalPalette palette = paletteOf(scheme);
        boolean dark = isDark(palette.background());
        return new ThemeEntry(id, scheme.name(), dark, dark ? "editora-dark" : "editora-light", palette, file);
    }

    static TerminalPalette paletteOf(ColorScheme scheme) {
        int[] ansi = scheme.ansi();
        Color[] colours = new Color[16];
        for (int i = 0; i < 16; i++) colours[i] = colour(ansi[i]);
        return new TerminalPalette(
                colour(scheme.background()),
                colour(scheme.foreground()),
                colours,
                scheme.cursor() == null ? null : colour(scheme.cursor()),
                scheme.selection() == null ? null : colour(scheme.selection()));
    }

    private static Color colour(int rgb) {
        return Color.rgb(rgb >> 16 & 0xff, rgb >> 8 & 0xff, rgb & 0xff);
    }

    /** Whether a background wants the dark chrome: its relative luminance below the middle grey's. */
    static boolean isDark(Color background) {
        return luminance(background) < 0.18;
    }

    private static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static String idOf(Path file) {
        String name = file.getFileName().toString();
        return USER_PREFIX + name.substring(0, name.length() - ".properties".length());
    }

    /** A file name for a scheme's name: lower case, letters and digits, hyphens between words. */
    static String slug(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String slug =
                ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 64) slug = slug.substring(0, 64).replaceAll("-+$", "");
        return slug.isEmpty() ? "theme" : slug;
    }
}
