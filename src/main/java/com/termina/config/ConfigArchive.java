package com.termina.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.jspecify.annotations.Nullable;

/**
 * Export and Import of the configuration (SET-12, Pauta 2.0.2), after Editora's
 * {@code ConfigExporter} and {@code ConfigImport}.
 *
 * <p>An export is the config folder as a zip, less what belongs to this run or this computer: locks,
 * logs, the instance socket, temporaries, the sync clone and the copies earlier imports made. An
 * import reads one back by <em>part</em> and replaces only the parts the user ticks. The preferences
 * are replaced key by key through {@link Settings}, never by writing over {@code settings.json}, so
 * what the running app holds and what the file holds cannot drift apart. Window placement and the
 * update check's memory are this computer's and are never imported, nor is the connection to settings
 * sync — the same line {@link Settings#keysToReset} draws.
 *
 * <p>A zip is refused before anything is written when it is not a zip, has no {@code settings.json}
 * at its root, names a path that is not a plain relative one, holds more than the limits below
 * (counted while inflating, not taken from the zip's headers), or was written by a newer Termina.
 * Theme files are only ever written by their bare name into {@code themes/}.
 *
 * <p>No JavaFX: the UI runs {@link #export}, {@link #scan} and {@link #applyFiles} off the FX thread
 * and gives {@link #merge}'s result to {@link Settings#replaceStored} on it.
 */
public final class ConfigArchive {

    /** What an import can replace, in the order the dialog lists it. */
    public enum Part {
        /** Everything in {@code settings.json} that no other part claims. */
        PREFERENCES,
        /** The {@code keys.*} block: the chords the user moved or cleared. */
        KEY_BINDINGS,
        /** The user's shell profiles, their fonts and themes, which is the default and which are hidden. */
        PROFILES,
        /** {@code themes/}: colour schemes imported from other terminals. */
        THEMES
    }

    /** Why a zip is not imported. */
    public static final class Refused extends Exception {
        public enum Reason {
            NOT_A_ZIP,
            NO_SETTINGS,
            UNSAFE_PATH,
            TOO_LARGE,
            NEWER,
            UNREADABLE_SETTINGS
        }

        private final Reason reason;

