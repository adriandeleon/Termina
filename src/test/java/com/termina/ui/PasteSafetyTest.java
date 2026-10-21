package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a paste is asked about. */
class PasteSafetyTest {

    @Test
    void aNewlineAnywhereNeedsConfirmation() {
        // Including a trailing one: "rm -rf x\n" runs the moment it lands.
        assertTrue(PasteSafety.needsConfirmation("ls\n"));
        assertTrue(PasteSafety.needsConfirmation("a\nb"));
        assertTrue(PasteSafety.needsConfirmation("a\r\nb"));
        assertTrue(PasteSafety.needsConfirmation("a\rb"));
    }

    @Test
    void aSingleLineDoesNot() {
        assertFalse(PasteSafety.needsConfirmation("ls -la"));
        assertFalse(PasteSafety.needsConfirmation("a\tb"));
        assertFalse(PasteSafety.needsConfirmation(""));
        assertFalse(PasteSafety.needsConfirmation(null));
    }
}
