package com.termina.sync;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The repository's form of the key bindings. */
class KeybindingsFileTest {

    @Test
    void everySystemSharesOneFile() {
        // OS-04 (Pauta 6.0.1): the two files per platform family are no longer written.
        assertEquals("keybindings/keybindings.properties", KeybindingsFile.PATH);
        assertNotEquals(KeybindingsFile.PATH, KeybindingsFile.MAC_PATH);
        assertNotEquals(KeybindingsFile.PATH, KeybindingsFile.OTHER_PATH);
        assertTrue(KeybindingsFile.isKeybindings(KeybindingsFile.PATH));
        assertTrue(KeybindingsFile.isKeybindings(KeybindingsFile.MAC_PATH));
        assertFalse(KeybindingsFile.isKeybindings("README.md"));
    }

    @Test
    void theSameBindingsAreTheSameBytesWhateverOrderTheyCameIn() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("file.newTab", "Ctrl+Shift+Y");
        a.put("file.closeTab", "none");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("file.closeTab", "none");
        b.put("file.newTab", "Ctrl+Shift+Y");

        String text = KeybindingsFile.text(KeybindingsFile.PATH, a);
        assertEquals(text, KeybindingsFile.text(KeybindingsFile.PATH, b));
        assertTrue(text.indexOf("file.closeTab=none") < text.indexOf("file.newTab=Ctrl+Shift+Y"), text);
        assertEquals(Map.of("file.newTab", "Ctrl+Shift+Y", "file.closeTab", "none"), KeybindingsFile.entries(text));
    }

    @Test
    void noBindingsIsNoFile() {
        // Every command on its default: a file listing nothing would only say that at greater length.
        assertNull(KeybindingsFile.text(KeybindingsFile.PATH, Map.of()));
        assertNull(KeybindingsFile.text(KeybindingsFile.PATH, null));
        assertEquals(Map.of(), KeybindingsFile.entries(null));
    }

    @Test
    void aHandEditedFileIsReadLeniently() {
        // The ids as an older Termina wrote them, before each had its area: read as today's.
        String text = "﻿# a comment\r\n"
                + "! also a comment\r\n"
                + "\n"
                + "  newTab =  Ctrl+Shift+Y  \r\n"
                + "this line has no equals sign\n"
                + "=Ctrl+X\n"
                + "copy=\n"
                + "bad id=Ctrl+B\n"
                + "paste=Ctrl+Shift+V\n";
        assertEquals(
                Map.of("file.newTab", "Ctrl+Shift+Y", "edit.paste", "Ctrl+Shift+V"), KeybindingsFile.entries(text));
    }

    @Test
    void aChordCannotSmuggleInAnotherLine() {
        Map<String, String> bindings = Map.of("file.newTab", "Ctrl+Y\nevil=Ctrl+E", "edit.paste", "Ctrl+Shift+V");
        assertEquals(
                Map.of("edit.paste", "Ctrl+Shift+V"),
                KeybindingsFile.entries(KeybindingsFile.text(KeybindingsFile.PATH, bindings)));
    }

    /**
     * A computer on an older Termina writes the ids four commands used to have: they are read as
     * the ids the commands have now, and written back under those.
     */
    @Test
    void anOldIdIsReadAsTheCommandsNewOne() {
        String theirs = "commandPalette=Alt+X\nabout=none\nnewTab=Ctrl+Shift+Y\n";
        Map<String, String> read = KeybindingsFile.entries(theirs);
        assertEquals(Map.of("palette.show", "Alt+X", "help.about", "none", "file.newTab", "Ctrl+Shift+Y"), read);
        String written = KeybindingsFile.text(KeybindingsFile.PATH, read);
        assertTrue(written.contains("palette.show=Alt+X\n"), written);
        assertFalse(written.contains("commandPalette"), written);
        // Both in one file, as after an old and a new computer have each written: the new id's stands.
        assertEquals(Map.of("palette.show", "F1"), KeybindingsFile.entries("commandPalette=Alt+X\npalette.show=F1\n"));
    }
}
