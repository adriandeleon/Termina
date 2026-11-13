package com.termina;

import java.util.Locale;

/**
 * Which operating system this is: the one class that reads {@code os.name} (OS-02, Pauta 6.0.1).
 *
 * <p>Twenty classes used to work it out for themselves, three spellings of "is this Windows" among
 * them. Every other class asks here, so a place the app treats the systems differently is a call
 * to one of these methods and can be found, read and judged as one list (OS-04).
 *
 * <p>The decisions that depend on the OS take a {@link Kind} or a name as a parameter and are
 * tested with each; only the thin callers ask for the one this process runs on. Pure, apart from
 * reading system properties.
 */
public final class Os {

    /** The three systems Termina runs on, and anything else. */
    public enum Kind {
        WINDOWS,
        MAC,
        LINUX,
        OTHER
    }

    private Os() {}

    /** The system an {@code os.name} value names. */
    public static Kind of(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) return Kind.WINDOWS;
        if (name.startsWith("mac") || name.contains("darwin")) return Kind.MAC;
        if (name.contains("linux")) return Kind.LINUX;
        return Kind.OTHER;
    }

    /** The system's own name for itself, as the JVM reports it; empty when it reports none. */
    public static String name() {
        return System.getProperty("os.name", "");
    }

    /** The system this process runs on, or the one a test said to run as. */
    public static Kind current() {
        Kind as = override;
        return as != null ? as : of(name());
    }

    private static volatile @org.jspecify.annotations.Nullable Kind override;

    /**
     * Runs as another system until given null (OS-03, Pauta 6.0.1): for the test that asks whether
     * the commands, the menu bar's rows and the Settings pages are the same on all three. What a
     * class worked out as it loaded is not asked again, so the override is set before the classes
     * a test builds its lists from are first used, or the lists are built by pure functions.
     */
    public static void runAs(@org.jspecify.annotations.Nullable Kind kind) {
        override = kind;
    }

    public static boolean isWindows() {
        return current() == Kind.WINDOWS;
    }

    public static boolean isMac() {
        return current() == Kind.MAC;
    }

    public static boolean isLinux() {
        return current() == Kind.LINUX;
    }

    /** Name, version and architecture, for About and a problem report: "Linux 6.12 (amd64)". */
    public static String description() {
        String name = name();
        return (name.isEmpty() ? "?" : name) + " " + System.getProperty("os.version", "") + " ("
                + System.getProperty("os.arch", "?").toLowerCase(Locale.ROOT) + ")";
    }
}
