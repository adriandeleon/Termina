package com.termina.theme;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Windows Terminal's schemes in the shapes they are pasted in: a list of them, and what is not one. */
class WindowsTerminalSchemesTest {

    private static String scheme(String name) {
        StringBuilder json = new StringBuilder("{\"name\": \"" + name + "\", \"background\": \"#101010\", ");
        json.append("\"foreground\": \"#f0f0f0\", \"opacity\": 90, \"useAcrylic\": true, \"retro\": false, ");
        json.append("\"tabColor\": null");
        for (String colour : new String[] {"black", "red", "green", "yellow", "blue", "purple", "cyan", "white"}) {
            String bright = "bright" + Character.toUpperCase(colour.charAt(0)) + colour.substring(1);
            json.append(", \"")
                    .append(colour)
                    .append("\": \"#202020\", \"")
                    .append(bright)
                    .append("\": \"#303030\"");
        }
        return json.append('}').toString();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aListOfSchemesIsEveryOneOfThem() throws Exception {
        List<ColorScheme> read =
                WindowsTerminalSchemes.read(bytes("[" + scheme("One") + ", 3, " + scheme("Two") + "]"), "fallback");
        assertEquals(List.of("One", "Two"), read.stream().map(ColorScheme::name).toList());
    }

    @Test
    void whatIsNotASchemeIsRefusedWithWhy() {
        assertThrows(ThemeImportException.class, () -> WindowsTerminalSchemes.read(bytes("[1, 2]"), "x"));
        assertThrows(ThemeImportException.class, () -> WindowsTerminalSchemes.read(bytes("42"), "x"));
        assertThrows(ThemeImportException.class, () -> WindowsTerminalSchemes.read(bytes("{\"name\": "), "x"));
        assertThrows(ThemeImportException.class, () -> WindowsTerminalSchemes.read(bytes(""), "x"));
    }
}
