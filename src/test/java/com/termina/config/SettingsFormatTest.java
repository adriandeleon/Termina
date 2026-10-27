package com.termina.config;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings file's syntax: what a hand edit means, and that what is written reads back. */
class SettingsFormatTest {

    private static final List<String> AWKWARD = List.of(
            "C:\\Windows\\System32\\wsl.exe",
            "C:\\tools\\nu.exe",
            "C:\\new\\folder",
            "C:\\users\\me",
            "C:\\ubad1\\x", // backslash-u and four hex digits, typed by hand: must still round-trip
            "\\\\server\\share\\shell.exe",
            "C:\\work\\",
            "\\",
            "\\\\",
            "a\\=b",
            "https://example.com:8443/a=b#frag!",
            "key = value : with : colons",
            "/home/josé/日本語/😀",
            "  leading spaces",
            "trailing spaces  ",
            "\ttab and\nnewline\r",
            "#not a comment",
            "!not a comment",
            "=starts with equals",
            ":starts with colon",
            "\uFEFFbom",
            "lone \uD83D surrogate",
            "");

    @Test
    void everyAwkwardValueRoundTrips() {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < AWKWARD.size(); i++) entries.put("v" + i, AWKWARD.get(i));
        assertEquals(entries, SettingsFormat.parse(SettingsFormat.format(entries)));
    }

    @Test
    void everyAwkwardKeyRoundTrips() {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < AWKWARD.size(); i++) entries.put(AWKWARD.get(i) + i, "x");
        assertEquals(entries, SettingsFormat.parse(SettingsFormat.format(entries)));
    }

    @Test
    void aBackslashTypedByHandIsABackslash() {
        Map<String, String> read = SettingsFormat.parse(
                "a=C:\\Windows\\System32\\wsl.exe\nb=C:\\tools\\nu.exe\nc=C:\\users\\me\nd=C:\\work\\\n");
        assertEquals("C:\\Windows\\System32\\wsl.exe", read.get("a"));
        assertEquals("C:\\tools\\nu.exe", read.get("b"), "\\t is not a tab here");
        assertEquals("C:\\users\\me", read.get("c"), "\\u without four hex digits is literal");
        assertEquals("C:\\work\\", read.get("d"), "no continuation lines: a trailing backslash ends the value");
    }

    @Test
    void javaStyleEscapingReadsTheSame() {
        Map<String, String> read = SettingsFormat.parse("a=C\\:\\\\Windows\\\\System32\nb=https\\://host\\=x\n");
        assertEquals("C:\\Windows\\System32", read.get("a"));
        assertEquals("https://host=x", read.get("b"));
    }

    @Test
    void aFileWrittenByPropertiesStoreReadsAsPropertiesReadsIt() throws Exception {
        // The format before this one. Everything it wrote must come back unchanged.
        Properties old = new Properties();
        old.setProperty("terminal.shell", "C:\\Windows\\System32\\wsl.exe");
        old.setProperty("links.openCommand", "editora {file}:{line}");
        old.setProperty("sync.repository", "https://github.com/me/x.git");
        old.setProperty("profile.1.directory", "/home/josé");
        old.setProperty("profile.1.name", " leading space #! = :");
        old.setProperty("keys.newTab", "Ctrl+Shift+T");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        old.store(out, "Termina settings");

        Map<String, String> read = SettingsFormat.parse(SettingsFormat.decode(out.toByteArray()));
        Map<String, String> expected = new LinkedHashMap<>();
        for (String name : old.stringPropertyNames()) expected.put(name, old.getProperty(name));
        assertEquals(expected, read);
    }

    @Test
    void separatorsAndCommentsAreProperties() {
        Map<String, String> read =
                SettingsFormat.parse("# comment\n! comment\n\n  a = 1\nb:2\nc 3\nd=\ne\nf==x\na=last\r\ng=crlf\r\n");
        assertEquals("last", read.get("a"), "a repeated key keeps its last value");
        assertEquals("2", read.get("b"));
        assertEquals("3", read.get("c"));
        assertEquals("", read.get("d"));
        assertEquals("", read.get("e"));
        assertEquals("=x", read.get("f"));
        assertEquals("crlf", read.get("g"));
        assertEquals(7, read.size());
    }

    @Test
    void escapedKeyCharactersAreThemselves() {
        Map<String, String> read = SettingsFormat.parse("a\\=b\\:c\\ d=1\n\\#e=2\n");
        assertEquals("1", read.get("a=b:c d"));
        assertEquals("2", read.get("#e"));
    }

    @Test
    void malformedUnicodeEscapesAreLiteralNotAnError() {
        Map<String, String> read = SettingsFormat.parse("a=\\u12\nb=\\uzzzz\nc=\\u00e9\\u0041\n");
        assertEquals("\\u12", read.get("a"));
        assertEquals("\\uzzzz", read.get("b"));
        assertEquals("éA", read.get("c"));
    }

    @Test
    void aByteOrderMarkIsSkipped() {
        assertEquals(Map.of("theme", "x"), SettingsFormat.parse("\uFEFFtheme=x\n"));
    }

    @Test
    void utf8IsReadAsUtf8AndAnythingElseAsLatin1() {
        assertEquals("josé", SettingsFormat.decode("josé".getBytes(StandardCharsets.UTF_8)));
        assertEquals("josé", SettingsFormat.decode("josé".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void whatIsWrittenStaysLegible() {
        String text = SettingsFormat.format(
                Map.of("terminal.shell", "C:\\Windows\\System32\\wsl.exe", "profile.1.directory", "/home/josé"));
        assertTrue(text.contains("terminal.shell=C:\\Windows\\System32\\wsl.exe\n"), text);
        assertTrue(text.contains("profile.1.directory=/home/josé\n"), text);
        assertTrue(text.startsWith("#"), "a header saying what the syntax is");
    }
}
