package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javafx.geometry.Orientation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
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

    /** Deeper than any tab anyone has split; a value nested past it is not one of ours. */
    private static final int MAX_DEPTH = 32;

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

    // ---------------------------------------------------------------- the stored value

    /**
     * The value as Jackson reads and writes it (PLAT-14, Pauta 6.0.1): plain classes through the
     * config files' own {@code ObjectMapper}, never JSON built or parsed by hand.
     */
    @JsonPropertyOrder({"version", "selected", "tabs"})
    static final class Stored {
        public int version;
        public int selected = -1;
        public List<StoredTab> tabs = new ArrayList<>();
    }

    /** One tab: whether it was pinned, which of its panes was active, and its panes. */
    @JsonPropertyOrder({"pinned", "active", "panes"})
    static final class StoredTab {
        public boolean pinned;
        public int active;
        public @Nullable StoredPane panes;
    }

    /**
     * A pane, or a split of panes: a pane has the first four fields, a split the last three. A
     * field of the other kind is left out of the value.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"shell", "directory", "profile", "title", "split", "dividers", "children"})
    static final class StoredPane {
        public @Nullable String shell;
        public @Nullable String directory;
        public @Nullable String profile;
        public @Nullable String title;
        public @Nullable String split;
        public @Nullable List<Double> dividers;
        public @Nullable List<StoredPane> children;
    }

    /** The stored value for {@code session}, after {@link #kept}: JSON on one line. Pure. */
    static String write(ClosedTabs.Window session) {
        Stored stored = new Stored();
        stored.version = VERSION;
        stored.selected = session.selected();
        for (ClosedTabs.Entry tab : ClosedTabs.reopenOrder(kept(session).tabs())) {
            StoredTab out = new StoredTab();
            out.pinned = tab.pinned();
            out.active = tab.layout().activeLeaf();
            out.panes = stored(tab.layout().root());
            stored.tabs.add(out);
        }
        try {
            return com.termina.config.ConfigJson.mapper().writeValueAsString(stored);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e); // a string does not fail
        }
    }

    private static StoredPane stored(PaneLayout node) {
        StoredPane out = new StoredPane();
        switch (node) {
            case PaneLayout.Leaf leaf -> {
                out.shell = leaf.options().shell();
                out.directory = leaf.options().workingDirectory();
                out.profile = leaf.options().profileId();
                out.title = leaf.userTitle();
            }
            case PaneLayout.Split split -> {
                out.split = split.orientation().name();
                out.dividers = new ArrayList<>();
                for (double divider : split.dividers()) out.dividers.add(divider);
                out.children =
                        split.children().stream().map(SessionStore::stored).toList();
            }
        }
        return out;
    }

    /**
     * The session a stored value holds. A tab whose panes do not make a layout (a split in no
     * direction this build knows, or with nothing in it) is left out and the rest kept; text that
     * is not JSON, JSON of another shape, or of a later version, throws.
     */
    static ClosedTabs.Window read(String text) throws IOException {
        Stored stored = com.termina.config.ConfigJson.mapper().readValue(text, Stored.class);
        if (stored == null || stored.version != VERSION) {
            throw new IOException("not a session this version reads: version "
                    + (stored == null ? "none" : String.valueOf(stored.version)));
        }
        List<ClosedTabs.Entry> tabs = new ArrayList<>();
        if (stored.tabs != null) {
            for (StoredTab tab : stored.tabs) {
                PaneLayout layout = tab == null ? null : layout(tab.panes, 0);
                if (layout == null) continue;
                tabs.add(new ClosedTabs.Entry(new PaneLayout.Tab(layout, tab.active), tabs.size(), 0, tab.pinned));
            }
        }
        return new ClosedTabs.Window(tabs, stored.selected, 0);
    }

    private static @Nullable PaneLayout layout(@Nullable StoredPane node, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        String direction = node.split;
        if (direction != null) {
            Orientation orientation;
            try {
                orientation = Orientation.valueOf(direction);
            } catch (IllegalArgumentException unknown) {
                return null;
            }
            List<PaneLayout> children = new ArrayList<>();
            List<StoredPane> stored = node.children;
            if (stored != null) {
                for (StoredPane child : stored) {
                    PaneLayout read = layout(child, depth + 1);
                    if (read != null) children.add(read);
                }
            }
            // A split left with one pane is that pane; with none, nothing.
            if (children.size() < 2) return children.isEmpty() ? null : children.getFirst();
            List<Double> stops = node.dividers;
            double[] dividers = stops == null
                    ? new double[0]
                    : stops.stream()
                            .filter(java.util.Objects::nonNull)
                            .mapToDouble(stop -> Math.clamp(stop, 0.0, 1.0))
                            .toArray();
            return new PaneLayout.Split(orientation, dividers, children);
        }
        return new PaneLayout.Leaf(
                new LaunchOptions(text(node.shell), text(node.directory), List.of(), text(node.profile)),
                text(node.title));
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value;
    }
}
