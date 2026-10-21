package com.termina.ui;

import java.nio.charset.StandardCharsets;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The key-to-bytes mapping. Pure enough to test without a toolkit. */
class KeyEncodingTest {

    /** Stands in for the emulator: records what it was asked and answers with a fixed sequence. */
    private static final class RecordingLookup implements KeyEncoding.CodeLookup {
        int lastKeyCode = -1;
        int lastModifiers = -1;
        byte[] answer;

        RecordingLookup(byte[] answer) {
            this.answer = answer;
        }

        @Override
        public byte[] apply(int awtKeyCode, int awtModifiers) {
            lastKeyCode = awtKeyCode;
            lastModifiers = awtModifiers;
            return answer;
        }
    }

    private static KeyEvent pressed(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta);
    }

    /**
     * A press carrying the text the platform reports, which for a composed chord is not the key.
     *
     * <p>Option+F on macOS arrives as {@code \u0192} — the ƒ the OS composed — on both the press
     * and the typed event. Recorded from a real key press through {@code -Dtermina.keyTrace}.
     */
    private static KeyEvent pressedWithText(KeyCode code, String text, boolean shift, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", text, code, shift, false, alt, false);
    }

    private static KeyEvent typed(String character, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED, false, ctrl, alt, meta);
    }

    @Test
    void arrowKeysGoThroughTheEmulatorSoCursorKeyModeIsRespected() {
        RecordingLookup lookup = new RecordingLookup(new byte[] {0x1b, 'O', 'A'});
        byte[] out = KeyEncoding.encodePressed(pressed(KeyCode.UP, false, false, false, false), lookup, true);

        // 38 is java.awt.event.KeyEvent.VK_UP — the encoder's vocabulary.
        assertEquals(38, lookup.lastKeyCode);
        assertArrayEquals(new byte[] {0x1b, 'O', 'A'}, out);
    }

    @Test
    void modifiersUseAwtExtendedDownMasks() {
        RecordingLookup lookup = new RecordingLookup(new byte[] {1});
        KeyEncoding.encodePressed(pressed(KeyCode.LEFT, true, true, false, false), lookup, true);

        // SHIFT_DOWN_MASK | CTRL_DOWN_MASK. The plain masks (1 | 2) would silently miss every
        // modified binding, since the encoder falls back to the unmodified entry.
        assertEquals((1 << 6) | (1 << 7), lookup.lastModifiers);
    }

    @Test
    void altIsExcludedFromModifiersWhenItComposesCharactersInstead() {
        RecordingLookup lookup = new RecordingLookup(new byte[] {1});
        KeyEncoding.encodePressed(pressed(KeyCode.LEFT, false, false, true, false), lookup, false);
        assertEquals(0, lookup.lastModifiers);
    }

    @Test
    void controlLetterBecomesTheMatchingC0Byte() {
        RecordingLookup lookup = new RecordingLookup(null);
        assertArrayEquals(
                new byte[] {0x03}, // Ctrl+C -> ETX, which the tty turns into SIGINT
                KeyEncoding.encodePressed(pressed(KeyCode.C, false, true, false, false), lookup, true));
        assertArrayEquals(
                new byte[] {0x01}, // Ctrl+A -> SOH (readline: beginning-of-line)
                KeyEncoding.encodePressed(pressed(KeyCode.A, false, true, false, false), lookup, true));
    }

    @Test
    void tabAndEscapeAreSentEvenThoughTheEmulatorCannotEncodeThem() {
        // JediTerm's key encoder maps Enter and Backspace but not these two, and both are control
        // characters so the KEY_TYPED path discards them as well. Unhandled they reach the shell by
        // no route at all — and worse, the event goes unconsumed and JavaFX treats Tab as focus
        // traversal, moving focus out of the terminal so everything typed afterwards lands
        // elsewhere. That is what "the terminal freezes when I hit tab" was.
        RecordingLookup none = new RecordingLookup(null);
        assertArrayEquals(
                new byte[] {0x09},
                KeyEncoding.encodePressed(pressed(KeyCode.TAB, false, false, false, false), none, true));
        assertArrayEquals(
                new byte[] {0x1b},
                KeyEncoding.encodePressed(pressed(KeyCode.ESCAPE, false, false, false, false), none, true));
    }

    @Test
    void shiftTabIsBackTab() {
        // ESC [ Z (CBT) is what xterm sends and what readline and every TUI expect.
        RecordingLookup none = new RecordingLookup(null);
        assertArrayEquals(
                new byte[] {0x1b, '[', 'Z'},
                KeyEncoding.encodePressed(pressed(KeyCode.TAB, true, false, false, false), none, true));
    }

    @Test
    void theEmulatorStillWinsWhenItHasAnEncoding() {
        // The fallback must not shadow mode-dependent encodings — an emulator answer always wins.
        RecordingLookup lookup = new RecordingLookup(new byte[] {0x1b, 'O', 'A'});
        assertArrayEquals(
                new byte[] {0x1b, 'O', 'A'},
                KeyEncoding.encodePressed(pressed(KeyCode.TAB, false, false, false, false), lookup, true));
    }

    @Test
    void everyKeyWeClaimToHandleProducesSomething() {
        // The real invariant: a key in the VK table must never return null with no encoder, because
        // null means unconsumed, and unconsumed means JavaFX may act on it.
        RecordingLookup none = new RecordingLookup(null);
        for (KeyCode code : new KeyCode[] {KeyCode.TAB, KeyCode.ESCAPE, KeyCode.ENTER, KeyCode.BACK_SPACE}) {
            assertNotNull(
                    KeyEncoding.encodePressed(pressed(code, false, false, false, false), none, true),
                    () -> code + " must not fall through unconsumed");
        }
    }

    @Test
    void unmodifiedLetterFallsThroughToKeyTypedSoImeAndDeadKeysWork() {
        RecordingLookup lookup = new RecordingLookup(null);
        assertNull(KeyEncoding.encodePressed(pressed(KeyCode.A, false, false, false, false), lookup, true));
    }

    @Test
    void typedCharacterIsSentAsUtf8() {
        assertArrayEquals(
                "é".getBytes(StandardCharsets.UTF_8), KeyEncoding.encodeTyped(typed("é", false, false, false), true));
    }

    @Test
    void altPrefixesEscapeWhenActingAsMeta() {
        // From the press, not the typed event. Both produce 1b 62 where the platform leaves the
        // character alone, but only the press still does where the OS composes it: macOS turns
        // Option+B into ∫, and ESC-prefixing that sends bytes no shell can read as M-b.
        byte[] out = KeyEncoding.encodePressed(
                pressedWithText(KeyCode.B, "\u222b", false, true), new RecordingLookup(null), true);
        assertArrayEquals(new byte[] {0x1b, 'b'}, out); // M-b, readline's backward-word

        // And the typed twin is dropped, so the keystroke is not also sent as the composed glyph.
        assertNull(KeyEncoding.encodeTyped(typed("\u222b", false, true, false), true));
    }

    @Test
    void altComposesRatherThanMetaWhenConfiguredThatWay() {
        // On macOS with Option-as-compose, Option+b produces "∫" and must be sent as that character.
        byte[] out = KeyEncoding.encodeTyped(typed("∫", false, true, false), false);
        assertArrayEquals("∫".getBytes(StandardCharsets.UTF_8), out);
    }

    @Test
    void controlCharactersFromKeyTypedAreDroppedToAvoidDoubleSending() {
        // KEY_PRESSED already encoded this chord; some platforms deliver it again as a character.
        assertNull(KeyEncoding.encodeTyped(typed("", true, false, false), true));
        assertNull(KeyEncoding.encodeTyped(typed("\r", false, false, false), true));
    }

    @Test
    void altGrComposesCharactersWithoutSendingControlBytes() {
        for (boolean meta : new boolean[] {false, true}) {
            assertNull(KeyEncoding.encodePressed(
                    pressed(KeyCode.Q, false, true, true, false), new RecordingLookup(null), meta));
            assertArrayEquals(
                    "@".getBytes(StandardCharsets.UTF_8), KeyEncoding.encodeTyped(typed("@", true, true, false), meta));
        }
    }

    @Test
    void pasteNormalisesNewlinesToCarriageReturns() {
        // A shell reading a line wants CR; sending LF submits nothing and looks like a hang.
        assertEquals("a\rb\rc", new String(KeyEncoding.encodePaste("a\r\nb\nc", false), StandardCharsets.UTF_8));
    }

    @Test
    void bracketedPasteWrapsTheTextWhenTheShellAskedForIt() {
        String out = new String(KeyEncoding.encodePaste("ls", true), StandardCharsets.UTF_8);
        assertEquals("[200~ls[201~", out);
    }

    // --- Alt as Meta, where the composed character is the trap ----------------------------------

    @Test
    void metaSendsEscapeAndTheUnmodifiedKey() {
        // M-f is forward-word: ESC then 'f'. Not ESC then whatever Option composed.
        byte[] out = KeyEncoding.encodePressed(
                pressedWithText(KeyCode.F, "\u0192", false, true), new RecordingLookup(null), true);
        assertArrayEquals(new byte[] {0x1b, 'f'}, out);
    }

    @Test
    void theComposedCharacterIsNeverSentInMetaMode() {
        // The bug this pins: prefixing ESC to ƒ's UTF-8 put 1b c6 92 on the wire, the shell ate c6
        // with the escape, and the leftover 92 is a lone continuation byte — invalid UTF-8, which
        // renders as an unprintable box wherever the cursor happened to be.
        assertNull(KeyEncoding.encodeTyped(typed("\u0192", false, true, false), true));
    }

    @Test
    void shiftPicksTheUpperCaseWidget() {
        // M-u and M-U are different readline widgets, so the case has to survive.
        byte[] out = KeyEncoding.encodePressed(
                pressedWithText(KeyCode.U, "\u00a8", true, true), new RecordingLookup(null), true);
        assertArrayEquals(new byte[] {0x1b, 'U'}, out);
    }

    @Test
    void withAltAsMetaOffTheComposedCharacterIsTheWholePoint() {
        // Option is a compose key then, and ƒ is what the user asked for.
        assertNull(KeyEncoding.encodePressed(
                pressedWithText(KeyCode.F, "\u0192", false, true), new RecordingLookup(null), false));
        assertArrayEquals(
                "\u0192".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                KeyEncoding.encodeTyped(typed("\u0192", false, true, false), false));
    }

    @Test
    void altGrIsNotMeta() {
        // Ctrl+Alt is how Windows and Linux keyboards reach @ \ and friends. Reading it as Meta
        // would make those layouts unable to type characters they have no other key for.
        assertArrayEquals(new byte[] {'@'}, KeyEncoding.encodeTyped(typed("@", true, true, false), true));
        assertArrayEquals(new byte[] {'@'}, KeyEncoding.encodeTyped(typed("@", false, true, false), false));
    }

    @Test
    void aKeyWithNoPlainCharacterHasNoMetaForm() {
        // Nothing to prefix, and inventing a byte would send a keystroke the user never made.
        assertNull(KeyEncoding.metaBase(pressedWithText(KeyCode.SHIFT, "", false, true)));
    }

    // --- Ctrl+Alt: Meta over a control byte, unless it is AltGr -------------------------------

    private static KeyEvent ctrlAlt(KeyCode code, String text) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", text, code, false, true, true, false);
    }

    @Test
    void ctrlAltLetterIsEscapeThenTheControlByte() {
        // Readline's C-M-f. Had no route at all: the control branch wants Alt up, the meta branch
        // wants Ctrl up, and KEY_TYPED drops control characters.
        assertArrayEquals(
                new byte[] {0x1b, 0x06},
                KeyEncoding.encodePressed(ctrlAlt(KeyCode.F, "f"), new RecordingLookup(null), true));
        // The press may carry the control form instead, which is just as plainly the key.
        assertArrayEquals(
                new byte[] {0x1b, 0x06},
                KeyEncoding.encodePressed(ctrlAlt(KeyCode.F, "\u0006"), new RecordingLookup(null), true));
    }

    @Test
    void aPressWithNoTextIsLeftToKeyTypedBecauseItCouldBeAltGr() {
        // With nothing to judge by, guessing "plain" would cost some layout its @ key. This is also
        // what altGrComposesCharactersWithoutSendingControlBytes above pins from the other side.
        assertNull(KeyEncoding.encodePressed(ctrlAlt(KeyCode.F, ""), new RecordingLookup(null), true));
        assertFalse(KeyEncoding.isPlainCtrlAlt(ctrlAlt(KeyCode.F, "")));
    }

    @Test
    void altGrIsStillLeftToKeyTyped() {
        // AltGr+Q on a German layout: the Q key, carrying @. That is a composition and it goes
        // through KEY_TYPED as before; sending ESC + 0x11 instead would break typing @ at all.
        assertNull(KeyEncoding.encodePressed(ctrlAlt(KeyCode.Q, "@"), new RecordingLookup(null), true));
        assertArrayEquals(new byte[] {'@'}, KeyEncoding.encodeTyped(typed("@", true, true, false), true));
        assertFalse(KeyEncoding.isPlainCtrlAlt(ctrlAlt(KeyCode.Q, "@")));
        assertFalse(KeyEncoding.isPlainCtrlAlt(ctrlAlt(KeyCode.E, "€")));
        assertTrue(KeyEncoding.isPlainCtrlAlt(ctrlAlt(KeyCode.Q, "q")));
        assertTrue(KeyEncoding.isPlainCtrlAlt(ctrlAlt(KeyCode.Q, "Q")));
    }

    @Test
    void theTypedTwinOfCtrlAltLetterIsDropped() {
        // The press sent it; a platform that also delivers the letter must not get it twice.
        assertNull(KeyEncoding.encodeTyped(typed("f", true, true, false), true));
        // Only in Meta mode — with Alt composing, Ctrl+Alt is whatever the OS says it is.
        assertArrayEquals(new byte[] {'f'}, KeyEncoding.encodeTyped(typed("f", true, true, false), false));
    }

    @Test
    void ctrlAltOnlyCoversLettersAndDigits() {
        // Ctrl+Alt+[ would otherwise double up with the typed '[' on a US layout.
        assertNull(KeyEncoding.encodePressed(ctrlAlt(KeyCode.OPEN_BRACKET, "["), new RecordingLookup(null), true));
    }

    // --- Ctrl+digit, per xterm ----------------------------------------------------------------

    @Test
    void controlDigitsFollowXterm() {
        assertArrayEquals(new byte[] {0x00}, KeyEncoding.controlByte(KeyCode.DIGIT2));
        assertArrayEquals(new byte[] {0x1b}, KeyEncoding.controlByte(KeyCode.DIGIT3));
        assertArrayEquals(new byte[] {0x1c}, KeyEncoding.controlByte(KeyCode.DIGIT4));
        assertArrayEquals(new byte[] {0x1d}, KeyEncoding.controlByte(KeyCode.DIGIT5));
        assertArrayEquals(new byte[] {0x1e}, KeyEncoding.controlByte(KeyCode.DIGIT6));
        assertArrayEquals(new byte[] {0x1f}, KeyEncoding.controlByte(KeyCode.DIGIT7)); // readline's undo
        assertArrayEquals(new byte[] {0x7f}, KeyEncoding.controlByte(KeyCode.DIGIT8));
        assertNull(KeyEncoding.controlByte(KeyCode.DIGIT1));
        assertNull(KeyEncoding.controlByte(KeyCode.DIGIT9));
    }

    // --- the numeric keypad -------------------------------------------------------------------

    @Test
    void keypadArrowsGoThroughTheEmulatorLikeTheOthers() {
        // NumLock off sends KP_UP, which was not in the table and so went unconsumed. 224 is
        // VK_KP_UP, which JediTerm's encoder knows.
        RecordingLookup lookup = new RecordingLookup(new byte[] {0x1b, '[', 'A'});
        byte[] out = KeyEncoding.encodePressed(pressed(KeyCode.KP_UP, false, false, false, false), lookup, true);
        assertEquals(224, lookup.lastKeyCode);
        assertArrayEquals(new byte[] {0x1b, '[', 'A'}, out);
        KeyEncoding.encodePressed(pressed(KeyCode.KP_RIGHT, false, false, false, false), lookup, true);
        assertEquals(227, lookup.lastKeyCode);
    }

    // --- paste sanitising ---------------------------------------------------------------------

    @Test
    void escapeAndControlsAreStrippedFromAPaste() {
        // The attack: a clipboard carrying the close bracket ends the paste early and the rest is
        // keystrokes. With ESC gone there is no sequence left to end it with.
        assertEquals("ls[201~; rm x", KeyEncoding.sanitise("ls\u001b[201~; rm x"));
        assertEquals("ab", KeyEncoding.sanitise("a\u0000\u0001\u0007\u007fb"));
        assertEquals("ab", KeyEncoding.sanitise("a\u009bb")); // 8-bit CSI
    }

    @Test
    void tabsAndNewlinesSurviveSanitising() {
        assertEquals("a\tb\r\nc\rd\ne", KeyEncoding.sanitise("a\tb\r\nc\rd\ne"));
        assertEquals("café 日本", KeyEncoding.sanitise("café 日本"));
        assertEquals("", KeyEncoding.sanitise(null));
    }

    @Test
    void aCleanPasteIsReturnedAsIs() {
        String clean = "nothing to strip";
        assertTrue(clean == KeyEncoding.sanitise(clean));
    }

    @Test
    void encodePasteSanitisesBeforeBracketing() {
        String out = new String(KeyEncoding.encodePaste("a\u001b[201~b", true), StandardCharsets.UTF_8);
        assertEquals("\u001b[200~a[201~b\u001b[201~", out);
    }
}
