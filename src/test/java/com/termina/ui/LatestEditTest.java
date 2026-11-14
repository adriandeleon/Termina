package com.termina.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits whose answers come back in any order: only the newest to a profile is applied. On macOS
 * an older commit of a profile's form answered after a newer one and wrote its empty folder back.
 */
class LatestEditTest {

    @Test
    void anOlderEditsLateAnswerIsNotTheNewest() {
        LatestEdit edits = new LatestEdit();
        long first = edits.begin("user-1");
        assertTrue(edits.isNewest("user-1", first));
        long second = edits.begin("user-1");
        // Whichever answers first or last, only the second is applied.
        assertFalse(edits.isNewest("user-1", first));
        assertTrue(edits.isNewest("user-1", second));
        assertFalse(edits.isNewest("user-1", first), "asking does not change the answer");
    }

    @Test
    void anEditToAnotherProfileSupersedesNothing() {
        LatestEdit edits = new LatestEdit();
        long one = edits.begin("user-1");
        long other = edits.begin("user-2");
        assertTrue(edits.isNewest("user-1", one), "going to another profile does not drop this one's edit");
        assertTrue(edits.isNewest("user-2", other));
        assertFalse(edits.isNewest("user-2", one), "a number is one profile's");
        assertFalse(edits.isNewest("user-3", one), "a profile never edited has no newest");
    }
}
