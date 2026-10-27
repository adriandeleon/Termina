package com.termina.sync;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * The user's key bindings as the sync repository holds them: one {@code command=chord} line per
 * changed command, sorted, in the same {@code KeyCombination} notation as the {@code keys.*} block of
 * {@code settings.properties}. A command that is absent keeps its default; {@code none} is a command
 * the user took off every key.
 *
 * <p>One file per platform family, because a chord means something different on each: the defaults
 * are {@code Shortcut+…} on macOS and {@code Ctrl+Shift+…} elsewhere, and a chord recorded on Linux
 * names the Control key, which on a Mac is a different key from the one the user pressed. Each computer
 * syncs only its own family's file and leaves the other one as the repository has it, so a Mac and a
 * Linux machine share one repository without translating each other's chords. Windows and Linux share a
 * file: both use the same modifiers.
 *
 * <p>The merge unit is one command. Pure.
 */
public final class KeybindingsFile {

    /** The macOS bindings' path in the repository. */
    public static final String MAC_PATH = "keybindings/macos.properties";

    /** The Windows and Linux bindings' path in the repository. */
    public static final String OTHER_PATH = "keybindings/windows-linux.properties";

    private KeybindingsFile() {}

    /** The file this computer syncs. */
    public static String pathFor(boolean mac) {
        return mac ? MAC_PATH : OTHER_PATH;
    }

    /** Whether {@code path} is one of the key-binding files. */
    public static boolean isKeybindings(String path) {
        return MAC_PATH.equals(path) || OTHER_PATH.equals(path);
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
        sb.append("# Termina key bindings for ")
                .append(MAC_PATH.equals(path) ? "macOS" : "Windows and Linux")
                .append(", written by settings sync.\n");
        sb.append("# One changed command per line; a command not listed keeps its default, and \"none\"\n");
        sb.append("# leaves it on no key.\n");
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
        return out;
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
