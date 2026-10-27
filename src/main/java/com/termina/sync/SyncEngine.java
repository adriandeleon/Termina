package com.termina.sync;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * One settings sync between this computer and the user's repository. Blocking and free of JavaFX; the
 * caller picks the thread.
 *
 * <p>Git only carries the data. Termina keeps a clone of its own ({@code sync/repo} beside
 * {@code settings.properties}) that nothing else reads, and never asks git to merge: the working tree is
 * set to what the remote has, the merged files ({@link SyncMerge}) are written over it, and that is
 * committed and pushed. So there are no conflict markers and no half-finished rebase to recover from.
 *
 * <p>The three sides of the merge are the live data (mine), the remote branch (theirs) and
 * {@value #BASE_REF} — what this computer and the repository last agreed on (base). After a push it is
 * the commit pushed. When the live data was written but the push did not go through (it lost a race and
 * the round is repeated, or it failed), the base moves only as far as the agreement does: to the
 * repository's entry for everything this computer took or already had, and nowhere for what it has still
 * to send ({@link SyncMerge.FileResult#settled}). So a sync interrupted anywhere is simply repeated by
 * the next one — this computer's edits still differ from the base and are sent again — while what it
 * received is no longer mistaken for an edit of its own, which would send it back over a newer one.
 *
 * <p>A port of Editora's settings sync, which has run this design for longer; the two repositories are
 * separate and do not share files.
 */
public final class SyncEngine {

    /** The repository layout this build reads and writes ({@code formatVersion} in {@value #FORMAT_FILE}). */
    public static final int FORMAT_VERSION = 1;

    static final String FORMAT_FILE = "termina-sync.properties";
    static final String BASE_REF = "refs/termina/base";

    /** A file at least this large is guarded against losing more than half of its entries in one sync. */
    static final int GUARDED_SIZE = 10;

    private static final int ATTEMPTS = 3;

    /**
     * A synced file larger than this in the repository is refused rather than read. A key-binding file
     * is a few kilobytes; this one is someone else's, or damage, and would be read whole into memory.
     */
    static final long MAX_FILE_BYTES = 1024 * 1024;

    private final GitRunner git;
    private final Path clone;
    private final String url;
    private final String branch;
    private final SyncTarget target;
    private final String machine;

    private boolean cancelled;
    /** Past the point where this computer's data is written: a cancel no longer stops the run. */
    private boolean committed;

    private GitRunner.@Nullable Cancellation inFlight;

    /**
     * @param git a runner whose directory is the clone
     * @param machine this computer's name, for the commit message
     */
    public SyncEngine(GitRunner git, String url, String branch, SyncTarget target, String machine) {
        this.git = git;
        this.clone = git.dir();
        this.url = url == null ? "" : url.strip();
        this.branch = branch == null || branch.isBlank() ? "main" : branch.strip();
        this.target = target;
        this.machine = machine == null || machine.isBlank() ? "this computer" : machine.strip();
    }

    /** Whether {@code url} can be handed to git as a remote: not blank, one line, and not an option. */
    public static boolean isUsableUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String u = url.strip();
        return !u.startsWith("-") && u.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }

    /** Whether {@code branch} is a plain branch name. */
    public static boolean isUsableBranch(String branch) {
        return branch != null
                && branch.matches("[A-Za-z0-9._/-]+")
                && !branch.startsWith("-")
                && !branch.contains("..")
                && !branch.startsWith("/")
                && !branch.endsWith("/")
                && !branch.endsWith(".lock");
    }

    private static final Pattern USER_INFO = Pattern.compile("^(\\s*([A-Za-z][A-Za-z0-9+.-]*)://)([^/@?#]*)@");

    /**
     * Whether {@code url} carries a secret: a password after the user name ({@code https://me:TOKEN@host/…})
     * or a user name that is itself an access token ({@code https://ghp_…@github.com/…}). Sync refuses such
     * a URL: it would sit in plain text in {@code settings.properties} and in the clone's
     * {@code .git/config}. An SSH user ({@code git@host:…}, {@code ssh://git@host/…}) is never a secret.
     */
    public static boolean hasCredentials(String url) {
        if (url == null) return false;
        Matcher m = USER_INFO.matcher(url);
        if (!m.find()) return false; // no scheme (scp-like git@host:path) or no user-info
        String scheme = m.group(2).toLowerCase(java.util.Locale.ROOT);
        String userInfo = m.group(3);
        int colon = userInfo.indexOf(':');
        if (colon >= 0) return colon < userInfo.length() - 1;
        return (scheme.equals("http") || scheme.equals("https")) && looksLikeToken(userInfo);
    }

    /**
     * {@code url} without the secret {@link #hasCredentials} finds: {@code https://me:TOKEN@host/r.git}
     * becomes {@code https://me@host/r.git}, {@code https://ghp_…@host/r.git} becomes
     * {@code https://host/r.git}. Anything else is returned as it is.
     */
    public static String withoutCredentials(String url) {
        if (!hasCredentials(url)) return url;
        Matcher m = USER_INFO.matcher(url);
        m.find(); // hasCredentials matched the same prefix
        String userInfo = m.group(3);
        int colon = userInfo.indexOf(':');
        String user = colon >= 0 ? userInfo.substring(0, colon) : "";
        if (looksLikeToken(user)) user = ""; // "ghp_…:x-oauth-basic": the user name is the token
        return m.group(1) + (user.isEmpty() ? "" : user + "@") + url.substring(m.end());
    }

    private static boolean looksLikeToken(String user) {
        return user.matches("(ghp|gho|ghu|ghs|ghr)_\\w+|github_pat_\\w+|glpat-[\\w-]+") || user.matches("[\\w-]{30,}");
    }

    /**
     * Stops the run at the next safe point: before the fetch, during it, or after it before anything on
     * this computer is written. Once the merged data is being written the run finishes, and this returns
     * false. Any thread.
     */
    public synchronized boolean cancel() {
        if (committed) return false;
        cancelled = true;
        if (inFlight != null) inFlight.cancel();
        return true;
    }

    private synchronized boolean stopHere() {
        return cancelled;
    }

    /** Commits to finishing the run, unless it was cancelled first. */
    private synchronized boolean commit() {
        if (!cancelled) committed = true;
        return committed;
    }

    /**
     * Syncs.
     *
     * @param allowLargeRemoval run even when the merge removes most of a file's entries
     *     ({@link SyncReport.Status#NEEDS_CONFIRMATION} otherwise)
     */
    public SyncReport run(boolean allowLargeRemoval) {
        if (!isUsableUrl(url) || !isUsableBranch(branch)) {
            return SyncReport.failure(SyncReport.Status.FAILED, "invalid repository URL or branch");
        }
        if (hasCredentials(url)) {
            return SyncReport.failure(SyncReport.Status.FAILED, "the repository URL contains a password or token");
        }
        if (!git.available()) return SyncReport.failure(SyncReport.Status.NO_GIT, "");
        Set<String> received = new LinkedHashSet<>();
        try {
            if (stopHere()) return cancelled();
            prepareClone(git, url);
            for (int attempt = 1; ; attempt++) {
                SyncReport report = cycle(allowLargeRemoval, received);
                if (report != null) return report;
                if (attempt == ATTEMPTS) {
                    return SyncReport.failure(SyncReport.Status.PUSH_FAILED, "the repository kept changing");
                }
            }
        } catch (GitFailure e) {
            return SyncReport.failure(SyncReport.Status.FAILED, e.getMessage());
        } catch (IOException | RuntimeException e) {
            return SyncReport.failure(SyncReport.Status.FAILED, String.valueOf(e.getMessage()));
        }
    }

    private static SyncReport cancelled() {
        return SyncReport.failure(SyncReport.Status.CANCELLED, "");
    }

    /** One fetch-merge-push round; null when the push lost a race and the round must be repeated. */
    private @Nullable SyncReport cycle(boolean allowLargeRemoval, Set<String> received) throws IOException, GitFailure {
        GitRunner.Result fetch = fetch();
        if (fetch == null || fetch.cancelled() || stopHere()) return cancelled();
        if (!fetch.ok()) return SyncReport.failure(SyncReport.Status.FETCH_FAILED, fetch.transcript());

        Set<String> paths = new TreeSet<>(target.paths());
        String remoteRef = "refs/remotes/origin/" + branch;
        boolean remoteHas = exists(remoteRef);
        Map<String, String> theirs = new LinkedHashMap<>();
        if (remoteHas) {
            must(git.run("checkout", "--quiet", "--force", "-B", branch, remoteRef));
            must(git.run("clean", "-fdq"));
            if (formatVersion(clone.resolve(FORMAT_FILE)) > FORMAT_VERSION) {
                return SyncReport.failure(SyncReport.Status.NEWER_FORMAT, "");
            }
            for (String path : paths) {
                Path file = clone.resolve(path);
                if (!Files.isRegularFile(file)) continue;
                if (Files.size(file) > MAX_FILE_BYTES) {
                    return SyncReport.failure(
                            SyncReport.Status.FAILED,
                            path + " in the repository is larger than " + MAX_FILE_BYTES / 1024 + " KB");
                }
                theirs.put(path, Files.readString(file, StandardCharsets.UTF_8));
            }
        } else {
            // An empty repository (or a new branch). Whatever an earlier, unpushed attempt left in the
            // clone is not the remote's: start from nothing.
            must(git.run("symbolic-ref", "HEAD", "refs/heads/" + branch));
            if (exists("HEAD")) must(git.run("update-ref", "-d", "refs/heads/" + branch));
            must(git.run("read-tree", "--empty"));
            must(git.run("clean", "-fdxq"));
        }
        Map<String, String> base = baseFiles(paths);
        Map<String, String> mine = target.read();

        List<SyncMerge.FileResult> results = new ArrayList<>();
        for (String path : paths) {
            results.add(SyncMerge.merge(path, base.get(path), mine.get(path), theirs.get(path)));
        }
        String largeRemoval = allowLargeRemoval ? null : largeRemoval(results);
        if (largeRemoval != null) return SyncReport.failure(SyncReport.Status.NEEDS_CONFIRMATION, largeRemoval);

        List<String> sent = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Map<String, String> liveChanges = new LinkedHashMap<>();
        for (SyncMerge.FileResult r : results) {
            received.addAll(r.received()); // a set: a repeated round receives some entries again
            sent.addAll(r.sent());
            conflicts.addAll(r.conflicts());
            if (!Objects.equals(r.text(), SyncMerge.normalize(r.path(), mine.get(r.path())))) {
                liveChanges.put(r.path(), r.text());
            }
        }
        if (!commit()) return cancelled(); // the last point before this computer's data changes
        if (!liveChanges.isEmpty() && !target.apply(mine, liveChanges)) {
            return SyncReport.failure(SyncReport.Status.LOCAL_BUSY, "");
        }

        String settled = settledBase(results, base);

        for (SyncMerge.FileResult r : results) writeOrDelete(clone.resolve(r.path()), r.text());
        writeIfAbsent(FORMAT_FILE, "formatVersion=" + FORMAT_VERSION + "\n");
        writeIfAbsent(".gitattributes", "* -text\n");
        writeIfAbsent("README.md", README);
        List<String> written = new ArrayList<>(paths);
        written.addAll(List.of(FORMAT_FILE, ".gitattributes", "README.md"));
        stage(written);
        if (!git.run("diff", "--cached", "--quiet").ok()) {
            must(git.run(withIdentity("commit", "--quiet", "--no-verify", "-m", "Sync from " + machine)));
        }
        if (!remoteHas || !rev("HEAD").equals(rev(remoteRef))) {
            GitRunner.Result push = git.network("push", "--quiet", "origin", "HEAD:refs/heads/" + branch);
            if (push.nonFastForward() || !push.ok()) {
                // The live data already holds the merge. What it took from the repository is agreed
                // now; what it has to send is not, and goes again next round.
                if (settled != null) must(git.run("update-ref", BASE_REF, settled));
            }
            if (push.nonFastForward()) return null; // another computer pushed first; merged next round
            if (!push.ok()) {
                return new SyncReport(
                        SyncReport.Status.PUSH_FAILED, List.copyOf(received), List.of(), conflicts, push.transcript());
            }
        }
        must(git.run("update-ref", BASE_REF, "HEAD"));
        return new SyncReport(SyncReport.Status.OK, List.copyOf(received), sent, conflicts, "");
    }

    /**
     * Writes the {@link SyncMerge.FileResult#settled settled} base as a commit of its own, for
     * {@value #BASE_REF} to move to if this round's push does not go through; null when it is the base
     * already. Goes through the clone's work tree and index, which the merged files then overwrite.
     */
    private @Nullable String settledBase(List<SyncMerge.FileResult> results, Map<String, String> base)
            throws IOException, GitFailure {
        boolean moved = false;
        for (SyncMerge.FileResult r : results) {
            if (!r.settled().equals(KeybindingsFile.entries(base.get(r.path())))) moved = true;
        }
        if (!moved) return null;
        List<String> paths = new ArrayList<>();
        for (SyncMerge.FileResult r : results) {
            writeOrDelete(clone.resolve(r.path()), KeybindingsFile.text(r.path(), r.settled()));
            paths.add(r.path());
        }
        stage(paths);
        String tree = must(git.run("write-tree")).out().strip();
        return must(git.run(withIdentity("commit-tree", tree, "-m", "Agreed with the repository by " + machine)))
                .out()
                .strip();
    }

    /**
     * Stages {@code paths} as the work tree has them, a missing file as a removal. Forced: an ignore rule
     * — the user's global {@code core.excludesFile}, or a {@code .gitignore} in the repository — must not
     * quietly leave a synced file out of the commit.
     */
    private void stage(List<String> paths) throws GitFailure {
        List<String> add = new ArrayList<>(List.of("add", "--force", "--"));
        List<String> remove = new ArrayList<>(List.of("rm", "--cached", "--quiet", "--ignore-unmatch", "--"));
        int addArgs = add.size();
        int removeArgs = remove.size();
        for (String path : paths) {
            (Files.exists(clone.resolve(path)) ? add : remove).add(path);
        }
        if (add.size() > addArgs) must(git.run(add.toArray(String[]::new)));
        if (remove.size() > removeArgs) must(git.run(remove.toArray(String[]::new)));
    }

    /** {@code args}, preceded by a stand-in author when the user has not configured one. */
    private String[] withIdentity(String... args) {
        List<String> argv = new ArrayList<>();
        if (!git.run("config", "--get", "user.email").ok()) {
            argv.addAll(List.of("-c", "user.name=Termina", "-c", "user.email=termina@localhost"));
        }
        argv.addAll(List.of(args));
        return argv.toArray(String[]::new);
    }

    private GitRunner.@Nullable Result fetch() {
        GitRunner.Cancellation c = new GitRunner.Cancellation();
        synchronized (this) {
            if (cancelled) return null;
            inFlight = c;
        }
        try {
            return git.network(c, "fetch", "--prune", "--quiet", "origin");
        } finally {
            synchronized (this) {
                inFlight = null;
            }
        }
    }

    /** Creates the clone on first use and points it at {@link #url}; a different repository forgets the base. */
    /**
     * Creates the clone and points it at {@code url}, as the start of a run does — for the caller that
     * has to look at the clone before the run, to choose the account it signs in as. False when the
     * URL is not one a run would accept, or git failed; the run then reports why.
     */
    public static boolean prepare(GitRunner git, String url) {
        String u = url == null ? "" : url.strip();
        if (!isUsableUrl(u) || hasCredentials(u)) return false;
        try {
            prepareClone(git, u);
            return true;
        } catch (IOException | GitFailure e) {
            return false;
        }
    }

    private static void prepareClone(GitRunner git, String url) throws IOException, GitFailure {
        Path clone = git.dir();
        Files.createDirectories(clone);
        if (!Files.exists(clone.resolve(".git"))) must(git.run("init", "--quiet"));
        // The URL as stored: "remote get-url" applies the user's insteadOf rewriting, and a rewritten
        // URL never equals the configured one.
        GitRunner.Result current = git.run("config", "--local", "--get", "remote.origin.url");
        if (!current.ok()) {
            must(git.run("remote", "add", "origin", url));
        } else if (!current.out().strip().equals(url)) {
            // What was synced with the old repository says nothing about this one: start as a first
            // sync, which keeps everything on both sides.
            must(git.run("remote", "set-url", "origin", url));
            if (exists(git, BASE_REF)) must(git.run("update-ref", "-d", BASE_REF));
            git.run("config", "--local", "--unset", SyncAccount.KEY); // nor does the account that could read it
        }
    }

    /** The files of the base commit; empty when this computer has not finished a sync with this repository. */
    private Map<String, String> baseFiles(Set<String> paths) throws GitFailure {
        Map<String, String> out = new LinkedHashMap<>();
        if (!exists(BASE_REF)) return out;
        Set<String> tracked = new TreeSet<>();
        for (String line : must(git.run("ls-tree", "-r", "-z", "--name-only", BASE_REF))
                .out()
                .split("\0")) {
            if (!line.isEmpty()) tracked.add(line);
        }
        for (String path : paths) {
            if (tracked.contains(path))
                out.put(path, must(git.run("show", BASE_REF + ":" + path)).out());
        }
        return out;
    }

    /**
     * The path of a file that would lose more than half of what either side has, or null. A sync of two
     * healthy computers never does that; an emptied or damaged file on one of them does, and would
     * otherwise be carried to every other computer.
     */
    static @Nullable String largeRemoval(List<SyncMerge.FileResult> results) {
        for (SyncMerge.FileResult r : results) {
            int left = r.merged().size();
            if ((r.mineEntries() >= GUARDED_SIZE && left * 2 < r.mineEntries())
                    || (r.theirEntries() >= GUARDED_SIZE && left * 2 < r.theirEntries())) {
                return r.path();
            }
        }
        return null;
    }

    /** The format version a repository declares; 0 when it declares none or the file cannot be read. */
    static int formatVersion(Path file) {
        if (!Files.isRegularFile(file)) return 0;
        try {
            Properties p = new Properties();
            p.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            return Integer.parseInt(p.getProperty("formatVersion", "0").strip());
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** Writes {@code text} to {@code file}, or deletes it when {@code text} is null. */
    static void writeOrDelete(Path file, @Nullable String text) throws IOException {
        if (text == null) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private void writeIfAbsent(String name, String text) throws IOException {
        Path file = clone.resolve(name);
        if (!Files.exists(file)) Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private boolean exists(String ref) {
        return exists(git, ref);
    }

    private static boolean exists(GitRunner git, String ref) {
        return git.run("rev-parse", "--verify", "--quiet", ref + "^{commit}").ok();
    }

    private String rev(String ref) throws GitFailure {
        return must(git.run("rev-parse", "--verify", ref + "^{commit}")).out().strip();
    }

    private static GitRunner.Result must(GitRunner.Result result) throws GitFailure {
        if (!result.ok()) throw new GitFailure(result.transcript());
        return result;
    }

    private static final class GitFailure extends Exception {
        private static final long serialVersionUID = 1L;

        GitFailure(String message) {
            super(message);
        }
    }

    private static final String README = """
            # Termina settings sync

            This repository is written by the Termina terminal's settings sync. It holds the key bindings
            the computers connected to it have changed: keybindings/macos.properties for Macs and
            keybindings/windows-linux.properties for everything else, one command=chord line each.

            Editing a file here by hand is fine: every connected computer picks the change up on its
            next sync.
            """;
}
