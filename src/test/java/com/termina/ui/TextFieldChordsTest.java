package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which commands leave their chord to a focused text field — the find bar's query. */
class TextFieldChordsTest {

    @Test
    void theEditingCommandsStepAside() {
        // Cmd+C, Cmd+V, Cmd+A and Cmd+Z on macOS are the field's own copy, paste, select and undo.
        assertTrue(WindowRules.yieldsToTextField("copy"));
        assertTrue(WindowRules.yieldsToTextField("paste"));
        assertTrue(WindowRules.yieldsToTextField("selectAll"));
        assertTrue(WindowRules.yieldsToTextField("reopenClosedTab"));
    }

    @Test
    void everythingElseStillWorksFromTheField() {
        // Find Next from inside the bar is the point of having a chord for it.
        assertFalse(WindowRules.yieldsToTextField("findNext"));
        assertFalse(WindowRules.yieldsToTextField("newTab"));
        assertFalse(WindowRules.yieldsToTextField(null));
    }
}
