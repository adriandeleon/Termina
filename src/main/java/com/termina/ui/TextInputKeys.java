package com.termina.ui;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;

/**
 * Emacs caret movement and basic editing in a plain {@link TextInputControl} — the palette's query
 * field.
 *
 * <p>JavaFX's own bindings make {@code C-a} "select all" and know nothing of {@code C-k}; someone
 * whose hands expect the Emacs keymap gets the line start and a kill. A {@code KEY_PRESSED} filter
 * builds the same chord token the palette uses, looks it up in the table, runs the action and
 * consumes the event so the default binding does not also fire. Unmapped keys fall through, so
 * ordinary typing keeps working. The table is Editora's Emacs keymap for the same commands.
 *
 * <p>The character paired with a handled press is swallowed too: on macOS Option-f also emits "ƒ",
 * which would otherwise be typed into the query and re-run the search. The caret math is pure and
 * tested ({@link #lineStart}, {@link #lineEnd}).
 */
final class TextInputKeys {

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    /** The Emacs editing chords, in {@link KeyChord} tokens, and what each does to the field. */
    static final Map<String, Consumer<TextInputControl>> EMACS = Map.ofEntries(
            // The control's own motion steps a whole character; caret + 1 stops inside a surrogate pair.
            Map.entry("C-f", TextInputControl::forward),
            Map.entry("C-b", TextInputControl::backward),
            Map.entry("C-a", c -> c.positionCaret(lineStart(c.getText(), c.getCaretPosition()))),
            Map.entry("C-e", c -> c.positionCaret(lineEnd(c.getText(), c.getCaretPosition()))),
            Map.entry("M-f", TextInputControl::endOfNextWord),
            Map.entry("M-b", TextInputControl::previousWord),
            Map.entry("C-d", TextInputKeys::deleteChar),
            Map.entry("C-k", TextInputKeys::killLine),
            Map.entry("M-d", c -> {
                c.selectEndOfNextWord();
                c.replaceSelection("");
            }),
            Map.entry("C-w", TextInputControl::cut),
            Map.entry("M-w", TextInputControl::copy),
            Map.entry("C-y", TextInputControl::paste),
            Map.entry("C-/", c -> {
                if (c.isUndoable()) c.undo();
            }),
            Map.entry("C-S--", c -> {
                if (c.isUndoable()) c.undo();
            }),
            Map.entry("C-S-/", c -> {
                if (c.isRedoable()) c.redo();
            }));

    private TextInputKeys() {}

    /** Installs the Emacs chords on {@code control}. */
    static void install(TextInputControl control) {
        install(control, MAC);
    }

    /** Package-visible so both platform branches are testable; {@link #MAC} is read once at load. */
    static void install(TextInputControl control, boolean mac) {
        boolean[] consumed = {false};
        control.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            consumed[0] = false;
            if (e.isConsumed()) {
                // An earlier filter (the palette's list navigation) already claimed this chord.
                // Its typed character is that chord's by-product all the same.
                consumed[0] = true;
                return;
            }
            Consumer<TextInputControl> action = EMACS.get(KeyChord.of(e));
            if (action != null) {
                action.accept(control);
                e.consume();
                consumed[0] = true;
            }
        });
        control.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (swallowTyped(consumed[0], mac, e.isMetaDown(), e.isControlDown())) e.consume();
            consumed[0] = false;
        });
        // A handled chord that produces no KEY_TYPED at all (Control shortcuts, notably on macOS)
        // must not leave the flag set to eat the next ordinary character.
        control.addEventFilter(KeyEvent.KEY_RELEASED, e -> consumed[0] = false);
    }

    /**
     * Whether a {@code KEY_TYPED} aimed at the field is dropped instead of typed.
     *
     * <p>The character that pairs with a press this table handled always is. On macOS a character
     * typed with Command or Control held is a shortcut's by-product, never text. <b>Option on its
     * own is not a reason</b>: it is how {@code @ [ ] { } | \ ~} are typed on German and Spanish
     * Mac layouts, and swallowing every Option character would make those untypable in the query.
     * Elsewhere Ctrl+Alt is AltGr, and a character typed with Control down can be real text. Pure.
     */
    static boolean swallowTyped(boolean pressHandled, boolean mac, boolean commandDown, boolean controlDown) {
        return pressHandled || (mac && (commandDown || controlDown));
    }

    private static void deleteChar(TextInputControl c) {
        if (c.getSelection().getLength() > 0) {
            c.replaceSelection("");
        } else if (c.getCaretPosition() < c.getLength()) {
            c.deleteText(c.getCaretPosition(), c.getCaretPosition() + 1);
        }
    }

    private static void killLine(TextInputControl c) {
        String t = c.getText();
        int p = c.getCaretPosition();
        int le = lineEnd(t, p);
        if (le > p) {
            c.deleteText(p, le); // kill to end of line
        } else if (p < t.length()) {
            c.deleteText(p, p + 1); // already at line end: delete the line break
        }
    }

    // ---------------------------------------------------------------- pure caret math

    /** Offset of the first character of the line containing {@code caret}. */
    static int lineStart(String text, int caret) {
        int c = clamp(caret, text);
        return text.lastIndexOf('\n', c - 1) + 1;
    }

    /** Offset just before the line break ending {@code caret}'s line (or end of text). */
    static int lineEnd(String text, int caret) {
        int i = text.indexOf('\n', clamp(caret, text));
        return i < 0 ? text.length() : i;
    }

    private static int clamp(int caret, String text) {
        return Math.max(0, Math.min(caret, text.length()));
    }
}
