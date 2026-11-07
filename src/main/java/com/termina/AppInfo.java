package com.termina;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Identity: the one place a version number is read from.
 *
 * <p>It comes from {@code pom.xml} through a Maven-filtered {@code build-info.properties}, so
 * cutting a release means editing one number. A constant here instead would be a second source that
 * quietly disagrees with the artifact it is compiled into.
 */
public final class AppInfo {

    public static final String NAME = "Termina";
    public static final String VERSION = loadVersion();
    public static final String BUILD_TIME = load("build.time", "");

    /**
     * The edition of the Pauta guidelines this build is built to, from {@code pauta.version} in the
     * pom. Empty for an unfiltered build, in which case nothing names one rather than a guess.
     */
    public static final String PAUTA_EDITION = load("pauta.version", "");

    /** The JavaFX the build compiled against: the answer when the running one cannot be read. */
    private static final String BUILT_JAVAFX = load("javafx.version", "");

    public static final String COPYRIGHT = "Copyright © 2026 Adrian De Leon";
    public static final String LICENSE = "MIT License";

    public static final String GITHUB_REPO = "adriandeleon/Termina";
    public static final String HOMEPAGE = "https://termina-project.dev";
    public static final String RELEASES_PAGE = "https://github.com/" + GITHUB_REPO + "/releases";
    public static final String LATEST_RELEASE_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";

    private AppInfo() {}

    /**
     * Falls back to {@code 0.0.0} when the resource was not filtered — that happens when classes
     * are run straight from an IDE build rather than through Maven, and it should read as
     * "unknown build" rather than crash.
     */
    private static String loadVersion() {
        return load("version", "0.0.0");
    }

    private static String load(String key, String fallback) {
        try (InputStream in = AppInfo.class.getResourceAsStream("/com/termina/build-info.properties")) {
            if (in == null) return fallback;
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty(key, fallback);
            // An unfiltered resource still contains the literal ${...} placeholder.
            return value.startsWith("${") ? fallback : value;
        } catch (IOException e) {
            return fallback;
        }
    }

    /**
     * The edition's own page. Always the numbered edition, never {@code latest/}: a newer edition
     * can hold rules this build does not meet.
     */
    public static String pautaUrl() {
        return "https://pauta-project.dev/" + PAUTA_EDITION + "/";
    }

    /** The running Java, with its vendor: {@code 25.0.1+8 (Eclipse Adoptium)}. */
    public static String javaVersion() {
        String vendor = System.getProperty("java.vendor", "");
        return Runtime.version() + (vendor.isBlank() ? "" : " (" + vendor + ")");
    }

    /**
     * The running JavaFX. The toolkit publishes it only once it has started, so {@code --version}
     * reads the module's own version, and the build's when the module has none. The running one
     * is the one worth reporting: a stale jar on a module path once ran an older JavaFX under a
     * build compiled against a newer one.
     */
    public static String javaFxVersion() {
        String running = System.getProperty("javafx.runtime.version");
        if (running != null && !running.isBlank()) return running;
        String module = ModuleLayer.boot()
                .findModule("javafx.graphics")
                .map(Module::getDescriptor)
                .flatMap(ModuleDescriptor::rawVersion)
                .orElse("");
        return module.isBlank() ? BUILT_JAVAFX : module;
    }

    /**
     * What this build is and what it runs on, one {@code Key: value} per line under the name and
     * version. {@code --version} prints it and About shows it, so the two cannot disagree, and it
     * is what a bug report wants pasted.
     */
    public static List<String> details() {
        var lines = new java.util.ArrayList<String>();
        lines.add(NAME + " " + VERSION);
        if (!BUILD_TIME.isBlank()) lines.add("Built: " + BUILD_TIME);
        if (!PAUTA_EDITION.isBlank()) lines.add("Pauta: " + PAUTA_EDITION);
        lines.add("Java: " + javaVersion());
        String fx = javaFxVersion();
        if (!fx.isBlank()) lines.add("JavaFX: " + fx);
        lines.add("OS: " + System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "") + " ("
                + System.getProperty("os.arch", "?").toLowerCase(Locale.ROOT) + ")");
        return List.copyOf(lines);
    }
}
