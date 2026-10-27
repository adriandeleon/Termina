package com.termina.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The UI string catalog.
 *
 * <p>English lives in {@code resources/com/termina/i18n/messages.properties} and is both the base and
 * the fallback; each other language is an overlay, {@code messages_<lang>.properties}. Loaded through
 * {@code getResourceAsStream} and a UTF-8 reader rather than {@code ResourceBundle}, which is the same
 * way the fonts and stylesheets are read — our own module's resources, so no {@code module-info opens}
 * and none of ResourceBundle's module-path surprises.
 *
 * <p>{@link #tr(String)} returns the active language's value, falling back to English, then to the key
 * itself (so a missing key is visible, never an exception). {@link #tr(String, Object...)} applies
 * {@link MessageFormat} (e.g. {@code status.saved = Saved {0}}). The active language is chosen once by
 * {@code App.start} (after config load, before any UI is built); the UI is fully relabeled on restart.
 */
public final class Messages {

    /** Bundled UI languages: code → endonym (its own name). English is the base catalog. */
    private static final Map<String, String> LANGUAGES = new LinkedHashMap<>();

    static {
        LANGUAGES.put("en", "English");
        LANGUAGES.put("it", "Italiano");
        LANGUAGES.put("es", "Español");
        LANGUAGES.put("fr", "Français");
        LANGUAGES.put("pt", "Português");
        LANGUAGES.put("de", "Deutsch");
    }

    private static Map<String, String> base = Map.of();
    private static Map<String, String> active = Map.of();
    private static String currentLang = "en";

    private Messages() {}

    /** Loads the English base + the chosen language overlay (falling back to English for an unknown code). */
    public static synchronized void init(String lang) {
        base = load("en");
        currentLang = LANGUAGES.containsKey(lang) ? lang : "en";
        active = "en".equals(currentLang) ? base : load(currentLang);
    }

    private static Map<String, String> load(String lang) {
        String name = "en".equals(lang)
                ? "/com/termina/i18n/messages.properties"
                : "/com/termina/i18n/messages_" + lang + ".properties";
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream(name)) {
            if (in != null) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // missing/unreadable bundle → empty map; tr() falls back to English/key
        }
        Map<String, String> m = new HashMap<>();
        for (String key : p.stringPropertyNames()) {
            m.put(key, p.getProperty(key));
        }
        return m;
    }

    /** The localized string for {@code key} (active → English → the key itself). */
    public static String tr(String key) {
        return render(raw(key), null);
    }

    /** The localized string for {@code key} with {@link MessageFormat} arguments. */
    public static String tr(String key, @Nullable Object... args) {
        return render(raw(key), args);
    }

    private static String raw(String key) {
        String v = active.get(key);
        if (v == null) {
            v = base.get(key);
        }
        return v != null ? v : key;
    }

    /** An argument placeholder: {@code {0}}, {@code {1,number}}… — what makes a value a pattern. */
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{\\s*\\d");

    /**
     * Whether a catalogue value is a {@link MessageFormat} pattern: it has an argument placeholder.
     *
     * <p>Decided by the value, never by the call. It used to be the call — {@code tr(key)} returned the
     * text as written, {@code tr(key, args)} ran it through MessageFormat — so the same apostrophe had
     * to be written {@code ''} in one value and {@code '} in the next depending on how some caller
     * happened to look it up, and French showed {@code n''est} in four places. Now a value with a
     * placeholder is always a pattern (apostrophes doubled, as MessageFormat wants), and one without
     * is always plain text (apostrophes single). {@code MessagesTest} holds every bundle to that.
     */
    public static boolean isPattern(String value) {
        return PLACEHOLDER.matcher(value).find();
    }

    static String render(String value, @Nullable Object @Nullable [] args) {
        if (!isPattern(value)) return value;
        try {
            return new MessageFormat(value).format(args == null ? new Object[0] : args);
        } catch (RuntimeException e) {
            return value; // a malformed pattern shouldn't crash the UI
        }
    }

    /** Every key in a bundle and its raw value, for the test that holds each bundle to {@link #isPattern}. */
    static Map<String, String> bundle(String lang) {
        return load(lang);
    }

    /** The active language code (e.g. {@code "es"}). */
    public static String current() {
        return currentLang;
    }

    /** Bundled languages: code → endonym, in display order (English first). */
    public static Map<String, String> available() {
        return new LinkedHashMap<>(LANGUAGES);
    }

    /** The endonym for a language code (e.g. {@code "es"} → {@code "Español"}); the code itself if unknown. */
    public static String languageName(String code) {
        return LANGUAGES.getOrDefault(code, code);
    }

    /**
     * The effective UI language: the explicit {@code pref} if bundled, else the {@code systemLang} if
     * bundled, else English. Pure (no toolkit / no I/O) for unit testing.
     */
    public static String resolve(String pref, Set<String> available, String systemLang) {
        if (pref != null && available.contains(pref)) {
            return pref;
        }
        if (systemLang != null && available.contains(systemLang)) {
            return systemLang;
        }
        return "en";
    }

    /**
     * The JVM default locale to run with, given what the user asked for.
     *
     * <p>Only an explicit choice moves the default off the OS locale. "Automatic" means "follow
     * the system", and the system's locale carries more than a language — the country decides
     * number and date formats, and {@code de_AT} collapsed to {@code de} loses it. Someone whose OS
     * speaks a language we do not bundle sees English menus either way; that is no reason to also
     * change how their dates are written. An explicit choice keeps the country when the language is
     * the system's own, for the same reason.
     *
     * @param pref the stored language preference, blank for automatic
     * @param resolved the language the catalogue was initialised with
     * @param system the OS locale
     */
    public static java.util.Locale defaultLocale(String pref, String resolved, java.util.Locale system) {
        if (pref == null || pref.isBlank()) return system;
        if (system != null && system.getLanguage().equals(resolved)) return system;
        return java.util.Locale.forLanguageTag(resolved);
    }
}
