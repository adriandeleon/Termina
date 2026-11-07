package com.termina.config;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * The JSON form of Termina's config files (PLAT-14, SET-08, SET-09, Pauta 2.0.2):
 *
 * <pre>
 * {
 *   "schemaVersion": 1,
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
 * <p>Through jackson-core's streaming API: one small file does not need a data-binding library, and
 * the runtime image does not need its reflection.
 */
public final class ConfigJson {

    /**
     * The version this build writes and reads. A file names its own; an older one is brought forward
     * by {@link #MIGRATIONS}, and a newer one is not read at all (see {@link Settings#load}).
     */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Step {@code i} takes a file from version {@code i + 1} to {@code i + 2}. Version 1 is the first,
     * so there are none yet; a change to what a key means adds one here, and a purely additive change
     * — a new key with a default — adds the identity step {@code values -> values}, so the version
     * still says which build wrote the file.
     */
    static final List<UnaryOperator<Map<String, String>>> MIGRATIONS = List.of();

    private static final JsonFactory FACTORY = new JsonFactory();

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
        Integer version = null;
        Map<String, String> values = new TreeMap<>();
        try (JsonParser parser = FACTORY.createParser(text)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new FormatException("not a JSON object");
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("schemaVersion".equals(field)) {
                    if (value != JsonToken.VALUE_NUMBER_INT) throw new FormatException("schemaVersion is not a number");
                    version = parser.getIntValue();
                } else if ("settings".equals(field)) {
                    if (value != JsonToken.START_OBJECT) throw new FormatException("settings is not an object");
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        String key = parser.currentName();
                        JsonToken token = parser.nextToken();
                        if (token == JsonToken.VALUE_NULL) continue;
                        if (token.isStructStart()) {
                            parser.skipChildren();
                            continue; // a value this build cannot hold is dropped, not fatal
                        }
                        values.put(key, parser.getText());
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.currentToken() != JsonToken.END_OBJECT) throw new FormatException("trailing content");
        } catch (IOException e) {
            throw new FormatException(e.getMessage() == null ? "unreadable JSON" : e.getMessage());
        }
        if (version == null) throw new FormatException("no schemaVersion");
        return new Document(version, values);
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
        StringWriter out = new StringWriter();
        try (JsonGenerator json = FACTORY.createGenerator(out)) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            json.writeNumberField("schemaVersion", SCHEMA_VERSION);
            json.writeObjectFieldStart("settings");
            for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
                json.writeFieldName(entry.getKey());
                writeValue(json, entry.getValue());
            }
            json.writeEndObject();
            json.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("writing to a string cannot fail", e);
        }
        return out + System.lineSeparator();
    }

    private static void writeValue(JsonGenerator json, String value) throws IOException {
        if (value.equals("true") || value.equals("false")) {
            json.writeBoolean(Boolean.parseBoolean(value));
        } else if (isCanonicalInteger(value)) {
            json.writeNumber(Long.parseLong(value));
        } else if (isCanonicalDecimal(value)) {
            json.writeNumber(value);
        } else {
            json.writeString(value);
        }
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
