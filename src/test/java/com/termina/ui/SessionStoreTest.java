package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.geometry.Orientation;

import com.termina.pty.LaunchOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WIN-08 (Pauta 6.0.1): the tabs open when Termina ends are written down and read back at the
 * next launch, panes, names and directories included, and CLI-04's {@code --no-session} skips it.
 */
class SessionStoreTest {

    @TempDir
    Path dir;

    private static PaneLayout.Leaf leaf(String directory, String profile, String title, String... command) {
        return new PaneLayout.Leaf(new LaunchOptions("/bin/zsh", directory, List.of(command), profile), title);
    }

    /** Two tabs: a plain one, and a pinned one split in two with its second pane active. */
    private static ClosedTabs.Window twoTabs() {
        PaneLayout split = new PaneLayout.Split(
                Orientation.HORIZONTAL,
                new double[] {0.3},
                List.of(leaf("/srv/one", "work", ""), leaf("/srv/two", "", "logs", "tail", "-f", "x.log")));
        return new ClosedTabs.Window(
                List.of(
                        new ClosedTabs.Entry(new PaneLayout.Tab(leaf("/home/me", "", "build"), 0), 0, 7, false),
                        new ClosedTabs.Entry(new PaneLayout.Tab(split, 1), 1, 7, true)),
                1,
                7);
    }

    @Test
    void aSessionComesBackAsItWasWritten() throws IOException {
        ClosedTabs.Window back = SessionStore.read(SessionStore.write(twoTabs()));

        assertEquals(2, back.tabs().size());
        assertEquals(1, back.selected());
        ClosedTabs.Entry first = back.tabs().get(0);
        assertFalse(first.pinned());
        PaneLayout.Leaf plain =
                assertInstanceOf(PaneLayout.Leaf.class, first.layout().root());
        assertEquals("/home/me", plain.options().workingDirectory());
        assertEquals("/bin/zsh", plain.options().shell());
        assertEquals("build", plain.userTitle());

        ClosedTabs.Entry second = back.tabs().get(1);
        assertTrue(second.pinned());
        assertEquals(1, second.index());
        assertEquals(1, second.layout().activeLeaf());
        PaneLayout.Split split =
                assertInstanceOf(PaneLayout.Split.class, second.layout().root());
        assertEquals(Orientation.HORIZONTAL, split.orientation());
        assertArrayEquals(new double[] {0.3}, split.dividers());
        List<PaneLayout.Leaf> panes = second.layout().root().leaves();
        assertEquals("work", panes.get(0).options().profileId());
        assertEquals("/srv/two", panes.get(1).options().workingDirectory());
        assertEquals("logs", panes.get(1).userTitle());
    }

    @Test
    void aProgramATabWasStartedToRunIsNotStartedAgain() throws IOException {
        // The pane comes back as a shell in the same place: nobody asked for tail at this launch.
        String text = SessionStore.write(twoTabs());
        assertFalse(text.contains("tail"), text);
        PaneLayout.Leaf was =
                SessionStore.read(text).tabs().get(1).layout().root().leaves().get(1);
        assertFalse(was.options().hasCommand());
        assertEquals("/srv/two", was.options().workingDirectory());
    }

    @Test
    void aDirectoryThatIsGoneIsLeftBlankAndTheRestKept() {
        ClosedTabs.Window checked =
                SessionStore.inDirectoriesThatExist(twoTabs(), path -> path.equals(Path.of("/srv/one")));
        assertEquals(
                "",
                checked.tabs().get(0).layout().root().leaves().get(0).options().workingDirectory());
        List<PaneLayout.Leaf> panes = checked.tabs().get(1).layout().root().leaves();
        assertEquals("/srv/one", panes.get(0).options().workingDirectory());
        assertEquals("", panes.get(1).options().workingDirectory());
        assertEquals("logs", panes.get(1).userTitle(), "only the directory is dropped");
        assertTrue(checked.tabs().get(1).pinned());
    }

