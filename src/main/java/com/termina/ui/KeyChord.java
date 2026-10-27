package com.termina.ui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import org.jspecify.annotations.Nullable;

/**
 * A key press as a chord token — {@code C-n}, {@code M-x}, {@code C-S-/}, {@code pagedown} — the
 * vocabulary the palette's key tables are written in.
 *
 * <p>The same tokens Editora's keymaps use, so its Emacs bindings can be carried over verbatim
 * ({@code C-n} is next line there and here). Modifiers are emitted in a fixed order, Control, Alt,
 * Command, Shift, so a token can be compared as a string. The {@code M-} prefix is Alt on every
 * platform: Option on a Mac reports as Alt in JavaFX, and that is what Emacs users there have as Meta.
 *
 * <p>Tokens are shown to the user in the platform's own notation ({@link #display}), never raw —
 * everything else in the window writes "Ctrl+Shift+T", and a legend saying "C-n" beside a menu
 * saying "Ctrl+N" would be two names for one key.
 */
final class KeyChord {

    private KeyChord() {}

    /** The token for a key event, or null for a modifier pressed on its own. */
    static @Nullable String of(KeyEvent event) {
        return token(
                event.getCode(), event.isControlDown(), event.isAltDown(), event.isMetaDown(), event.isShiftDown());
    }

    /** The token for a key with these modifiers held, or null for a modifier or an unknown key. Pure. */
    static @Nullable String token(KeyCode code, boolean control, boolean alt, boolean meta, boolean shift) {
        if (code == null || code == KeyCode.UNDEFINED || code.isModifierKey()) return null;
        StringBuilder sb = new StringBuilder();
        if (control) sb.append("C-");
        if (alt) sb.append("M-");
        if (meta) sb.append("Cmd-");
        if (shift) sb.append("S-");
        sb.append(keyName(code));
        return sb.toString();
    }

    static String keyName(KeyCode code) {
        if (code.isLetterKey()) return code.getName().toLowerCase(Locale.ROOT);
        if (code.isDigitKey()) {
            // Main-row digits name themselves; a numpad digit is "Numpad 6", and the trailing digit
            // is the part that means anything.
            String name = code.getName();
            return name.length() == 1 ? name : name.substring(name.length() - 1);
        }
        return switch (code) {
            case SPACE -> "space";
            case SLASH -> "/";
            case BACK_SLASH -> "\\";
            case PERIOD -> ".";
            case COMMA -> ",";
            case SEMICOLON -> ";";
            case MINUS -> "-";
            case EQUALS -> "=";
            case OPEN_BRACKET -> "[";
            case CLOSE_BRACKET -> "]";
            case ENTER -> "enter";
            case TAB -> "tab";
            case BACK_SPACE -> "backspace";
            case DELETE -> "delete";
            case ESCAPE -> "escape";
            case LEFT -> "left";
            case RIGHT -> "right";
            case UP -> "up";
            case DOWN -> "down";
            case HOME -> "home";
            case END -> "end";
            case PAGE_UP -> "pageup";
            case PAGE_DOWN -> "pagedown";
            default -> code.getName().toLowerCase(Locale.ROOT).replace(' ', '-');
        };
    }

    /** The inverse of {@link #keyName}, built once; a numpad digit maps back to the main-row one. */
    private static final Map<String, KeyCode> BY_NAME = byName();

    private static Map<String, KeyCode> byName() {
        Map<String, KeyCode> map = new HashMap<>();
        for (KeyCode code : KeyCode.values()) {
            if (code == KeyCode.UNDEFINED || code.isModifierKey()) continue;
            map.putIfAbsent(keyName(code), code);
        }
        // Main-row digits over the numpad's, whichever the enum listed first.
        for (char d = '0'; d <= '9'; d++) map.put(String.valueOf(d), KeyCode.valueOf("DIGIT" + d));
        return map;
    }

    /**
     * The {@link KeyCombination} a token names, or null for one this class did not produce. The
     * way a token becomes something JavaFX can display and match.
     */
    static @Nullable KeyCombination toCombination(String token) {
        if (token == null || token.isEmpty()) return null;
        java.util.List<KeyCombination.Modifier> modifiers = new java.util.ArrayList<>();
        String rest = token;
        // Longest prefix first: "Cmd-" must not be read as "C-" followed by "md-".
        while (true) {
            if (rest.startsWith("Cmd-")) {
                modifiers.add(KeyCombination.META_DOWN);
                rest = rest.substring(4);
            } else if (rest.startsWith("C-") && rest.length() > 2) {
                modifiers.add(KeyCombination.CONTROL_DOWN);
                rest = rest.substring(2);
            } else if (rest.startsWith("M-") && rest.length() > 2) {
                modifiers.add(KeyCombination.ALT_DOWN);
                rest = rest.substring(2);
            } else if (rest.startsWith("S-") && rest.length() > 2) {
                modifiers.add(KeyCombination.SHIFT_DOWN);
                rest = rest.substring(2);
            } else {
                break;
            }
        }
        KeyCode code = BY_NAME.get(rest);
        if (code == null) return null;
        return new KeyCodeCombination(code, modifiers.toArray(KeyCombination.Modifier[]::new));
    }

    /**
     * A token in the platform's notation — {@code Ctrl+N} here, {@code ⌃N} on a Mac — or the token
     * itself for one with no combination, so a legend never goes blank for a key it cannot name.
     */
    static String display(String token) {
        KeyCombination combination = toCombination(token);
        return combination == null ? token : combination.getDisplayText();
    }
}
