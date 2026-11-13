package com.termina.ui;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javafx.geometry.Orientation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.termina.pty.LaunchOptions;
import org.jspecify.annotations.Nullable;

/**
 * The tabs that were open when Termina last ended, kept for the next launch (WIN-08, Pauta 6.0.1).
 *
 * <p>What is kept is what Reopen Closed Tab keeps for a window ({@link ClosedTabs.Window}): each
 * tab's panes as they were arranged, each pane's profile and the directory its shell was in, the
 * names the user gave, which tabs were pinned and which was in front. The shells themselves are
 * gone, so each pane comes back as a new shell in its old place. A program a tab was started to
 * run ({@code termina -e}) is not kept: that pane comes back as a shell in the same directory,
 * because starting somebody's command again, unasked, at the next launch is not restoring.
 *
 * <p>It is one value, {@code session.tabs}, in the session-state file beside the window's place
 * ({@code workspace-state.json}: SET-08 keeps what was open there, and never in the preferences),
 * so it has that file's schema version, its deferred save and its flush as the application goes.
 * State is not exported into another machine's settings or synced. Written when the last window
 * closes or the application quits; read with the settings and parsed once, off the FX thread,
 * before the first window. The value is this, on one line:
 *
 * <pre>
 * {
 *   "version": 1,
 *   "selected": 1,
 *   "tabs": [
 *     { "pinned": false, "active": 0,
 *       "panes": { "shell": "", "directory": "/home/me/src", "profile": "", "title": "" } },
 *     { "pinned": true, "active": 1,
 *       "panes": { "split": "HORIZONTAL", "dividers": [0.5], "children": [ {…}, {…} ] } }
 *   ]
 * }
 * </pre>
 */
public final class SessionStore {

    /** The shape of the value, apart from the file's own schema version: a later shape is not read. */
    static final int VERSION = 1;

    /** Deeper than any tab anyone has split; a file nested past it is not one of ours. */
    private static final int MAX_DEPTH = 32;

    private static final JsonFactory FACTORY = new JsonFactory();

    private static final ClosedTabs.Window NOTHING = new ClosedTabs.Window(List.of(), -1, 0);

    /** What {@link #warm} read, until the first window takes it. */
    private static volatile ClosedTabs.@Nullable Window warmed;

    private SessionStore() {}

    /**
     * Whether a launch reopens the last session: the setting is on, the command line did not say
     * {@code --no-session} (CLI-04), and the run is the application's own, not a capture's.
     */
    public static boolean restores(boolean setting, boolean noSession, boolean capture) {
        return setting && !noSession && !capture;
    }

    /**
     * Reads {@code stored}, the settings' {@code session.tabs}, for the first window, with every
     * directory that is no longer one left blank; off the FX thread, since that asks the disk.
     */
    public static void warm(String stored) {
        warmed = parse(stored, Files::isDirectory);
    }

    /** The session {@link #warm} read, once; empty when there was none or it was taken already. */
    static ClosedTabs.Window take() {
        ClosedTabs.Window taken = warmed;
        warmed = null;
        return taken == null ? NOTHING : taken;
    }

    /**
     * The session {@code stored} holds, each pane whose directory {@code isDirectory} refuses
     * starting where its profile does. Empty for no text, or text that cannot be read: a damaged
     * session costs the tabs, never the launch.
     */
    static ClosedTabs.Window parse(String stored, Predicate<Path> isDirectory) {
        if (stored == null || stored.isBlank()) return NOTHING;
        try {
            return inDirectoriesThatExist(read(stored), isDirectory);
        } catch (IOException | RuntimeException unreadable) {
            DebugLog.append("session: the stored tabs could not be read: " + unreadable);
            return NOTHING;
        }
    }

    // ---------------------------------------------------------------- what is kept

    /** {@code session} as it is stored: every pane a shell, whatever it was started to run. Pure. */
    static ClosedTabs.Window kept(ClosedTabs.Window session) {
        return mapLeaves(
                session,
                leaf -> new PaneLayout.Leaf(
                        new LaunchOptions(
                                leaf.options().shell(),
                                leaf.options().workingDirectory(),
                                List.of(),
                                leaf.options().profileId()),
                        leaf.userTitle()));
    }

    /** {@code session} with each directory {@code isDirectory} refuses left blank. Pure. */
    static ClosedTabs.Window inDirectoriesThatExist(ClosedTabs.Window session, Predicate<Path> isDirectory) {
        return mapLeaves(session, leaf -> {
            String directory = leaf.options().workingDirectory();
            boolean there;
            try {
                there = directory.isBlank() || isDirectory.test(Path.of(directory));
            } catch (java.nio.file.InvalidPathException notAPath) {
                there = false;
            }
            return there ? leaf : new PaneLayout.Leaf(leaf.options().withWorkingDirectory(""), leaf.userTitle());
        });
    }

    private static ClosedTabs.Window mapLeaves(
            ClosedTabs.Window session, java.util.function.UnaryOperator<PaneLayout.Leaf> change) {
        List<ClosedTabs.Entry> tabs = new ArrayList<>();
        for (ClosedTabs.Entry tab : session.tabs()) {
            PaneLayout.Tab layout = new PaneLayout.Tab(
                    mapLeaves(tab.layout().root(), change), tab.layout().activeLeaf());
            tabs.add(new ClosedTabs.Entry(layout, tab.index(), tab.stamp(), tab.pinned()));
        }
        return new ClosedTabs.Window(tabs, session.selected(), session.stamp());
    }

    private static PaneLayout mapLeaves(PaneLayout node, java.util.function.UnaryOperator<PaneLayout.Leaf> change) {
        return switch (node) {
            case PaneLayout.Leaf leaf -> change.apply(leaf);
            case PaneLayout.Split split ->
                new PaneLayout.Split(
                        split.orientation(),
                        split.dividers(),
                        split.children().stream()
                                .map(child -> mapLeaves(child, change))
                                .toList());
        };
    }

