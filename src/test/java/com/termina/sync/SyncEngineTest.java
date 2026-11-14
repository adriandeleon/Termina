package com.termina.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Whole syncs against a real repository: a bare one in a temporary folder, and one clone per simulated
 * computer, each with its key bindings in memory.
 */
class SyncEngineTest {

    @TempDir
    Path dir;

    private String remote;

    /** One computer: its clone, its platform, and the bindings it holds. */
    private final class Computer implements SyncTarget {
        final String name;
        final boolean mac;
        final Path clone;
        Map<String, String> bindings = new LinkedHashMap<>();
        boolean refuseWrites;
        int writes;
        /**
         * Runs once, after the live data is written and before the push: the window in which another
         * computer can push first.
         */
        Runnable duringSync;

        Computer(String name, boolean mac) {
            this.name = name;
            this.mac = mac;
            this.clone = dir.resolve(name).resolve("sync").resolve("repo");
        }

        String path() {
            return KeybindingsFile.PATH;
        }

        @Override
        public Set<String> paths() {
            return Set.of(path());
        }

        @Override
        public Map<String, String> read() {
            // As the application's own target does: in the form every system shares.
            String text = KeybindingsFile.text(path(), SharedChords.toFile(bindings, mac));
            return text == null ? Map.of() : Map.of(path(), text);
        }

        @Override
        public boolean apply(Map<String, String> expected, Map<String, String> changes) {
            if (refuseWrites || !Objects.equals(read().get(path()), expected.get(path()))) return false;
            bindings = new LinkedHashMap<>(
                    SharedChords.fromFile(KeybindingsFile.entries(changes.get(path())), bindings, mac));
            writes++;
            Runnable then = duringSync;
            duringSync = null;
            if (then != null) then.run();
            return true;
        }

        SyncEngine engine() {
            return engine(remote);
        }

        SyncEngine engine(String url) {
            return new SyncEngine(new GitRunner(List.of("git"), clone, false, ""), url, "main", this, name);
        }

        SyncReport sync() {
            return engine().run(false);
        }
    }

    @BeforeEach
    void bareRepository() throws IOException {
        GitRunner probe = new GitRunner(List.of("git"), dir, false, "");
        assumeTrue(probe.available(), "git is not installed");
        Path bare = dir.resolve("remote.git");
        Files.createDirectories(bare);
        assertTrue(probe.run("init", "--quiet", "--bare", bare.toString()).ok());
        remote = bare.toString();
    }

    /** What the remote's main branch holds at {@code path}, or null. */
    private String remoteFile(String path) {
        GitRunner git = new GitRunner(List.of("git"), dir.resolve("remote.git"), false, "");
        GitRunner.Result show = git.run("show", "main:" + path);
        return show.ok() ? show.out() : null;
    }

    @Test
    void aBindingChangedOnOneComputerArrivesOnTheOther() {
        Computer laptop = new Computer("laptop", false);
        Computer desktop = new Computer("desktop", false);
        laptop.bindings.put("file.newTab", "Ctrl+Shift+Y");

        SyncReport first = laptop.sync();
        assertEquals(SyncReport.Status.OK, first.status(), first.detail());
        assertEquals(List.of("file.newTab"), first.sent());
        assertEquals(
                "file.newTab=Ctrl+Shift+Y",
                remoteFile(KeybindingsFile.PATH)
                        .lines()
                        .filter(l -> !l.startsWith("#"))
                        .findFirst()
                        .orElseThrow());

        SyncReport second = desktop.sync();
        assertEquals(SyncReport.Status.OK, second.status(), second.detail());
        assertEquals(List.of("file.newTab"), second.received());
        assertEquals(Map.of("file.newTab", "Ctrl+Shift+Y"), desktop.bindings);

        desktop.bindings.remove("file.newTab"); // back to the default
        desktop.bindings.put("edit.copy", "Ctrl+Alt+C");
        assertEquals(SyncReport.Status.OK, desktop.sync().status());
        SyncReport back = laptop.sync();
        assertEquals(SyncReport.Status.OK, back.status(), back.detail());
        assertEquals(Map.of("edit.copy", "Ctrl+Alt+C"), laptop.bindings);
    }

