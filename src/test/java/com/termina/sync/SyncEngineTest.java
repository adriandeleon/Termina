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

        Computer(String name, boolean mac) {
            this.name = name;
            this.mac = mac;
            this.clone = dir.resolve(name).resolve("sync").resolve("repo");
        }

        String path() {
            return KeybindingsFile.pathFor(mac);
        }

        @Override
        public Set<String> paths() {
            return Set.of(path());
        }

        @Override
        public Map<String, String> read() {
            String text = KeybindingsFile.text(path(), bindings);
            return text == null ? Map.of() : Map.of(path(), text);
        }

        @Override
        public boolean apply(Map<String, String> expected, Map<String, String> changes) {
            if (refuseWrites || !Objects.equals(read().get(path()), expected.get(path()))) return false;
            bindings = new LinkedHashMap<>(KeybindingsFile.entries(changes.get(path())));
            writes++;
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
        laptop.bindings.put("newTab", "Ctrl+Shift+Y");

        SyncReport first = laptop.sync();
        assertEquals(SyncReport.Status.OK, first.status(), first.detail());
        assertEquals(List.of("newTab"), first.sent());
        assertEquals(
                "newTab=Ctrl+Shift+Y",
                remoteFile(KeybindingsFile.OTHER_PATH)
                        .lines()
                        .filter(l -> !l.startsWith("#"))
                        .findFirst()
                        .orElseThrow());

        SyncReport second = desktop.sync();
        assertEquals(SyncReport.Status.OK, second.status(), second.detail());
        assertEquals(List.of("newTab"), second.received());
        assertEquals(Map.of("newTab", "Ctrl+Shift+Y"), desktop.bindings);

        desktop.bindings.remove("newTab"); // back to the default
        desktop.bindings.put("copy", "Ctrl+Alt+C");
        assertEquals(SyncReport.Status.OK, desktop.sync().status());
        SyncReport back = laptop.sync();
        assertEquals(SyncReport.Status.OK, back.status(), back.detail());
        assertEquals(Map.of("copy", "Ctrl+Alt+C"), laptop.bindings);
    }

    @Test
    void theRepositoryExplainsItselfAndDeclaresItsFormat() {
        Computer laptop = new Computer("laptop", false);
        laptop.bindings.put("newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, laptop.sync().status());

        assertTrue(remoteFile("README.md").contains("Termina"));
        assertEquals("* -text\n", remoteFile(".gitattributes"));
        assertEquals("formatVersion=" + SyncEngine.FORMAT_VERSION + "\n", remoteFile(SyncEngine.FORMAT_FILE));
    }

    @Test
    void aMacAndALinuxMachineNeverTouchEachOthersBindings() {
        Computer mac = new Computer("mac", true);
        Computer linux = new Computer("linux", false);
        mac.bindings.put("newTab", "Shortcut+Shift+Y");
        linux.bindings.put("newTab", "Ctrl+Alt+T");

        assertEquals(SyncReport.Status.OK, mac.sync().status());
        SyncReport l = linux.sync();
        assertEquals(SyncReport.Status.OK, l.status(), l.detail());
        assertEquals(List.of(), l.received());
        assertEquals(Map.of("newTab", "Ctrl+Alt+T"), linux.bindings);
        assertEquals(SyncReport.Status.OK, mac.sync().status());
        assertEquals(Map.of("newTab", "Shortcut+Shift+Y"), mac.bindings);

        assertTrue(remoteFile(KeybindingsFile.MAC_PATH).contains("newTab=Shortcut+Shift+Y"));
        assertTrue(remoteFile(KeybindingsFile.OTHER_PATH).contains("newTab=Ctrl+Alt+T"));
    }

    @Test
    void aConflictIsWonByTheComputerThatSyncsAndThenSettles() {
        Computer a = new Computer("a", false);
        Computer b = new Computer("b", false);
        a.bindings.put("newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, a.sync().status());
        assertEquals(SyncReport.Status.OK, b.sync().status());

        a.bindings.put("newTab", "Ctrl+Alt+A");
        b.bindings.put("newTab", "Ctrl+Alt+B");
        assertEquals(SyncReport.Status.OK, a.sync().status());
        SyncReport clash = b.sync();
        assertEquals(SyncReport.Status.OK, clash.status(), clash.detail());
        assertEquals(List.of("newTab"), clash.conflicts());
        assertEquals("Ctrl+Alt+B", b.bindings.get("newTab"));

        // a did not touch it again, so the repository's version is simply taken.
        assertEquals(SyncReport.Status.OK, a.sync().status());
        assertEquals("Ctrl+Alt+B", a.bindings.get("newTab"));
    }

    @Test
    void anEmptiedListIsNotCarriedEverywhereWithoutAsking() {
        Computer a = new Computer("a", false);
        for (int i = 0; i < SyncEngine.GUARDED_SIZE + 2; i++) a.bindings.put("command" + i, "Ctrl+Alt+F" + (i + 1));
        assertEquals(SyncReport.Status.OK, a.sync().status());
        String before = remoteFile(KeybindingsFile.OTHER_PATH);

        a.bindings.clear();
        SyncReport stopped = a.sync();
        assertEquals(SyncReport.Status.NEEDS_CONFIRMATION, stopped.status());
        assertEquals(before, remoteFile(KeybindingsFile.OTHER_PATH));

        SyncReport anyway = a.engine().run(true);
        assertEquals(SyncReport.Status.OK, anyway.status(), anyway.detail());
        assertEquals(null, remoteFile(KeybindingsFile.OTHER_PATH));
    }

    @Test
    void aRepositoryFromANewerTerminaIsLeftAlone() throws IOException {
        Computer newer = new Computer("newer", false);
        newer.bindings.put("newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, newer.sync().status());
        Files.writeString(newer.clone.resolve(SyncEngine.FORMAT_FILE), "formatVersion=99\n");
        GitRunner git = new GitRunner(List.of("git"), newer.clone, false, "");
        assertTrue(git.run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "v99")
                .ok());
        assertTrue(git.network("push", "-q", "origin", "HEAD:refs/heads/main").ok());

        Computer old = new Computer("old", false);
        old.bindings.put("copy", "Ctrl+Alt+C");
        assertEquals(SyncReport.Status.NEWER_FORMAT, old.sync().status());
        assertEquals(0, old.writes);
        assertFalse(remoteFile(KeybindingsFile.OTHER_PATH).contains("copy"));
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
        a.bindings.put("newTab", "Ctrl+Shift+Y");
        SyncReport r = a.engine(dir.resolve("not-there.git").toString()).run(false);
        assertEquals(SyncReport.Status.FETCH_FAILED, r.status());
        assertFalse(r.detail().isBlank(), "git's message is what the user can act on");
        assertEquals(Map.of("newTab", "Ctrl+Shift+Y"), a.bindings);
    }

    @Test
    void aCancelBeforeTheFetchWritesNothing() {
        Computer a = new Computer("a", false);
        a.bindings.put("newTab", "Ctrl+Shift+Y");
        SyncEngine engine = a.engine();
        assertTrue(engine.cancel());
        assertEquals(SyncReport.Status.CANCELLED, engine.run(false).status());
        assertEquals(null, remoteFile(KeybindingsFile.OTHER_PATH));
    }

    @Test
    void bindingsThatChangedDuringTheSyncAreNotOverwritten() {
        Computer a = new Computer("a", false);
        Computer b = new Computer("b", false);
        a.bindings.put("newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, a.sync().status());

        b.refuseWrites = true; // as if the user rebound something while the fetch ran
        SyncReport r = b.sync();
        assertEquals(SyncReport.Status.LOCAL_BUSY, r.status());
        assertEquals(Map.of(), b.bindings);

        b.refuseWrites = false;
        assertEquals(SyncReport.Status.OK, b.sync().status());
        assertEquals(Map.of("newTab", "Ctrl+Shift+Y"), b.bindings);
    }

    @Test
    void pointingAtAnotherRepositoryStartsAfreshAndKeepsEverything() throws IOException {
        Computer a = new Computer("a", false);
        a.bindings.put("newTab", "Ctrl+Shift+Y");
        assertEquals(SyncReport.Status.OK, a.sync().status());

        Path other = dir.resolve("other.git");
        Files.createDirectories(other);
        assertTrue(new GitRunner(List.of("git"), dir, false, "")
                .run("init", "--quiet", "--bare", other.toString())
                .ok());
        SyncReport r = a.engine(other.toString()).run(false);
        assertEquals(SyncReport.Status.OK, r.status(), r.detail());
        assertEquals(Map.of("newTab", "Ctrl+Shift+Y"), a.bindings);
        GitRunner git = new GitRunner(List.of("git"), other, false, "");
        assertTrue(git.run("show", "main:" + KeybindingsFile.OTHER_PATH).out().contains("newTab=Ctrl+Shift+Y"));
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
