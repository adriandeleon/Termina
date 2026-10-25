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
    void eachPlatformFamilyHasItsOwnFile() {
        // A chord recorded on Linux names Control, which on a Mac is not the key the user pressed.
        assertEquals(KeybindingsFile.MAC_PATH, KeybindingsFile.pathFor(true));
        assertEquals(KeybindingsFile.OTHER_PATH, KeybindingsFile.pathFor(false));
        assertNotEquals(KeybindingsFile.pathFor(true), KeybindingsFile.pathFor(false));
        assertTrue(KeybindingsFile.isKeybindings(KeybindingsFile.MAC_PATH));
        assertFalse(KeybindingsFile.isKeybindings("README.md"));
    }

    @Test
    void theSameBindingsAreTheSameBytesWhateverOrderTheyCameIn() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("newTab", "Ctrl+Shift+Y");
        a.put("closeTab", "none");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("closeTab", "none");
        b.put("newTab", "Ctrl+Shift+Y");

        String text = KeybindingsFile.text(KeybindingsFile.OTHER_PATH, a);
        assertEquals(text, KeybindingsFile.text(KeybindingsFile.OTHER_PATH, b));
        assertTrue(text.indexOf("closeTab=none") < text.indexOf("newTab=Ctrl+Shift+Y"), text);
        assertEquals(Map.of("newTab", "Ctrl+Shift+Y", "closeTab", "none"), KeybindingsFile.entries(text));
    }

    @Test
    void noBindingsIsNoFile() {
        // Every command on its default: a file listing nothing would only say that at greater length.
        assertNull(KeybindingsFile.text(KeybindingsFile.MAC_PATH, Map.of()));
        assertNull(KeybindingsFile.text(KeybindingsFile.MAC_PATH, null));
        assertEquals(Map.of(), KeybindingsFile.entries(null));
    }

    @Test
    void aHandEditedFileIsReadLeniently() {
        String text = "﻿# a comment\r\n"
                + "! also a comment\r\n"
                + "\n"
                + "  newTab =  Ctrl+Shift+Y  \r\n"
                + "this line has no equals sign\n"
                + "=Ctrl+X\n"
                + "copy=\n"
                + "bad id=Ctrl+B\n"
                + "paste=Ctrl+Shift+V\n";
        assertEquals(Map.of("newTab", "Ctrl+Shift+Y", "paste", "Ctrl+Shift+V"), KeybindingsFile.entries(text));
    }

    @Test
    void aChordCannotSmuggleInAnotherLine() {
        Map<String, String> bindings = Map.of("newTab", "Ctrl+Y\nevil=Ctrl+E", "paste", "Ctrl+Shift+V");
        assertEquals(
                Map.of("paste", "Ctrl+Shift+V"),
                KeybindingsFile.entries(KeybindingsFile.text(KeybindingsFile.OTHER_PATH, bindings)));
    }
}