    @Test
    void whatIsNotASessionIsRefusedAndABadTabIsLeftOut() throws IOException {
        assertThrows(IOException.class, () -> SessionStore.read("not json"));
        assertThrows(IOException.class, () -> SessionStore.read("[1, 2]"));
        assertThrows(IOException.class, () -> SessionStore.read("{\"version\": 99, \"tabs\": []}"));
        assertThrows(IOException.class, () -> SessionStore.read("{\"tabs\": []}"));
        assertThrows(IOException.class, () -> SessionStore.read("{\"version\": 1, \"tabs\": ["));

        ClosedTabs.Window some = SessionStore.read("""
                {"version": 1, "selected": 5, "tabs": [
                  "a string",
                  {"pinned": true},
                  {"panes": {"split": "DIAGONAL", "children": []}},
                  {"panes": {"split": "VERTICAL", "dividers": [7, "x"], "children": [{"directory": "/a"}]}},
                  {"active": 9, "panes": {"directory": "/b", "title": 3}}
                ]}
                """);
        // The split with one pane left is that pane; the active pane out of range is the first.
        assertEquals(2, some.tabs().size());
        assertEquals(
                "/a",
                some.tabs().get(0).layout().root().leaves().get(0).options().workingDirectory());
        assertEquals(0, some.tabs().get(1).layout().activeLeaf());
        assertEquals("", some.tabs().get(1).layout().root().leaves().get(0).userTitle());
        assertEquals(1, some.tabs().get(1).index(), "each tab's place is its place among those kept");
    }

    @Test
    void theSessionIsOneLineInTheStateFileAndNeverInThePreferences() throws IOException {
        // SET-08: what was open is session state, beside the window's place.
        com.termina.config.Settings settings = new com.termina.config.Settings(dir.resolve("settings.json"));
        settings.load();
        assertEquals("", settings.sessionTabs());
        String stored = SessionStore.write(twoTabs());
        assertFalse(stored.contains("\n"), "one line, so it is one value");
        settings.setSessionTabs(stored);
        settings.setRestoreSession(false);
        settings.flush();

        String preferences = Files.readString(dir.resolve(com.termina.config.Settings.SETTINGS_FILE));
        String state = Files.readString(dir.resolve(com.termina.config.Settings.STATE_FILE));
        assertFalse(preferences.contains("session.tabs"), preferences);
        assertTrue(preferences.contains("session.restore"), "the switch is a preference");
        assertTrue(state.contains("session.tabs") && state.contains("/srv/one"), state);
        assertFalse(state.contains("session.restore"), state);

        com.termina.config.Settings reread = new com.termina.config.Settings(dir.resolve("settings.json"));
        reread.load();
        assertEquals(
                2, SessionStore.parse(reread.sessionTabs(), path -> true).tabs().size());
    }

    @Test
    void aDamagedSessionCostsTheTabsAndNeverTheLaunch() {
        assertTrue(SessionStore.parse("", path -> true).tabs().isEmpty(), "nothing stored is no session");
        assertTrue(SessionStore.parse("{ half a value", path -> true).tabs().isEmpty());
        assertTrue(SessionStore.parse("{\"version\": 7}", path -> true).tabs().isEmpty(), "a later shape");
    }

    @Test
    void whatWasReadForALaunchIsTakenOnce() {
        SessionStore.warm(SessionStore.write(twoTabs()));
        ClosedTabs.Window taken = SessionStore.take();
        assertEquals(2, taken.tabs().size());
        // /home/me, /srv/one and /srv/two are not directories here, so each starts where its profile does.
        for (ClosedTabs.Entry tab : taken.tabs()) {
            for (PaneLayout.Leaf pane : tab.layout().root().leaves()) {
                assertTrue(pane.options().workingDirectory().isEmpty()
                        || Files.isDirectory(Path.of(pane.options().workingDirectory())));
            }
        }
        assertTrue(SessionStore.take().tabs().isEmpty(), "a second window does not get it again");
    }

    @Test
    void aLaunchReopensTheSessionUnlessToldNotTo() {
        assertTrue(SessionStore.restores(true, false, false));
        assertFalse(SessionStore.restores(false, false, false), "the setting is off");
        assertFalse(SessionStore.restores(true, true, false), "--no-session");
        assertFalse(SessionStore.restores(true, false, true), "a capture run starts clean");
    }

    @Test
    void aCommandLineAsksForATabOfItsOwnOnlyWhenItNamesSomething() {
        assertFalse(WindowManager.asksForATab(null, ""));
        assertFalse(WindowManager.asksForATab(LaunchOptions.ofShell("/bin/zsh"), ""));
        assertTrue(WindowManager.asksForATab(LaunchOptions.ofShell("").withWorkingDirectory("/srv"), ""));
        assertTrue(WindowManager.asksForATab(new LaunchOptions("", "", List.of("htop")), ""));
        assertTrue(WindowManager.asksForATab(null, "logs"));
    }
}
