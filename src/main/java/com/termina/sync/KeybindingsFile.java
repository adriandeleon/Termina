package com.termina.sync;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * The user's key bindings as the sync repository holds them: one {@code command=chord} line per
 * changed command, sorted, in the same {@code KeyCombination} notation as the {@code keys.*} block of
 * {@code settings.json}. A command that is absent keeps its default; {@code none} is a command
 * the user took off every key.
 *
 * <p>One file for every system (OS-04, Pauta 6.0.1), so a shortcut changed on a Mac is changed on
 * the Windows and Linux computers that sync with it. Its chords are in the Windows and Linux form;
 * a Mac translates as it reads and writes ({@link SharedChords}). Until 0.15 there was a file per
 * platform family and each computer synced only its own, which kept a Mac's changes from every
 * other system: those two files are left where a repository has them and no longer read.
 *
 * <p>The merge unit is one command. Pure.
 */
public final class KeybindingsFile {

    /** The key bindings' path in the repository, for every system. */
    public static final String PATH = "keybindings/keybindings.properties";

    /** Where Macs kept theirs until 0.15; no longer read or written. */
    public static final String MAC_PATH = "keybindings/macos.properties";

    /** Where Windows and Linux kept theirs until 0.15; no longer read or written. */
    public static final String OTHER_PATH = "keybindings/windows-linux.properties";

    private KeybindingsFile() {}

    /** Whether {@code path} is the key-bindings file, or one of the two it replaced. */
    public static boolean isKeybindings(String path) {
        return PATH.equals(path) || MAC_PATH.equals(path) || OTHER_PATH.equals(path);
    }

    /**
     * The file for {@code bindings} (command id to chord), or null when there are none: no file means
     * "every command on its default", and an empty file would say the same thing in more bytes.
     */
    public static @Nullable String text(String path, @Nullable Map<String, String> bindings) {
        Map<String, String> sorted = new TreeMap<>();
        if (bindings != null) {
            bindings.forEach((id, chord) -> {
                if (isId(id) && isChord(chord)) sorted.put(id.strip(), chord.strip());
            });
        }
        if (sorted.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("# Termina key bindings, written by settings sync, for every system.\n");
        sb.append("# One changed command per line; a command not listed keeps its default, and \"none\"\n");
        sb.append("# leaves it on no key. Chords are as Windows and Linux press them: a Mac reads\n");
        sb.append("# Ctrl+Shift as Command.\n");
        sorted.forEach((id, chord) -> sb.append(id).append('=').append(chord).append('\n'));
        return sb.toString();
    }

    /**
     * The bindings in {@code text}, by command id. Lenient, like the settings file it mirrors: a line
     * that is a comment, blank, or not {@code id=chord} is passed over rather than failing the file,
     * because the repository is meant to be editable by hand.
     */
    public static Map<String, String> entries(@Nullable String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        String body = !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
        for (String raw : body.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String id = line.substring(0, eq).strip();
            String chord = line.substring(eq + 1).strip();
            if (isId(id) && isChord(chord)) out.put(id, chord);
        }
        // A computer on an older Termina still writes the ids four commands used to have; read as
        // the ids they have now, here, so the merge compares a command with itself. What this
        // computer writes back names the new ids only.
        return com.termina.config.CommandIds.renamed(out);
    }

    /** A command id as the settings file spells it after {@code keys.}. */
    static boolean isId(String id) {
        return id != null && id.strip().matches("[A-Za-z0-9_.-]+");
    }

    /** Anything on one line: whether it parses is the window's question, asked when it is applied. */
    static boolean isChord(String chord) {
        return chord != null && !chord.isBlank() && chord.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }
}
