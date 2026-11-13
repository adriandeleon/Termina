package com.termina.ui;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The default key bindings as data (KBD-09, Pauta 2.0.2): both platforms' files, and their rules. */
class KeymapTest {

    @Test
    void bothPlatformsBindTheSameCommands() {
        assertEquals(Keymap.load(false).keySet(), Keymap.load(true).keySet());
    }

    @Test
    void noChordIsBoundTwiceOnAPlatform() {
        for (boolean mac : new boolean[] {false, true}) {
            List<KeyCombination> chords = new ArrayList<>(Keymap.load(mac).values());
            assertEquals(List.of(), MenuAction.duplicateAccelerators(chords), mac ? "macOS" : "Windows/Linux");
        }
    }

    @Test
    void offMacOsAnApplicationChordNeverTakesPlainCtrlAndALetter() {
        // Ctrl+T is readline's transpose, Ctrl+W deletes a word, Ctrl+C is SIGINT (AGENTS.md).
        List<String> shellChords = new ArrayList<>();
        Keymap.load(false).forEach((id, chord) -> {
            if (chord instanceof KeyCodeCombination code
                    && code.getCode().isLetterKey()
                    && code.getShortcut() == KeyCombination.ModifierValue.DOWN
                    && code.getShift() != KeyCombination.ModifierValue.DOWN
                    && code.getAlt() != KeyCombination.ModifierValue.DOWN) {
                shellChords.add(id + "=" + chord.getName());
            }
        });
        assertEquals(List.of(), shellChords);
    }

    @Test
    void everyBoundIdIsACommand() throws Exception {
        Properties catalogue = new Properties();
        try (InputStream in = KeymapTest.class.getResourceAsStream("/com/termina/i18n/messages.properties")) {
            catalogue.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> unknown = new ArrayList<>();
        for (String id : Keymap.load(false).keySet()) {
            if (catalogue.getProperty(TerminalWindow.COMMAND_KEY + id) == null) unknown.add(id);
        }
        assertEquals(List.of(), unknown, "a binding for a command that does not exist does nothing");
    }

    @Test
    void theFileReadsAsIdToChord() throws Exception {
        Map<String, String> read = Keymap.parse(
                "{\"schemaVersion\": 1, \"comment\": \"x\", \"bindings\": {\"file.newTab\": \"Shortcut+Shift+T\"}}");
        assertEquals(Map.of("file.newTab", "Shortcut+Shift+T"), read);
    }
}
