package com.termina.theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import org.jspecify.annotations.Nullable;

/**
 * Windows Terminal colour schemes: one scheme object, as the schemes gallery and
 * iTerm2-Color-Schemes publish them, or a whole {@code settings.json}, whose {@code schemes} array
 * is read in full. Comments and trailing commas are allowed, as Windows Terminal itself allows them
 * in its settings.
 *
 * <p>Windows Terminal calls magenta {@code purple}; both are accepted.
 */
final class WindowsTerminalSchemes {

    private static final String[] NAMES = {"black", "red", "green", "yellow", "blue", "purple", "cyan", "white"};

    private WindowsTerminalSchemes() {}

    static List<ColorScheme> read(byte[] file, String fallbackName) throws ThemeImportException {
        Object root = parse(file);
        List<ColorScheme> out = new ArrayList<>();
        if (root instanceof Map<?, ?> object && object.get("schemes") instanceof List<?> schemes) {
            for (Object scheme : schemes) {
                if (scheme instanceof Map<?, ?> s) out.add(scheme(s, fallbackName));
            }
            if (out.isEmpty()) throw new ThemeImportException("a settings file with no colour schemes in it");
            return out;
        }
        if (root instanceof Map<?, ?> scheme) return List.of(scheme(scheme, fallbackName));
        if (root instanceof List<?> schemes) {
            for (Object scheme : schemes) {
                if (scheme instanceof Map<?, ?> s) out.add(scheme(s, fallbackName));
            }
            if (!out.isEmpty()) return out;
        }
        throw new ThemeImportException("not a Windows Terminal colour scheme");
    }

    private static ColorScheme scheme(Map<?, ?> scheme, String fallbackName) throws ThemeImportException {
        int[] ansi = new int[16];
        for (int i = 0; i < 8; i++) {
            ansi[i] = required(scheme, NAMES[i], i == 5 ? "magenta" : null);
            String bright = "bright" + Character.toUpperCase(NAMES[i].charAt(0)) + NAMES[i].substring(1);
            ansi[i + 8] = required(scheme, bright, i == 5 ? "brightMagenta" : null);
        }
        Object name = scheme.get("name");
        return new ColorScheme(
                name instanceof String s ? s : fallbackName,
                required(scheme, "background", null),
                required(scheme, "foreground", null),
                ansi,
                optional(scheme.get("cursorColor")),
                optional(scheme.get("selectionBackground")));
    }

    private static int required(Map<?, ?> scheme, String key, @Nullable String alias) throws ThemeImportException {
        Object value = scheme.containsKey(key) || alias == null ? scheme.get(key) : scheme.get(alias);
        Integer colour = optional(value);
        if (colour == null) throw new ThemeImportException("a scheme with no \"" + key + "\" colour");
        return colour;
    }

    private static @Nullable Integer optional(@Nullable Object value) throws ThemeImportException {
        if (value == null) return null;
        if (!(value instanceof String text)) throw new ThemeImportException("a colour that is not a string");
        return TextSchemes.hexColour(text);
    }

    /** The document as maps, lists and scalars. */
    private static @Nullable Object parse(byte[] file) throws ThemeImportException {
        JsonFactory factory = JsonFactory.builder()
                .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .build();
        try (JsonParser parser = factory.createParser(file)) {
            JsonToken first = parser.nextToken();
            if (first == null) throw new ThemeImportException("an empty file");
            return value(parser, first, 0);
        } catch (ThemeImportException e) {
            throw e;
        } catch (Exception e) {
            throw new ThemeImportException("not JSON: " + e.getMessage(), e);
        }
    }

    private static @Nullable Object value(JsonParser parser, JsonToken token, int depth) throws Exception {
        if (depth > 64) throw new ThemeImportException("JSON nested too deeply");
        switch (token) {
            case START_OBJECT -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (JsonToken next = parser.nextToken(); next != JsonToken.END_OBJECT; next = parser.nextToken()) {
                    String key = parser.currentName();
                    out.put(key, value(parser, parser.nextToken(), depth + 1));
                }
                return out;
            }
            case START_ARRAY -> {
                List<Object> out = new ArrayList<>();
                for (JsonToken next = parser.nextToken(); next != JsonToken.END_ARRAY; next = parser.nextToken()) {
                    out.add(value(parser, next, depth + 1));
                }
                return out;
            }
            case VALUE_STRING -> {
                return parser.getText();
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                return parser.getNumberValue();
            }
            case VALUE_TRUE -> {
                return Boolean.TRUE;
            }
            case VALUE_FALSE -> {
                return Boolean.FALSE;
            }
            default -> {
                return null;
            }
        }
    }
}
