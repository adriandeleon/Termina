package com.termina.ui;

import java.nio.charset.StandardCharsets;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        byte[] out = KeyEncoding.encodeTyped(typed("b", false, true, false), true);
        assertArrayEquals(new byte[] {0x1b, 'b'}, out); // M-b, readline's backward-word
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
    void pasteNormalisesNewlinesToCarriageReturns() {
        // A shell reading a line wants CR; sending LF submits nothing and looks like a hang.
        assertEquals("a\rb\rc", new String(KeyEncoding.encodePaste("a\r\nb\nc", false), StandardCharsets.UTF_8));
    }

    @Test
    void bracketedPasteWrapsTheTextWhenTheShellAskedForIt() {
        String out = new String(KeyEncoding.encodePaste("ls", true), StandardCharsets.UTF_8);
        assertEquals("[200~ls[201~", out);
    }
}
