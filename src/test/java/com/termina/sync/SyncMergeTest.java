package com.termina.sync;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The three-way merge, one command at a time. */
class SyncMergeTest {

    private static final String PATH = KeybindingsFile.OTHER_PATH;

    private static String file(Map<String, String> bindings) {
        return KeybindingsFile.text(PATH, bindings);
    }

    @Test
    void twoComputersRebindingDifferentCommandsBothKeepTheirWork() {
        String base = file(Map.of("newTab", "Ctrl+Shift+Y"));
        String mine = file(Map.of("newTab", "Ctrl+Shift+Y", "copy", "Ctrl+Alt+C"));
        String theirs = file(Map.of("newTab", "Ctrl+Shift+Y", "paste", "Ctrl+Alt+V"));

        SyncMerge.FileResult r = SyncMerge.merge(PATH, base, mine, theirs);

        assertEquals(Map.of("newTab", "Ctrl+Shift+Y", "copy", "Ctrl+Alt+C", "paste", "Ctrl+Alt+V"), r.merged());
        assertEquals(List.of("paste"), r.received());
        assertEquals(List.of("copy"), r.sent());
        assertEquals(List.of(), r.conflicts());
    }

    @Test
    void aRemovalOnEitherSideIsCarriedOver() {
        // Putting a command back on its default removes its line; that is a change like any other.
        String base = file(Map.of("newTab", "Ctrl+Shift+Y", "copy", "Ctrl+Alt+C"));
        String mine = file(Map.of("copy", "Ctrl+Alt+C"));
        String theirs = file(Map.of("newTab", "Ctrl+Shift+Y"));

        SyncMerge.FileResult r = SyncMerge.merge(PATH, base, mine, theirs);

        assertEquals(Map.of(), r.merged());
        assertNull(r.text());
        assertEquals(List.of("copy"), r.received());
        assertEquals(List.of("newTab"), r.sent());
    }

    @Test
    void theSameCommandChangedOnBothSidesKeepsThisComputersChord() {
        String base = file(Map.of("newTab", "Ctrl+Shift+Y"));
        String mine = file(Map.of("newTab", "Ctrl+Alt+T"));
        String theirs = file(Map.of("newTab", "Ctrl+Shift+N"));

        SyncMerge.FileResult r = SyncMerge.merge(PATH, base, mine, theirs);

        assertEquals(Map.of("newTab", "Ctrl+Alt+T"), r.merged());
        assertEquals(List.of("newTab"), r.sent());
        assertEquals(List.of("newTab"), r.conflicts());
    }

    @Test
    void bothMakingTheSameChangeIsNoConflict() {
        String base = file(Map.of("newTab", "Ctrl+Shift+Y"));
        String both = file(Map.of("newTab", "Ctrl+Alt+T"));

        SyncMerge.FileResult r = SyncMerge.merge(PATH, base, both, both);

        assertEquals(List.of(), r.received());
        assertEquals(List.of(), r.sent());
        assertEquals(List.of(), r.conflicts());
    }

    @Test
    void theFirstSyncWithARepositoryRemovesNothing() {
        // No base: an entry only one side has looks added, never deleted by the other.
        String mine = file(Map.of("copy", "Ctrl+Alt+C"));
        String theirs = file(Map.of("paste", "Ctrl+Alt+V"));

        SyncMerge.FileResult r = SyncMerge.merge(PATH, null, mine, theirs);

        assertEquals(Map.of("copy", "Ctrl+Alt+C", "paste", "Ctrl+Alt+V"), r.merged());
    }

    @Test
    void normalizingIgnoresLayoutAndComments() {
        String handEdited = "# mine\r\nnewTab = Ctrl+Shift+Y\r\n";
        assertEquals(file(Map.of("newTab", "Ctrl+Shift+Y")), SyncMerge.normalize(PATH, handEdited));
        assertNull(SyncMerge.normalize(PATH, null));
    }
}
