package com.termina.theme;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The XML property lists an .itermcolors file is: every value type, and what is not one. */
class PlistXmlTest {

    private static Object read(String body) throws ThemeImportException {
        return PlistXml.parse(("<?xml version=\"1.0\"?><plist version=\"1.0\">" + body + "</plist>")
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void everyValueTypeReads() throws Exception {
        Object value = read("<dict><key>a</key><array><real>0.5</real><integer>3</integer><true/><false/>"
                + "<data>AAE=</data><date>2026-11-08T00:00:00Z</date><string>s</string></array>"
                + "<key>b</key><dict/></dict>");
        Map<?, ?> dict = (Map<?, ?>) value;
        List<?> array = (List<?>) dict.get("a");
        assertEquals(0.5, array.get(0));
        assertEquals(3L, array.get(1));
        assertEquals(Boolean.TRUE, array.get(2));
        assertEquals(Boolean.FALSE, array.get(3));
        assertEquals(2, ((byte[]) array.get(4)).length);
        assertEquals("2026-11-08T00:00:00Z", array.get(5));
        assertEquals("s", array.get(6));
    }

    @Test
    void whatIsNotAPropertyListIsRefused() {
        assertThrows(ThemeImportException.class, () -> PlistXml.parse("<html/>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ThemeImportException.class, () -> read(""));
        assertThrows(ThemeImportException.class, () -> read("<unknown/>"));
        assertThrows(
                ThemeImportException.class, () -> PlistXml.parse("<plist><dict>".getBytes(StandardCharsets.UTF_8)));
    }
}
