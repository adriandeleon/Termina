package com.termina.config;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * How {@code settings.properties} is read and written: properties syntax, with one change to what a
 * backslash means.
 *
 * <p>{@link java.util.Properties} treats every backslash as an escape and drops the ones it does not
 * know, so a hand-typed {@code C:\Windows\System32\wsl.exe} loaded as {@code C:WindowsSystem32wsl.exe},
 * {@code C:\tools\nu.exe} loaded with a tab in it, and a malformed <code>&#92;u</code> threw out of startup. The
 * file is documented as hand-editable and Windows paths are exactly what people type into it, so here
 * <b>a backslash is a backslash</b>, except in front of the few things this class itself writes:
 *
 * <ul>
 *   <li>{@code \\} is one backslash — so a file in Java's own escaping ({@code C:\\Windows}) reads the
 *       same as one typed plainly ({@code C:\Windows}). A UNC path typed by hand therefore needs its
 *       leading pair doubled: {@code \\\\server\share}.
 *   <li><code>&#92;uXXXX</code>, exactly four hex digits, is that character — how {@code Properties.store}
 *       wrote everything outside ASCII, so a file from before this format still reads <code>&#92;u00e9</code>
 *       as {@code é}. Anything else after <code>&#92;u</code> (<code>C:&#92;users</code>) is literal.
 *   <li>{@code \=}, {@code \:}, {@code \#}, {@code \!} and {@code \ } are that character, as
 *       {@code Properties.store} escaped them ({@code https\://host}).
 * </ul>
 *
 * <p>Every other backslash stays: {@code \t}, {@code \n}, {@code \r} and {@code \f} are <i>not</i>
 * escapes, because {@code C:\tools\nu.exe} and {@code C:\new} are far likelier in this file than a
 * tab. There are no continuation lines either — a path ending in a backslash ends there. The file is
 * UTF-8, so {@code /home/josé} survives a hand edit; one that is not valid UTF-8 (a hand edit saved in
 * Latin-1) is read as ISO-8859-1 rather than refused. A leading byte-order mark is skipped.
 *
 * <p>Reading never throws: there is no malformed input, only input that means something literal.
 *
 * <p>Only this file. Settings sync's keybinding files and the theme files are other formats with other
 * readers and are not affected.
 */
final class SettingsFormat {

    static final String HEADER =
            "# Termina settings. UTF-8; a backslash is a backslash (\\\\ is one, \\uXXXX is a character).";

    private SettingsFormat() {}

    /** The text of a file's bytes: UTF-8 if they are UTF-8, ISO-8859-1 otherwise. */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** Every key and value in the text, in file order; a key given twice keeps its last value. */
    static Map<String, String> parse(String text) {
        Map<String, String> entries = new LinkedHashMap<>();
        String body = !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
        for (String line : body.split("\r\n|\r|\n", -1)) {
            int i = skipWhitespace(line, 0);
            if (i >= line.length() || line.charAt(i) == '#' || line.charAt(i) == '!') continue;
            StringBuilder key = new StringBuilder();
            while (i < line.length()) {
                char c = line.charAt(i);
                if (c == '\\') {
                    i = unescape(line, i, key);
                } else if (c == '=' || c == ':' || isWhitespace(c)) {
                    break;
                } else {
                    key.append(c);
                    i++;
                }
            }
            i = skipWhitespace(line, i);
            if (i < line.length() && (line.charAt(i) == '=' || line.charAt(i) == ':')) {
                i = skipWhitespace(line, i + 1);
            }
            StringBuilder value = new StringBuilder();
            while (i < line.length()) {
                if (line.charAt(i) == '\\') {
                    i = unescape(line, i, value);
                } else {
                    value.append(line.charAt(i++));
                }
            }
            entries.put(key.toString(), value.toString());
        }
        return entries;
    }

    /** The whole file for these entries, sorted by key so a hand edit and a diff both read easily. */
    static String format(Map<String, String> entries) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
            out.append(escape(entry.getKey(), true))
                    .append('=')
                    .append(escape(entry.getValue(), false))
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * Writes {@code s} so that {@link #parse} reads it back exactly — and no more escaped than that,
     * so a path stays legible.
     */
    static String escape(String s, boolean key) {
        StringBuilder out = new StringBuilder(s.length());
        boolean leading = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean whitespace = isWhitespace(c);
            if (c == '\\') {
                // Only a backslash the reader would take as the start of an escape needs doubling.
                out.append(startsEscape(s, i, key) ? "\\\\" : "\\");
            } else if (Character.isISOControl(c) || isLoneSurrogate(s, i) || c == '\uFEFF') {
                out.append(String.format("\\u%04x", (int) c));
            } else if (c == ' ' && (key || leading)) {
                out.append("\\ ");
            } else if (key && (c == '=' || c == ':' || (i == 0 && (c == '#' || c == '!')))) {
                out.append('\\').append(c);
            } else {
                out.append(c);
            }
            leading &= whitespace;
        }
        return out.toString();
    }

    /** In a key, a final backslash would escape the {@code =} written after it. */
    private static boolean startsEscape(String s, int backslash, boolean key) {
        int next = backslash + 1;
        if (next >= s.length()) return key;
        char c = s.charAt(next);
        return c == '\\' || c == '=' || c == ':' || c == '#' || c == '!' || c == ' ' || isUnicodeEscape(s, next);
    }

    /** Whether {@code u} at {@code at} is followed by exactly the four hex digits of an escape. */
    private static boolean isUnicodeEscape(String s, int at) {
        if (s.charAt(at) != 'u' || at + 4 >= s.length()) return false;
        for (int k = at + 1; k <= at + 4; k++) {
            if (Character.digit(s.charAt(k), 16) < 0) return false;
        }
        return true;
    }

    /** Reads the escape at {@code backslash} into {@code out}; returns the index after it. */
    private static int unescape(String line, int backslash, StringBuilder out) {
        int next = backslash + 1;
        if (next >= line.length()) {
            out.append('\\');
            return next;
        }
        char c = line.charAt(next);
        if (isUnicodeEscape(line, next)) {
            out.append((char) Integer.parseInt(line.substring(next + 1, next + 5), 16));
            return next + 5;
        }
        if (c == '\\' || c == '=' || c == ':' || c == '#' || c == '!' || c == ' ') {
            out.append(c);
            return next + 1;
        }
        out.append('\\'); // literal; the character after it is read normally
        return next;
    }

    private static boolean isLoneSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
        if (Character.isLowSurrogate(c)) return i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
        return false;
    }

    private static int skipWhitespace(String line, int from) {
        int i = from;
        while (i < line.length() && isWhitespace(line.charAt(i))) i++;
        return i;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }
}
