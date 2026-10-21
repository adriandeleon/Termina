package com.termina.ui;

import java.util.List;

import com.termina.ui.ShortcutReference.Shortcut;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Which shortcut rows a query keeps, and how they group. */
class ShortcutReferenceTest {

    private static final List<Shortcut> ALL = List.of(
            new Shortcut("File", "New Tab", "Ctrl+Shift+T"),
            new Shortcut("Edit", "Paste", "Ctrl+Shift+V"),
            new Shortcut("File", "New Tab With Profile 1", "Ctrl+Shift+1"),
            new Shortcut("Terminal", "Paste", "Shift+Insert"));

    @Test
    void aBlankQueryKeepsEverything() {
        assertSame(ALL, ShortcutReference.matching(ALL, ""));
        assertSame(ALL, ShortcutReference.matching(ALL, null));
    }

    @Test
    void aQueryMatchesTheLabelTheKeysOrTheGroup() {
        assertEquals(2, ShortcutReference.matching(ALL, "paste").size());
        assertEquals(1, ShortcutReference.matching(ALL, "insert").size());
        assertEquals(1, ShortcutReference.matching(ALL, "EDIT").size());
        assertEquals(0, ShortcutReference.matching(ALL, "split").size());
    }

    @Test
    void groupsKeepFirstAppearanceOrderAndRowsKeepTheirs() {
        var groups = ShortcutReference.grouped(ALL);
        assertEquals(List.of("File", "Edit", "Terminal"), List.copyOf(groups.keySet()));
        // A row that arrives later still joins its group rather than starting a second one.
        assertEquals(
                List.of("New Tab", "New Tab With Profile 1"),
                groups.get("File").stream().map(Shortcut::label).toList());
    }
}