        public Refused(Reason reason, String detail) {
            super(detail);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /**
     * A zip that passed every check: its preferences at this build's schema, and its theme files by
     * name.
     */
    public record Scan(Map<String, String> settings, Map<String, byte[]> themes) {
        public Scan {
            settings = Map.copyOf(settings);
            themes = Map.copyOf(themes);
        }

        /** The parts this zip can replace; a part it has nothing for is not offered. */
        public Set<Part> parts() {
            Set<Part> parts = EnumSet.noneOf(Part.class);
            for (String key : settings.keySet()) {
                Part part = partOf(key);
                if (part != null) parts.add(part);
            }
            if (!themes.isEmpty()) parts.add(Part.THEMES);
            return parts;
        }

        /** How many entries the zip has for a part: keys for the settings parts, files for themes. */
        public int count(Part part) {
            if (part == Part.THEMES) return themes.size();
            int n = 0;
            for (String key : settings.keySet()) {
                if (partOf(key) == part) n++;
            }
            return n;
        }
    }

    /** Largest single file taken from a zip; a settings file or a theme is a few kilobytes. */
    static final long MAX_ENTRY_BYTES = 4L * 1024 * 1024;
    /** Largest total taken from a zip. */
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    /** Most entries an import looks at. */
    static final int MAX_ENTRIES = 2_000;

    /** The folder in the config folder that holds the copies made before an import. */
    public static final String BACKUPS_DIR = "backups";

    static final String THEMES_DIR = "themes";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ConfigArchive() {}

    /** An export's name: {@code termina-config-20261107-153012.zip}. */
    public static String fileName(LocalDateTime when) {
        return "termina-config-" + STAMP.format(when) + ".zip";
    }

    // ---------------------------------------------------------------- export

    /**
     * Whether a path in the config folder (forward slashes, relative) goes into an export. Pure.
     *
     * <p>Out: the sync clone and the import backups (folders of their own, rebuilt or made by us),
     * locks and the instance socket (this process's), the session logs (diagnostics, which a problem
     * report carries), and the temporaries a save writes and renames.
     */
    static boolean included(String relative) {
        if (relative.startsWith("sync/") || relative.startsWith(BACKUPS_DIR + "/") || relative.startsWith("tmp/")) {
            return false;
        }
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        return !(name.endsWith(".lock")
                || name.endsWith(".sock")
                || name.endsWith(".tmp")
                || name.endsWith(".log")
                || name.matches(".*\\.log\\.\\d+"));
    }

    /**
     * Writes the config folder's included files into {@code zip}, through a temporary beside it, so
     * a failed export never leaves a half-written zip under the name the user chose. Returns how
     * many files went in.
     */
    public static int export(Path configDir, Path zip) throws IOException {
        Path target = zip.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("no folder to write " + zip + " into");
        Files.createDirectories(parent);
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(configDir)) {
            try (Stream<Path> walk = Files.walk(configDir)) {
                walk.filter(Files::isRegularFile).sorted().forEach(files::add);
            }
        }
        Path temp = Files.createTempFile(parent, ".termina-export.", ".tmp");
        int written = 0;
        try {
            try (OutputStream file = Files.newOutputStream(temp);
                    ZipOutputStream out = new ZipOutputStream(file)) {
                for (Path path : files) {
                    String relative = relative(configDir, path);
                    // The zip being written may be inside the folder (a backup is), and is never its own entry.
                    if (path.toAbsolutePath().equals(target)
                            || path.toAbsolutePath().equals(temp.toAbsolutePath())) {
                        continue;
                    }
                    if (!included(relative)) continue;
                    byte[] bytes;
                    try {
                        bytes = Files.readAllBytes(path);
                    } catch (java.nio.file.NoSuchFileException gone) {
                        continue; // removed while the folder was read: a save's temporary, say
                    }
                    out.putNextEntry(new ZipEntry(relative));
                    out.write(bytes);
                    out.closeEntry();
                    written++;
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return written;
    }

    /**
     * Copies the config folder to {@code backups/termina-config.before-import-<stamp>.zip},
     * owner-only where the filesystem has permissions, before an import replaces anything.
     */
    public static Path backup(Path configDir, LocalDateTime now) throws IOException {
        Path dir = configDir.resolve(BACKUPS_DIR);
        Files.createDirectories(dir);
        Path zip = dir.resolve("termina-config.before-import-" + STAMP.format(now) + ".zip");
        export(configDir, zip);
        try {
            Files.setPosixFilePermissions(zip, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // Windows: the folder is the user's profile, which is already theirs alone.
        }
        return zip;
    }

    private static String relative(Path dir, Path file) {
        return dir.relativize(file).toString().replace('\\', '/');
    }

    // ---------------------------------------------------------------- import

    /** Reads and checks a zip; nothing is written. */
    public static Scan scan(Path zip) throws Refused, IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (InputStream file = Files.newInputStream(zip);
                ZipInputStream in = new ZipInputStream(file)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (++count > MAX_ENTRIES)
                    throw new Refused(Refused.Reason.TOO_LARGE, "more than " + MAX_ENTRIES + " entries");
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (!isSafeName(name)) throw new Refused(Refused.Reason.UNSAFE_PATH, name);
                boolean wanted = name.equals(Settings.SETTINGS_FILE) || themeName(name) != null;
                byte[] bytes = readLimited(in, name);
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES)
                    throw new Refused(Refused.Reason.TOO_LARGE, "more than " + MAX_TOTAL_BYTES + " bytes");
                if (wanted) entries.put(name, bytes);
            }
        } catch (ZipException e) {
            throw new Refused(Refused.Reason.NOT_A_ZIP, e.getMessage() == null ? zip.toString() : e.getMessage());
        }
        if (count == 0) throw new Refused(Refused.Reason.NOT_A_ZIP, zip.toString());
        byte[] settingsBytes = entries.remove(Settings.SETTINGS_FILE);
        if (settingsBytes == null) throw new Refused(Refused.Reason.NO_SETTINGS, Settings.SETTINGS_FILE);
        ConfigJson.Document document;
        try {
            document = ConfigJson.read(SettingsFormat.decode(settingsBytes));
        } catch (ConfigJson.FormatException e) {
            throw new Refused(Refused.Reason.UNREADABLE_SETTINGS, String.valueOf(e.getMessage()));
        }
        if (document.schemaVersion() > ConfigJson.SCHEMA_VERSION) {
            throw new Refused(Refused.Reason.NEWER, "schema " + document.schemaVersion());
        }
        Map<String, String> settings = ConfigJson.migrate(document.schemaVersion(), document.values());
        Map<String, byte[]> themes = new TreeMap<>();
        entries.forEach((name, bytes) -> {
            String theme = themeName(name);
            if (theme != null) themes.put(theme, bytes);
        });
        return new Scan(settings, themes);
    }

    private static byte[] readLimited(InputStream in, String name) throws IOException, Refused {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long size = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            size += n;
            if (size > MAX_ENTRY_BYTES) throw new Refused(Refused.Reason.TOO_LARGE, name);
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Whether a zip entry's name is a plain relative path: no leading slash, no drive, no backslash,
     * no {@code .} or {@code ..} segment, no empty one. Pure.
     */
    static boolean isSafeName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    /**
     * The theme file an entry names, {@code themes/<name>.json} and nothing deeper; else null. A
     * zip exported before 0.15 has {@code <name>.properties}, which is taken too: the themes
     * folder's reader rewrites it as JSON when it next reads the folder. Pure.
     */
    static @Nullable String themeName(String entry) {
        String prefix = THEMES_DIR + "/";
        if (!entry.startsWith(prefix)) return null;
        String name = entry.substring(prefix.length());
        if (name.isEmpty() || name.contains("/") || name.startsWith(".") || !isThemeFile(name)) return null;
        return name;
    }

    /** Whether a file name is a theme's: JSON, or the properties file it was until 0.15. Pure. */
    static boolean isThemeFile(String name) {
        return name.endsWith(".json") || name.endsWith(".properties");
    }

    /**
     * The part a {@code settings.json} key belongs to, or null for one an import never touches:
     * window state, the update check's memory, and the settings-sync connection. Pure.
     */
    public static @Nullable Part partOf(String key) {
        if (Settings.isStateKey(key) || Settings.isSyncKey(key)) return null;
        if (key.startsWith(Settings.KEYS_PREFIX)) return Part.KEY_BINDINGS;
        if (Settings.isProfileKey(key)) return Part.PROFILES;
        return Part.PREFERENCES;
    }

    /**
     * What the stored settings become: {@code current}, with every key of a ticked part taken out and
     * the zip's keys of that part put in. A ticked part is replaced whole — a key the zip lacks goes
     * back to its default — and an unticked one is left exactly as it is. Pure.
     */
    public static Map<String, String> merge(
            Map<String, String> current, Map<String, String> imported, Set<Part> ticked) {
        Map<String, String> out = new HashMap<>();
        current.forEach((key, value) -> {
            Part part = partOf(key);
            if (part == null || !ticked.contains(part)) out.put(key, value);
        });
        imported.forEach((key, value) -> {
            Part part = partOf(key);
            if (part != null && ticked.contains(part)) out.put(key, value);
        });
        return out;
    }

    /**
     * Writes the file parts: with {@link Part#THEMES} ticked, {@code themes/} ends holding exactly
     * the zip's theme files. The settings parts are not files here; see {@link #merge}. Call
     * {@link #backup} first.
     */
    public static void applyFiles(Scan scan, Set<Part> ticked, Path configDir) throws IOException {
        if (!ticked.contains(Part.THEMES) || scan.themes().isEmpty()) return;
        Path themes = configDir.resolve(THEMES_DIR);
        Files.createDirectories(themes);
        try (DirectoryStream<Path> existing = Files.newDirectoryStream(themes)) {
            for (Path file : existing) {
                String name = String.valueOf(file.getFileName());
                if (name.startsWith(".") || !isThemeFile(name)) continue;
                if (!scan.themes().containsKey(name)) Files.deleteIfExists(file);
            }
        }
        for (Map.Entry<String, byte[]> theme : scan.themes().entrySet()) {
            Path target = themes.resolve(theme.getKey());
            Path temp = Files.createTempFile(themes, "." + theme.getKey() + ".", ".tmp");
            try {
                Files.write(temp, theme.getValue());
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** A settings file's text, for a test's zip. */
    static byte[] settingsBytes(Map<String, String> values) {
        return ConfigJson.write(values).getBytes(StandardCharsets.UTF_8);
    }
}