    @Test
    void theRepositoryExplainsItselfAndDeclaresItsFormat() {
        Computer laptop = new Computer("laptop", false);
        laptop.bindings.put("file.newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, laptop.sync().status());

        assertTrue(remoteFile("README.md").contains("Termina"));
        assertEquals("* -text\n", remoteFile(".gitattributes"));
        assertEquals("formatVersion=" + SyncEngine.FORMAT_VERSION + "\n", remoteFile(SyncEngine.FORMAT_FILE));
    }

    /** Three computers that have all synced with the repository, every one holding {@code newTab=v0}. */
    private List<Computer> threeInStep() {
        Computer a = new Computer("a", false);
        Computer b = new Computer("b", false);
        Computer c = new Computer("c", false);
        a.bindings.put("file.newTab", "Ctrl+Alt+0");
        for (Computer each : List.of(a, b, c))
            assertEquals(SyncReport.Status.OK, each.sync().status());
        for (Computer each : List.of(a, b, c)) assertEquals(Map.of("file.newTab", "Ctrl+Alt+0"), each.bindings);
        return List.of(a, b, c);
    }

    private static String binding(String file, String id) {
        return file == null ? null : KeybindingsFile.entries(file).get(id);
    }

    @Test
    void aRetryAfterALostPushRaceKeepsTheNewerRemoteEdit() {
        List<Computer> all = threeInStep();
        Computer a = all.get(0);
        Computer b = all.get(1);
        Computer c = all.get(2);
        b.bindings.put("file.newTab", "Ctrl+Alt+1");
        assertEquals(SyncReport.Status.OK, b.sync().status());
        assertEquals(SyncReport.Status.OK, c.sync().status()); // c has v1 too

        // a takes v1 and has something of its own to push; c pushes v2 in between.
        a.bindings.put("edit.copy", "Ctrl+Alt+C");
        a.duringSync = () -> {
            c.bindings.put("file.newTab", "Ctrl+Alt+2");
            assertEquals(SyncReport.Status.OK, c.sync().status());
        };
        SyncReport r = a.sync();

        assertEquals(SyncReport.Status.OK, r.status(), r.detail());
        assertEquals(List.of(), r.conflicts(), "the user of a never touched newTab");
        assertEquals(List.of("file.newTab"), r.received(), "received once, however many rounds it took");
        assertEquals(List.of("edit.copy"), r.sent());
        assertEquals(Map.of("file.newTab", "Ctrl+Alt+2", "edit.copy", "Ctrl+Alt+C"), a.bindings);
        assertEquals("Ctrl+Alt+2", binding(remoteFile(KeybindingsFile.PATH), "file.newTab"));
        assertEquals("Ctrl+Alt+C", binding(remoteFile(KeybindingsFile.PATH), "edit.copy"));
    }

    @Test
    void aLocalEditStillWinsTheRoundAfterALostRace() {
        List<Computer> all = threeInStep();
        Computer a = all.get(0);
        Computer c = all.get(2);
        a.bindings.put("file.newTab", "Ctrl+Alt+A");
        a.bindings.put("edit.copy", "Ctrl+Alt+C");
        c.bindings.put("edit.paste", "Ctrl+Alt+V");
        assertEquals(SyncReport.Status.OK, c.sync().status()); // something for a to receive

        a.duringSync = () -> {
            c.bindings.put("file.newTab", "Ctrl+Alt+2");
            assertEquals(SyncReport.Status.OK, c.sync().status());
        };
        SyncReport r = a.sync();

        assertEquals(SyncReport.Status.OK, r.status(), r.detail());
        assertEquals(List.of("file.newTab"), r.conflicts(), "changed on both sides, and still not pushed");
        assertEquals(List.of("edit.copy", "file.newTab"), r.sent());
        assertEquals(List.of("edit.paste"), r.received());
        assertEquals(
                Map.of("file.newTab", "Ctrl+Alt+A", "edit.copy", "Ctrl+Alt+C", "edit.paste", "Ctrl+Alt+V"), a.bindings);
        assertEquals("Ctrl+Alt+A", binding(remoteFile(KeybindingsFile.PATH), "file.newTab"));
        assertEquals("Ctrl+Alt+V", binding(remoteFile(KeybindingsFile.PATH), "edit.paste"));
    }

    @Test
    void whatAFailedPushReceivedIsNotSentBackOverALaterEdit() throws IOException {
        List<Computer> all = threeInStep();
        Computer a = all.get(0);
        Computer b = all.get(1);
        b.bindings.put("file.newTab", "Ctrl+Alt+1");
        assertEquals(SyncReport.Status.OK, b.sync().status());

        // a receives v1, and its own edit cannot be pushed: the repository is gone for a while.
        Path repository = dir.resolve("remote.git");
        Path away = dir.resolve("remote-away.git");
        a.bindings.put("edit.copy", "Ctrl+Alt+C");
        a.duringSync = () -> {
            try {
                Files.move(repository, away);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        SyncReport failed = a.sync();
        Files.move(away, repository);
        assertEquals(SyncReport.Status.PUSH_FAILED, failed.status(), failed.detail());
        assertEquals(List.of("file.newTab"), failed.received());
        assertEquals("Ctrl+Alt+1", a.bindings.get("file.newTab"));

        // Later, b changes it again; a is to take that, not to push its old copy over it.
        b.bindings.put("file.newTab", "Ctrl+Alt+3");
        assertEquals(SyncReport.Status.OK, b.sync().status());
        SyncReport r = a.sync();

        assertEquals(SyncReport.Status.OK, r.status(), r.detail());
        assertEquals(List.of(), r.conflicts());
        assertEquals(List.of("file.newTab"), r.received());
        assertEquals(List.of("edit.copy"), r.sent(), "the edit that failed to go is sent now");
        assertEquals(Map.of("file.newTab", "Ctrl+Alt+3", "edit.copy", "Ctrl+Alt+C"), a.bindings);
        assertEquals("Ctrl+Alt+3", binding(remoteFile(KeybindingsFile.PATH), "file.newTab"));
        assertEquals("Ctrl+Alt+C", binding(remoteFile(KeybindingsFile.PATH), "edit.copy"));
    }

    @Test
    void aRepositoryThatIgnoresTheBindingsStillGetsThem() throws IOException {
        // A .gitignore in the repository: "add --all" staged nothing, and the run still said OK.
        Path setup = dir.resolve("setup");
        GitRunner here = new GitRunner(List.of("git"), dir, false, "");
        assertTrue(here.network("clone", "--quiet", remote, setup.toString()).ok());
        Files.writeString(setup.resolve(".gitignore"), "keybindings/\n*.properties\n");
        GitRunner inSetup = new GitRunner(List.of("git"), setup, false, "");
        assertTrue(inSetup.run("add", ".gitignore").ok());
        assertTrue(inSetup.run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "ignore")
                .ok());
        assertTrue(
                inSetup.network("push", "-q", "origin", "HEAD:refs/heads/main").ok());

        Computer a = new Computer("a", false);
        a.bindings.put("file.newTab", "Ctrl+Shift+Y");
        SyncReport r = a.sync();
        assertEquals(SyncReport.Status.OK, r.status(), r.detail());
        assertEquals("Ctrl+Shift+Y", binding(remoteFile(KeybindingsFile.PATH), "file.newTab"));
        assertEquals("formatVersion=" + SyncEngine.FORMAT_VERSION + "\n", remoteFile(SyncEngine.FORMAT_FILE));

        Computer b = new Computer("b", false);
        assertEquals(SyncReport.Status.OK, b.sync().status());
        assertEquals(Map.of("file.newTab", "Ctrl+Shift+Y"), b.bindings);
    }

    @Test
    void aGlobalExcludesFileDoesNotKeepTheBindingsOut() throws IOException {
        Path excludes = dir.resolve("global-ignore");
        Files.writeString(excludes, "keybindings/\n");
        Computer a = new Computer("a", false);
        a.bindings.put("file.newTab", "Ctrl+Shift+Y");
        GitRunner runner =
                new GitRunner(List.of("git"), a.clone, false, "").with(List.of("-c", "core.excludesFile=" + excludes));
        SyncReport r = new SyncEngine(runner, remote, "main", a, "a").run(false);
        assertEquals(SyncReport.Status.OK, r.status(), r.detail());
        assertEquals("Ctrl+Shift+Y", binding(remoteFile(KeybindingsFile.PATH), "file.newTab"));
    }

    @Test
    void anOversizedFileInTheRepositoryIsRefusedNotRead() throws IOException {
        Computer a = new Computer("a", false);
        a.bindings.put("file.newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, a.sync().status());
        Files.writeString(a.clone.resolve(KeybindingsFile.PATH), "x".repeat((int) SyncEngine.MAX_FILE_BYTES + 1));
        GitRunner inA = new GitRunner(List.of("git"), a.clone, false, "");
        assertTrue(inA.run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "big")
                .ok());
        assertTrue(inA.network("push", "-q", "origin", "HEAD:refs/heads/main").ok());

        Computer b = new Computer("b", false);
        b.bindings.put("edit.copy", "Ctrl+Alt+C");
        SyncReport r = b.sync();
        assertEquals(SyncReport.Status.FAILED, r.status());
        assertTrue(r.detail().contains(KeybindingsFile.PATH), r.detail());
        assertEquals(0, b.writes);
    }

    @Test
    void aChordChangedOnAMacIsChangedOnLinuxAndBack() {
        // OS-04 (Pauta 6.0.1): one file for every system. A Mac's Command is Ctrl+Shift in it.
        Computer mac = new Computer("mac", true);
        Computer linux = new Computer("linux", false);
        mac.bindings.put("file.newTab", "Meta+Y");

        assertEquals(SyncReport.Status.OK, mac.sync().status());
        assertTrue(remoteFile(KeybindingsFile.PATH).contains("file.newTab=Ctrl+Shift+Y"));
        SyncReport l = linux.sync();
        assertEquals(SyncReport.Status.OK, l.status(), l.detail());
        assertEquals(List.of("file.newTab"), l.received());
        assertEquals(Map.of("file.newTab", "Ctrl+Shift+Y"), linux.bindings);

        // And the other way: Linux changes two, one of them a chord that is no application chord.
        linux.bindings.put("edit.copy", "Ctrl+Shift+U");
        linux.bindings.put("view.fullScreen", "F9");
        assertEquals(SyncReport.Status.OK, linux.sync().status());
        SyncReport m = mac.sync();
        assertEquals(SyncReport.Status.OK, m.status(), m.detail());
        assertEquals(Map.of("file.newTab", "Meta+Y", "edit.copy", "Meta+U", "view.fullScreen", "F9"), mac.bindings);

        // Nothing is left to say: neither side sends or receives again.
        SyncReport quiet = linux.sync();
        assertEquals(List.of(), quiet.received());
        assertEquals(List.of(), quiet.sent());
        assertEquals(null, remoteFile(KeybindingsFile.MAC_PATH), "the file per platform is not written any more");
    }

    @Test
    void aMacKeepsItsOwnChordWhereTheFileSaysTheSameThing() {
        // Cmd+Shift+K and Cmd+K are both Ctrl+Shift+K in the file. A Mac that has the first is
        // not handed the second back by its own sync, nor by another computer's.
        Computer mac = new Computer("mac", true);
        Computer linux = new Computer("linux", false);
        mac.bindings.put("edit.clearScrollback", "Meta+Shift+K");
        assertEquals(SyncReport.Status.OK, mac.sync().status());
        linux.bindings.put("file.newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, linux.sync().status());
        assertEquals("Ctrl+Shift+K", linux.bindings.get("edit.clearScrollback"));

        assertEquals(SyncReport.Status.OK, mac.sync().status());
        assertEquals("Meta+Shift+K", mac.bindings.get("edit.clearScrollback"));
        assertEquals("Meta+Y", mac.bindings.get("file.newTab"));
    }

    @Test
    void aRepositoryOfTheOlderLayoutIsMarkedAsTheNewOne() throws Exception {
        // Version 1 had a file per platform. A computer still on it then says the repository is
        // newer than it is, where it would have gone on syncing a file nobody else reads.
        Computer a = new Computer("a", false);
        a.bindings.put("file.newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, a.sync().status());
        Files.writeString(a.clone.resolve(SyncEngine.FORMAT_FILE), "formatVersion=1\n");
        GitRunner git = new GitRunner(List.of("git"), a.clone, false, "");
        assertTrue(git.run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "as 0.14 left it")
                .ok());
        assertTrue(git.network("push", "-q", "origin", "HEAD:refs/heads/main").ok());
        assertEquals("formatVersion=1\n", remoteFile(SyncEngine.FORMAT_FILE));
        a.bindings.put("edit.copy", "Ctrl+Shift+U");
        assertEquals(SyncReport.Status.OK, a.sync().status());
        assertEquals("formatVersion=2\n", remoteFile(SyncEngine.FORMAT_FILE));
    }

    @Test
    void aConflictIsWonByTheComputerThatSyncsAndThenSettles() {
        Computer a = new Computer("a", false);
        Computer b = new Computer("b", false);
        a.bindings.put("file.newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, a.sync().status());
        assertEquals(SyncReport.Status.OK, b.sync().status());

        a.bindings.put("file.newTab", "Ctrl+Alt+A");
        b.bindings.put("file.newTab", "Ctrl+Alt+B");
        assertEquals(SyncReport.Status.OK, a.sync().status());
        SyncReport clash = b.sync();
        assertEquals(SyncReport.Status.OK, clash.status(), clash.detail());
        assertEquals(List.of("file.newTab"), clash.conflicts());
        assertEquals("Ctrl+Alt+B", b.bindings.get("file.newTab"));

        // a did not touch it again, so the repository's version is simply taken.
        assertEquals(SyncReport.Status.OK, a.sync().status());
        assertEquals("Ctrl+Alt+B", a.bindings.get("file.newTab"));
    }

    @Test
    void anEmptiedListIsNotCarriedEverywhereWithoutAsking() {
        Computer a = new Computer("a", false);
        for (int i = 0; i < SyncEngine.GUARDED_SIZE + 2; i++) a.bindings.put("command" + i, "Ctrl+Alt+F" + (i + 1));
        assertEquals(SyncReport.Status.OK, a.sync().status());
        String before = remoteFile(KeybindingsFile.PATH);

        a.bindings.clear();
        SyncReport stopped = a.sync();
        assertEquals(SyncReport.Status.NEEDS_CONFIRMATION, stopped.status());
        assertEquals(before, remoteFile(KeybindingsFile.PATH));

        SyncReport anyway = a.engine().run(true);
        assertEquals(SyncReport.Status.OK, anyway.status(), anyway.detail());
        assertEquals(null, remoteFile(KeybindingsFile.PATH));
    }

    @Test
    void aRepositoryFromANewerTerminaIsLeftAlone() throws IOException {
        Computer newer = new Computer("newer", false);
        newer.bindings.put("file.newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, newer.sync().status());
        Files.writeString(newer.clone.resolve(SyncEngine.FORMAT_FILE), "formatVersion=99\n");
        GitRunner git = new GitRunner(List.of("git"), newer.clone, false, "");
        assertTrue(git.run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "v99")
                .ok());
        assertTrue(git.network("push", "-q", "origin", "HEAD:refs/heads/main").ok());

        Computer old = new Computer("old", false);
        old.bindings.put("edit.copy", "Ctrl+Alt+C");
        assertEquals(SyncReport.Status.NEWER_FORMAT, old.sync().status());
        assertEquals(0, old.writes);
        assertFalse(remoteFile(KeybindingsFile.PATH).contains("edit.copy"));
    }

    @Test
    void aUrlWithAPasswordInItIsRefusedBeforeGitSeesIt() {
        Computer a = new Computer("a", false);
        SyncReport r = a.engine("https://me:hunter2@example.com/sync.git").run(false);
        assertEquals(SyncReport.Status.FAILED, r.status());
        assertFalse(Files.exists(a.clone), "nothing may be written for a refused URL");
    }

    @Test
    void noGitIsItsOwnFailure() {
        Computer a = new Computer("a", false);
        GitRunner missing = new GitRunner(List.of(dir.resolve("no-such-git").toString()), a.clone, false, "");
        SyncReport r = new SyncEngine(missing, remote, "main", a, "a").run(false);
        assertEquals(SyncReport.Status.NO_GIT, r.status());
    }

    @Test
    void anUnreachableRepositoryChangesNothing() {
        Computer a = new Computer("a", false);
        a.bindings.put("file.newTab", "Ctrl+Shift+Y");
        SyncReport r = a.engine(dir.resolve("not-there.git").toString()).run(false);
        assertEquals(SyncReport.Status.FETCH_FAILED, r.status());
        assertFalse(r.detail().isBlank(), "git's message is what the user can act on");
        assertEquals(Map.of("file.newTab", "Ctrl+Shift+Y"), a.bindings);
    }

    @Test
    void aCancelBeforeTheFetchWritesNothing() {
        Computer a = new Computer("a", false);
        a.bindings.put("file.newTab", "Ctrl+Shift+Y");
        SyncEngine engine = a.engine();
        assertTrue(engine.cancel());
        assertEquals(SyncReport.Status.CANCELLED, engine.run(false).status());
        assertEquals(null, remoteFile(KeybindingsFile.PATH));
    }

    @Test
    void bindingsThatChangedDuringTheSyncAreNotOverwritten() {
        Computer a = new Computer("a", false);
        Computer b = new Computer("b", false);
        a.bindings.put("file.newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, a.sync().status());

        b.refuseWrites = true; // as if the user rebound something while the fetch ran
        SyncReport r = b.sync();
        assertEquals(SyncReport.Status.LOCAL_BUSY, r.status());
        assertEquals(Map.of(), b.bindings);

        b.refuseWrites = false;
        assertEquals(SyncReport.Status.OK, b.sync().status());
        assertEquals(Map.of("file.newTab", "Ctrl+Shift+Y"), b.bindings);
    }

    @Test
    void pointingAtAnotherRepositoryStartsAfreshAndKeepsEverything() throws IOException {
        Computer a = new Computer("a", false);
        a.bindings.put("file.newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, a.sync().status());

        Path other = dir.resolve("other.git");
        Files.createDirectories(other);
        assertTrue(new GitRunner(List.of("git"), dir, false, "")
                .run("init", "--quiet", "--bare", other.toString())
                .ok());
        SyncReport r = a.engine(other.toString()).run(false);
        assertEquals(SyncReport.Status.OK, r.status(), r.detail());
        assertEquals(Map.of("file.newTab", "Ctrl+Shift+Y"), a.bindings);
        GitRunner git = new GitRunner(List.of("git"), other, false, "");
        assertTrue(git.run("show", "main:" + KeybindingsFile.PATH).out().contains("file.newTab=Ctrl+Shift+Y"));
    }

    @Test
    void urlsAndBranchesAreCheckedBeforeUse() {
        assertTrue(SyncEngine.isUsableUrl("git@github.com:me/termina-sync.git"));
        assertTrue(SyncEngine.isUsableUrl("https://github.com/me/termina-sync.git"));
        assertFalse(SyncEngine.isUsableUrl(""));
        assertFalse(SyncEngine.isUsableUrl("--upload-pack=touch /tmp/x"));
        assertFalse(SyncEngine.isUsableUrl("https://host/a\nb"));

        assertTrue(SyncEngine.isUsableBranch("main"));
        assertTrue(SyncEngine.isUsableBranch("sync/termina"));
        assertFalse(SyncEngine.isUsableBranch("-x"));
        assertFalse(SyncEngine.isUsableBranch("a..b"));
        assertFalse(SyncEngine.isUsableBranch("main.lock"));
        assertFalse(SyncEngine.isUsableBranch("/main"));
    }

    @Test
    void secretsInAUrlAreFoundAndCanBeTakenOut() {
        Map<String, String> cases = new HashMap<>();
        cases.put("https://me:TOKEN@github.com/me/s.git", "https://me@github.com/me/s.git");
        cases.put("https://ghp_abcdefABCDEF0123456789@github.com/me/s.git", "https://github.com/me/s.git");
        cases.put("https://ghp_abc:x-oauth-basic@github.com/me/s.git", "https://github.com/me/s.git");
        cases.forEach((url, clean) -> {
            assertTrue(SyncEngine.hasCredentials(url), url);
            assertEquals(clean, SyncEngine.withoutCredentials(url), url);
        });
        for (String fine : List.of(
                "git@github.com:me/s.git",
                "ssh://git@github.com/me/s.git",
                "https://me@github.com/me/s.git",
                "https://github.com/me/s.git")) {
            assertFalse(SyncEngine.hasCredentials(fine), fine);
            assertEquals(fine, SyncEngine.withoutCredentials(fine));
        }
    }
}
