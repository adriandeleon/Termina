package com.termina.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jspecify.annotations.Nullable;

/**
 * The JSON form of Termina's config files (PLAT-14, SET-08, SET-09, Pauta 2.0.2):
 *
 * <pre>
 * {
 *   "schemaVersion": 3,
 *   "settings": {
 *     "font.family": "JetBrains Mono",
 *     "font.size": 13,
 *     "ui.showMenuBar": true
 *   }
 * }
 * </pre>
 *
 * <p>The keys are the dotted names the code and the docs already use, kept flat rather than nested so a
 * key is one line to find and to change. A true/false value is written as a JSON boolean and a
 * number as a number, but only when it reads back to exactly the same text: a profile named "007"
 * stays a string. Everything is read back as text, which is what {@link Settings} keeps.
 *
 * <p>Read and written by Jackson's {@code ObjectMapper} as a plain class, {@link Stored} (PLAT-14,
 * Pauta 6.0.1), and never built or parsed by hand. Until 0.15 it went through jackson-core's
 * streaming API, a token at a time.
 */
public final class ConfigJson {

    /**
     * The version this build writes and reads. A file names its own; an older one is brought forward
     * by {@link #MIGRATIONS}, and a newer one is not read at all (see {@link Settings#load}).
     */
    public static final int SCHEMA_VERSION = 3;

    /**
     * Step {@code i} takes a file from version {@code i + 1} to {@code i + 2}. A change to what a key
     * means adds one here, and a purely additive change — a new key with a default — adds the
     * identity step {@code values -> values}, so the version still says which build wrote the file.
     *
     * <p>1 to 2: five commands took the ids Pauta 6.0.1 names, and a key binding is stored under
     * its command's id ({@link CommandIds#migrateSettings}). 2 to 3: every other id without an
     * area in front of it took one (KBD-02; {@link CommandIds#groupByArea}).
     */
    static final List<UnaryOperator<Map<String, String>>> MIGRATIONS =
            List.of(CommandIds::migrateSettings, CommandIds::groupByArea);

    /**
     * The one mapper for the config files: a file may hold a field this build does not know, and
     * nothing may follow the object; keys are written sorted, so a file changes only where a value
     * did.
     */
    private static final ObjectMapper MAPPER = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    /** The mapper every config store shares: the tabs of the last session are written with it too. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * The file as Jackson reads and writes it: its version, then its values by key. A value is a
     * string, a number or a boolean in the file; anything else in its place is not one of ours.
     */
    @JsonPropertyOrder({"schemaVersion", "settings"})
    public static final class Stored {
        public @Nullable Integer schemaVersion;
        public @Nullable Map<String, @Nullable Object> settings;
    }

    private ConfigJson() {}

    /** A file's version and its values, as text. */
    public record Document(int schemaVersion, Map<String, String> values) {}

    /** A file that is not this format: unreadable JSON, or JSON of another shape. */
    public static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    /** Reads a file's text. */
    public static Document read(String text) throws FormatException {
        Stored stored;
        try {
            stored = MAPPER.readValue(text, Stored.class);
        } catch (IOException e) {
            throw new FormatException(e.getMessage() == null ? "unreadable JSON" : e.getMessage());
        }
        if (stored == null) throw new FormatException("not a JSON object");
        if (stored.schemaVersion == null) throw new FormatException("no schemaVersion");
        Map<String, String> values = new TreeMap<>();
        if (stored.settings != null) {
            stored.settings.forEach((key, value) -> {
                // A value this build cannot hold, an object or a list, is dropped, not fatal.
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    values.put(key, String.valueOf(value));
                }
            });
        }
        return new Document(stored.schemaVersion, values);
    }

    /** Brings an older file's values forward to {@link #SCHEMA_VERSION}, one step at a time. */
    public static Map<String, String> migrate(int from, Map<String, String> values) {
        Map<String, String> current = new TreeMap<>(values);
        for (int version = Math.max(from, 1); version < SCHEMA_VERSION; version++) {
            current = new TreeMap<>(MIGRATIONS.get(version - 1).apply(current));
        }
        return current;
    }

    /** The file's text, keys sorted, one per line. */
    public static String write(Map<String, String> values) {
        Stored stored = new Stored();
        stored.schemaVersion = SCHEMA_VERSION;
        Map<String, @Nullable Object> typed = new TreeMap<>();
        values.forEach((key, value) -> typed.put(key, typed(value)));
        stored.settings = typed;
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(stored) + System.lineSeparator();
        } catch (IOException e) {
            throw new IllegalStateException("writing to a string cannot fail", e);
        }
    }

    /**
     * A value as the file holds it: a boolean or a number where the text reads back as exactly
     * that, so a profile named "007" stays a string; a string otherwise.
     */
    private static Object typed(String value) {
        if (value.equals("true") || value.equals("false")) return Boolean.parseBoolean(value);
        if (isCanonicalInteger(value)) return Long.parseLong(value);
        if (isCanonicalDecimal(value)) return Double.parseDouble(value);
        return value;
    }

    /** {@code 42} or {@code -7}, but not {@code 007} or {@code +3}, which would read back otherwise. */
    static boolean isCanonicalInteger(String value) {
        if (!value.matches("-?(0|[1-9][0-9]{0,17})")) return false;
        return String.valueOf(Long.parseLong(value)).equals(value);
    }

    /** {@code 1.25}, written by Java's own {@code Double.toString}, which is what Settings stores. */
    static boolean isCanonicalDecimal(String value) {
        if (!value.matches("-?(0|[1-9][0-9]*)\\.[0-9]+")) return false;
        try {
            return Double.toString(Double.parseDouble(value)).equals(value);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
