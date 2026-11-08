package com.termina.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One place a command is named, gated and run, by id (MENU-01, Pauta 2.0.2). */
class CommandRegistryTest {

    @Test
    void aCommandIsRunNamedAndGatedByItsIdAsThingsStandNow() {
        CommandRegistry registry = new CommandRegistry();
        boolean[] pinned = {false};
        int[] ran = {0};
        registry.register(
                "pinTab",
                () -> {
                    ran[0]++;
                    pinned[0] = !pinned[0];
                },
                () -> pinned[0] ? "Unpin Tab" : "Pin Tab",
                () -> null);
        registry.register("reopenClosedTab", () -> ran[0]++, () -> "Reopen Closed Tab", () -> "nothing closed yet");

        assertEquals("Pin Tab", registry.label("pinTab"));
        registry.run("pinTab");
        assertEquals("Unpin Tab", registry.label("pinTab"), "the label follows the state, asked each time");
        assertNull(registry.unavailable("pinTab"));
        assertEquals("nothing closed yet", registry.unavailable("reopenClosedTab"));
        assertEquals(1, ran[0]);
        assertEquals(List.of("pinTab", "reopenClosedTab"), registry.ids());
    }

    @Test
    void theFirstRegistrationStandsAndAnUnknownIdIsAnError() {
        CommandRegistry registry = new CommandRegistry();
        registry.register("newTab", () -> {}, () -> "first", () -> null);
        registry.register("newTab", () -> {}, () -> "second", () -> null);
        assertEquals("first", registry.label("newTab"));
        assertTrue(registry.contains("newTab"));
        assertFalse(registry.contains("noSuchCommand"));
        assertFalse(registry.contains(null));
        assertThrows(IllegalArgumentException.class, () -> registry.run("noSuchCommand"));
    }
}
