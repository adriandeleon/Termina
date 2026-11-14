package com.termina.sync;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OS-04 (Pauta 6.0.1): one file of key bindings for every system. A chord is written as Windows
 * and Linux press it, and a Mac reads Ctrl+Shift as Command and writes Command as Ctrl+Shift.
 */
class SharedChordsTest {

    private static final boolean MAC = true;
    private static final boolean PC = false;

    @Test
    void aMacsCommandIsCtrlShiftInTheFileAndBack() {
        assertEquals("Ctrl+Shift+K", SharedChords.toShared("Meta+K", MAC));
        assertEquals("Ctrl+Shift+K", SharedChords.toShared("Shortcut+K", MAC), "Shortcut is Command on a Mac");
        assertEquals("Meta+K", SharedChords.fromShared("Ctrl+Shift+K", MAC));
        // Option rides along: the defaults for moving between panes are exactly this pair.
        assertEquals("Ctrl+Alt+Shift+Down", SharedChords.toShared("Alt+Meta+Down", MAC));
        assertEquals("Alt+Meta+Down", SharedChords.fromShared("Ctrl+Alt+Shift+Down", MAC));
        assertEquals("Ctrl+Shift+Close Bracket", SharedChords.toShared("Meta+Close Bracket", MAC));
    }

    @Test
    void windowsAndLinuxReadAndWriteTheFileAsItIs() {
        for (String chord : List.of("Ctrl+Shift+K", "Ctrl+Alt+Shift+Down", "Alt+1", "F11", "Ctrl+K", "Meta+X")) {
            assertEquals(chord, SharedChords.toShared(chord, PC));
            assertEquals(chord, SharedChords.fromShared(chord, PC));
        }
        // Spelled one way: the order the settings file uses, and never Shortcut.
        assertEquals("Ctrl+Shift+Y", SharedChords.toShared("Shortcut+Shift+Y", PC));
        assertEquals("Ctrl+Shift+Y", SharedChords.toShared("shift+ctrl+Y", PC));
    }

    @Test
    void whatIsNoApplicationChordIsCarriedAsWritten() {
        for (String chord : List.of("F11", "Alt+1", "Ctrl+K", "Shift+Insert", "Ctrl+Alt+T")) {
            assertEquals(chord, SharedChords.toShared(chord, MAC), chord);
            assertEquals(chord, SharedChords.fromShared(chord, MAC), chord);
        }
        for (boolean mac : new boolean[] {MAC, PC}) {
            assertEquals("none", SharedChords.toShared("none", mac), "off every key, everywhere");
            assertEquals("none", SharedChords.fromShared("none", mac));
            assertEquals("Hyper+K", SharedChords.toShared("Hyper+K", mac), "a modifier it does not know");
            assertEquals("", SharedChords.toShared("", mac));
        }
    }

    @Test
    void controlAndCommandTogetherHaveAFormOfTheirOwn() {
        // The Mac default for full screen. In the file it keeps a Meta, so it comes back as it went.
        assertEquals("Ctrl+Meta+Shift+F", SharedChords.toShared("Ctrl+Meta+F", MAC));
        assertEquals("Ctrl+Meta+F", SharedChords.fromShared("Ctrl+Meta+Shift+F", MAC));
    }

    @Test
    void everyChordOfTheFileComesBackFromAMacAsItWent() {
        // What keeps a sync from sending a chord back changed, round after round.
        for (String shared :
                List.of("Ctrl+Shift+K", "Ctrl+Alt+Shift+Down", "Ctrl+Meta+Shift+F", "Ctrl+K", "Alt+1", "F11", "none")) {
            assertEquals(shared, SharedChords.toShared(SharedChords.fromShared(shared, MAC), MAC), shared);
        }
    }

    @Test
    void aMacKeepsTheChordItHasWhereTheFileSaysTheSame() {
        // Cmd+Shift+K and Cmd+K are one chord in the file, as Cmd+T and Cmd+Shift+P are the
        // defaults of commands whose other form has the same modifiers.
        assertEquals(SharedChords.toShared("Meta+K", MAC), SharedChords.toShared("Meta+Shift+K", MAC));

        Map<String, String> current = new LinkedHashMap<>();
        current.put("edit.clearScrollback", "Meta+Shift+K");
        current.put("file.newTab", "Meta+T");
        Map<String, String> file = new LinkedHashMap<>();
        file.put("edit.clearScrollback", "Ctrl+Shift+K"); // says the same: kept
        file.put("file.newTab", "Ctrl+Shift+Y"); // differs: replaced
        file.put("edit.copy", "Ctrl+Shift+U"); // new here
        assertEquals(
                Map.of("edit.clearScrollback", "Meta+Shift+K", "file.newTab", "Meta+Y", "edit.copy", "Meta+U"),
                SharedChords.fromFile(file, current, MAC));
        // A command the file no longer lists goes back to its default: it is not in the result.
        assertEquals(Map.of(), SharedChords.fromFile(Map.of(), current, MAC));

        assertEquals(
                Map.of("edit.clearScrollback", "Ctrl+Shift+K", "file.newTab", "Ctrl+Shift+T"),
                SharedChords.toFile(current, MAC));
    }
}
