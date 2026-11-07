package com.termina.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.input.KeyCombination;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.jspecify.annotations.Nullable;

/**
 * Termina's default key bindings, as data (KBD-09, Pauta 2.0.2): {@code keymaps/keymap.json} for
 * Windows and Linux and {@code keymaps/keymap.mac.json} for macOS, command id to chord. The code
 * names commands and what they do; which key runs one is here, in one place per platform. The
 * user's own bindings ({@code keys.*}) are applied on top by {@link Keybindings#resolve}.
 *
 * <p>One keymap, not Pauta's Emacs and CUA pair: in a terminal both sets of chords belong to the
 * shell (readline's default mode is Emacs; Ctrl+C, V and W are SIGINT, literal-next and delete-word),
 * which is decision record 0003.
 */
final class Keymap {

    private static final String RESOURCE = "/com/termina/keymaps/";

    private static @Nullable Map<String, KeyCombination> defaults;

    private Keymap() {}

    /** The default chord for command {@code id} on this platform, or null when it has none. */
    static synchronized @Nullable KeyCombination defaultFor(@Nullable String id) {
        if (id == null) return null;
        Map<String, KeyCombination> map = defaults;
        if (map == null) defaults = map = load(Keybindings.MAC);
        return map.get(id);
    }

    /** One platform's keymap; a missing or broken resource is a build fault, so it throws. */
    static Map<String, KeyCombination> load(boolean mac) {
        String name = mac ? "keymap.mac.json" : "keymap.json";
        try (InputStream in = Keymap.class.getResourceAsStream(RESOURCE + name)) {
            if (in == null) throw new IllegalStateException("missing keymap " + name);
            Map<String, KeyCombination> out = new LinkedHashMap<>();
            parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .forEach((id, chord) -> out.put(id, KeyCombination.valueOf(chord)));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("unreadable keymap " + name, e);
        }
    }

    /** The {@code bindings} object of a keymap file, id to chord text, in file order. */
    static Map<String, String> parse(String json) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (JsonParser parser = new JsonFactory().createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("not a JSON object");
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("bindings".equals(field) && value == JsonToken.START_OBJECT) {
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        String id = parser.currentName();
                        parser.nextToken();
                        out.put(id, parser.getText());
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
        return out;
    }
}