    // ---------------------------------------------------------------- the file's text

    /** The stored value for {@code session}, after {@link #kept}: JSON on one line. Pure. */
    static String write(ClosedTabs.Window session) {
        StringWriter out = new StringWriter();
        try (JsonGenerator json = FACTORY.createGenerator(out)) {
            json.writeStartObject();
            json.writeNumberField("version", VERSION);
            json.writeNumberField("selected", session.selected());
            json.writeArrayFieldStart("tabs");
            for (ClosedTabs.Entry tab : ClosedTabs.reopenOrder(kept(session).tabs())) {
                json.writeStartObject();
                json.writeBooleanField("pinned", tab.pinned());
                json.writeNumberField("active", tab.layout().activeLeaf());
                json.writeFieldName("panes");
                writeLayout(json, tab.layout().root());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e); // a StringWriter does not fail
        }
        return out.toString();
    }

    private static void writeLayout(JsonGenerator json, PaneLayout node) throws IOException {
        json.writeStartObject();
        switch (node) {
            case PaneLayout.Leaf leaf -> {
                json.writeStringField("shell", leaf.options().shell());
                json.writeStringField("directory", leaf.options().workingDirectory());
                json.writeStringField("profile", leaf.options().profileId());
                json.writeStringField("title", leaf.userTitle());
            }
            case PaneLayout.Split split -> {
                json.writeStringField("split", split.orientation().name());
                json.writeArrayFieldStart("dividers");
                for (double divider : split.dividers()) json.writeNumber(divider);
                json.writeEndArray();
                json.writeArrayFieldStart("children");
                for (PaneLayout child : split.children()) writeLayout(json, child);
                json.writeEndArray();
            }
        }
        json.writeEndObject();
    }

    /**
     * The session a stored value holds. A tab or a pane that does not read as one is left out and
     * the rest kept; text that is not JSON, or JSON of another shape or a later version, throws.
     */
    static ClosedTabs.Window read(String text) throws IOException {
        Object parsed;
        try (JsonParser json = FACTORY.createParser(text)) {
            json.nextToken();
            parsed = value(json, 0);
        }
        if (!(parsed instanceof Map<?, ?> root)) throw new IOException("not a session: no object");
        if (!(root.get("version") instanceof Number version) || version.intValue() != VERSION) {
            throw new IOException("not a session this version reads: version " + root.get("version"));
        }
        List<ClosedTabs.Entry> tabs = new ArrayList<>();
        if (root.get("tabs") instanceof List<?> stored) {
            for (Object each : stored) {
                if (!(each instanceof Map<?, ?> tab)) continue;
                PaneLayout layout = layout(tab.get("panes"), 0);
                if (layout == null) continue;
                int active = tab.get("active") instanceof Number n ? n.intValue() : 0;
                tabs.add(new ClosedTabs.Entry(
                        new PaneLayout.Tab(layout, active), tabs.size(), 0, Boolean.TRUE.equals(tab.get("pinned"))));
            }
        }
        int selected = root.get("selected") instanceof Number n ? n.intValue() : -1;
        return new ClosedTabs.Window(tabs, selected, 0);
    }

    private static @Nullable PaneLayout layout(@Nullable Object stored, int depth) {
        if (!(stored instanceof Map<?, ?> node) || depth > MAX_DEPTH) return null;
        if (node.get("split") instanceof String direction) {
            Orientation orientation;
            try {
                orientation = Orientation.valueOf(direction);
            } catch (IllegalArgumentException unknown) {
                return null;
            }
            List<PaneLayout> children = new ArrayList<>();
            if (node.get("children") instanceof List<?> list) {
                for (Object child : list) {
                    PaneLayout read = layout(child, depth + 1);
                    if (read != null) children.add(read);
                }
            }
            // A split left with one pane is that pane; with none, nothing.
            if (children.size() < 2) return children.isEmpty() ? null : children.getFirst();
            List<Double> dividers = new ArrayList<>();
            if (node.get("dividers") instanceof List<?> list) {
                for (Object divider : list) {
                    if (divider instanceof Number n) dividers.add(Math.clamp(n.doubleValue(), 0.0, 1.0));
                }
            }
            return new PaneLayout.Split(
                    orientation,
                    dividers.stream().mapToDouble(Double::doubleValue).toArray(),
                    children);
        }
        return new PaneLayout.Leaf(
                new LaunchOptions(
                        text(node.get("shell")), text(node.get("directory")), List.of(), text(node.get("profile"))),
                text(node.get("title")));
    }

    private static String text(@Nullable Object value) {
        return value instanceof String s ? s : "";
    }

    /** The value the parser is on, as maps, lists, strings, numbers and booleans; null for null. */
    private static @Nullable Object value(JsonParser json, int depth) throws IOException {
        if (depth > MAX_DEPTH * 3) throw new IOException("nested too deep");
        JsonToken token = json.currentToken();
        if (token == null) throw new IOException("empty");
        return switch (token) {
            case START_OBJECT -> {
                Map<String, @Nullable Object> map = new LinkedHashMap<>();
                while (json.nextToken() != JsonToken.END_OBJECT) {
                    String name = json.currentName();
                    json.nextToken();
                    map.put(name, value(json, depth + 1));
                }
                yield map;
            }
            case START_ARRAY -> {
                List<@Nullable Object> list = new ArrayList<>();
                while (json.nextToken() != JsonToken.END_ARRAY) list.add(value(json, depth + 1));
                yield list;
            }
            case VALUE_STRING -> json.getText();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> json.getDoubleValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new IOException("unexpected " + token);
        };
    }
}
