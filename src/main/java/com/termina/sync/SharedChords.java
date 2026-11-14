package com.termina.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A chord as the sync repository holds it, and as each system reads it (OS-04, Pauta 6.0.1).
 *
 * <p>The repository holds one file of key bindings for every computer, so a shortcut changed on a
 * Mac is changed on a Windows or Linux computer that syncs with it, and the other way. A chord does
 * not mean the same on both: an application chord is {@code Cmd+T} on a Mac and
 * {@code Ctrl+Shift+T} elsewhere, where plain {@code Ctrl+T} belongs to the shell. So the file is
 * written in one form, the Windows and Linux one, and a Mac translates as it reads and writes:
 *
 * <ul>
 *   <li>{@code Cmd+K} on a Mac is {@code Ctrl+Shift+K} in the file, and {@code Ctrl+Shift+K} in the
 *       file is {@code Cmd+K} on a Mac. {@code Alt} (Option) rides along:
 *       {@code Cmd+Option+Down} is {@code Ctrl+Alt+Shift+Down}.
 *   <li>A chord with no {@code Cmd} on a Mac, and one without both {@code Ctrl} and {@code Shift}
 *       in the file, is carried as written: {@code F11}, {@code Alt+1}, a plain {@code Ctrl+K}.
 *   <li>{@code Ctrl+Cmd+F}, the Control key and Command together, is
 *       {@code Ctrl+Meta+Shift+F} in the file, which gives it a form of its own to come back from.
 * </ul>
 *
 * <p>Two Mac chords can share a form: {@code Cmd+K} and {@code Cmd+Shift+K} are both
 * {@code Ctrl+Shift+K}, as {@code Cmd+T} and {@code Cmd+Shift+P} are the defaults for commands
 * whose other form has the same modifiers. A Mac therefore keeps the chord it has whenever the
 * file's says the same thing ({@link #fromFile}), and only a chord that really differs replaces
 * it. A chord from a PC that names the Windows key ({@code Meta}) is read on a Mac as Command.
 *
 * <p>Chords are spelled as the settings file spells them: the modifiers {@code Ctrl},
 * {@code Alt}, {@code Meta}, {@code Shift} in that order, then the key. {@code Shortcut} is read
 * as the system's own shortcut key and never written. Pure.
 */
public final class SharedChords {

    /** A command taken off every key, which is the same everywhere. */
    private static final String NONE = "none";

    private SharedChords() {}

    /** {@code local}, this computer's chord, as the repository's file holds it. */
    public static String toShared(String local, boolean mac) {
        Parts chord = Parts.of(local, mac);
        if (chord == null) return local;
        if (!mac || !chord.command) return chord.spell(chord.control, chord.alt, chord.meta, chord.shift);
        // Command, the Mac's shortcut key: Ctrl+Shift in the file. With Control held as well, the
        // file keeps a Meta to tell the two apart.
        return chord.spell(true, chord.alt, chord.control, true);
    }

    /** {@code shared}, the repository's chord, as this computer presses it. */
    public static String fromShared(String shared, boolean mac) {
        Parts chord = Parts.of(shared, false);
        if (chord == null) return shared;
        if (!mac || !(chord.control && chord.shift)) {
            return chord.spell(chord.control, chord.alt, chord.meta, chord.shift);
        }
        // Ctrl+Shift is Command on a Mac; with Meta in the file, Control and Command together.
        return chord.spell(chord.meta, chord.alt, true, false);
    }

    /** This computer's bindings (command id to chord) as the file holds them. */
    public static Map<String, String> toFile(Map<String, String> local, boolean mac) {
        Map<String, String> out = new LinkedHashMap<>();
        local.forEach((id, chord) -> out.put(id, toShared(chord, mac)));
        return out;
    }

    /**
     * The file's bindings as this computer keeps them. A command whose chord here already says
     * what the file says keeps the chord it has, so a Mac's {@code Cmd+Shift+K} is not replaced by
     * the {@code Cmd+K} its shared form would come back as.
     *
     * @param current what this computer has now
     */
    public static Map<String, String> fromFile(Map<String, String> shared, Map<String, String> current, boolean mac) {
        Map<String, String> out = new LinkedHashMap<>();
        shared.forEach((id, chord) -> {
            String kept = current.get(id);
            boolean same = kept != null && Objects.equals(toShared(kept, mac), toShared(fromShared(chord, mac), mac));
            out.put(id, same ? kept : fromShared(chord, mac));
        });
        return out;
    }

    /** A chord's modifiers and key; {@code command} is the Mac's Command key, and only there. */
    private record Parts(boolean control, boolean alt, boolean meta, boolean shift, boolean command, String key) {

        /**
         * Reads {@code text}, or null for what is not a chord to translate: {@code none}, nothing,
         * or a modifier this does not know, which is then carried as written.
         *
         * @param mac whether {@code Meta} and {@code Shortcut} are the Command key
         */
        static @org.jspecify.annotations.Nullable Parts of(
                @org.jspecify.annotations.Nullable String text, boolean mac) {
            if (text == null || text.isBlank() || NONE.equalsIgnoreCase(text.strip())) return null;
            String[] tokens = text.strip().split("\\+", -1);
            String key = tokens[tokens.length - 1].strip();
            if (key.isEmpty()) return null; // "Ctrl++" and its like: not ours to take apart
            boolean control = false;
            boolean alt = false;
            boolean meta = false;
            boolean shift = false;
            boolean command = false;
            for (int i = 0; i < tokens.length - 1; i++) {
                switch (tokens[i].strip().toLowerCase(Locale.ROOT)) {
                    case "ctrl", "control" -> control = true;
                    case "alt" -> alt = true;
                    case "shift" -> shift = true;
                    case "meta" -> {
                        if (mac) command = true;
                        else meta = true;
                    }
                    case "shortcut" -> {
                        if (mac) command = true;
                        else control = true;
                    }
                    default -> {
                        return null;
                    }
                }
            }
            return new Parts(control, alt, meta, shift, command, key);
        }

        String spell(boolean withControl, boolean withAlt, boolean withMeta, boolean withShift) {
            List<String> out = new ArrayList<>();
            if (withControl) out.add("Ctrl");
            if (withAlt) out.add("Alt");
            if (withMeta) out.add("Meta");
            if (withShift) out.add("Shift");
            out.add(key);
            return String.join("+", out);
        }
    }
}
