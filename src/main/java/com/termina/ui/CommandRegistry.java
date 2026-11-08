package com.termina.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * Every command of a window, by id: what it does, what it is called right now, and whether it can
 * run now (MENU-01, Pauta 2.0.2). The menu bar, the palette and the key bindings all ask here, so a
 * command greyed in one is greyed in all, and named the same: "Unpin Tab" in the Window menu is
 * "Unpin Tab" in the palette, and Reopen Closed Tab is unavailable in both for the same reason.
 *
 * <p>The label and the gate are suppliers, asked each time a menu opens or the palette lists, so
 * they follow the window's state without anyone remembering to update them. Imports no JavaFX type
 * (QUAL-05): building the menu items from this is the window's part.
 */
final class CommandRegistry {

    /** One command: its action, its label now, and why it cannot run now (null when it can). */
    record Command(String id, Runnable action, Supplier<String> label, Supplier<@Nullable String> unavailable) {}

    private final Map<String, Command> byId = new LinkedHashMap<>();

    /** Adds a command; the first registration of an id stands, as a menu's actions arrive twice. */
    void register(String id, Runnable action, Supplier<String> label, Supplier<@Nullable String> unavailable) {
        byId.putIfAbsent(id, new Command(id, action, label, unavailable));
    }

    boolean contains(@Nullable String id) {
        return id != null && byId.containsKey(id);
    }

    /** Runs command {@code id}. */
    void run(String id) {
        command(id).action().run();
    }

    /** What command {@code id} is called right now. */
    String label(String id) {
        return command(id).label().get();
    }

    /** Why command {@code id} cannot run now, or null when it can. */
    @Nullable
    String unavailable(String id) {
        return command(id).unavailable().get();
    }

    /** Every id, in the order they were registered. */
    List<String> ids() {
        return new ArrayList<>(byId.keySet());
    }

    private Command command(String id) {
        Command command = byId.get(id);
        if (command == null) throw new IllegalArgumentException("no command " + id);
        return command;
    }
}
