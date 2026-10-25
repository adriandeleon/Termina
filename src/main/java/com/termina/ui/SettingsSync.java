package com.termina.ui;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import com.termina.config.Settings;
import com.termina.link.CommandPath;
import com.termina.sync.GitRunner;
import com.termina.sync.KeybindingsFile;
import com.termina.sync.SyncEngine;
import com.termina.sync.SyncReport;
import com.termina.sync.SyncTarget;

import static com.termina.i18n.Messages.tr;

/**
 * Settings sync for the whole application: decides when a {@link SyncEngine} run happens, runs it off
 * the FX thread, writes what it brings into the live settings, and says what happened.
 *
 * <p>One instance, owned by {@link WindowManager}; every method is called on the FX thread. Only one
 * Termina process on a settings folder syncs — the first to connect takes a lock for its lifetime. A
 * second one holds its own in-memory copy of the settings and would push that, undoing the first one's
 * changes.
 *
 * <p>Automatic runs (after startup, {@value #DIRTY_DELAY_SECONDS} s after a key binding changed, and on
 * the interval) never ask for anything: git runs without a terminal or an askpass program, and a failure
 * is a line on the Sync page rather than a dialog over the terminal. A run the user asked for may use
 * their credential helper.
 */
final class SettingsSync {

    static final int DIRTY_DELAY_SECONDS = 30;
    private static final int STARTUP_DELAY_SECONDS = 5;
    /** Backup sets kept in {@code sync/backups}; each holds the files one run replaced. */
    static final int BACKUPS_KEPT = 10;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    enum Phase {
        /** Not connected, or another Termina is the one syncing. */
        OFF,
        /** Connected; the last run, if any, went well. */
        IDLE,
        SYNCING,
        /** The last run failed or stopped for a decision. */
        PROBLEM
    }

    /** @param report the last finished run, or null; {@code at} is when it finished */
    record State(Phase phase, SyncReport report, LocalTime at) {}

    private final Settings settings;
    private final boolean mac;
    /** Whether a window of the app has focus: the interval run waits for someone to be there. */
    private final BooleanSupplier anyWindowFocused;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "settings-sync");
        t.setDaemon(true);
        return t;
    });
    private final PauseTransition dirty = new PauseTransition(Duration.seconds(DIRTY_DELAY_SECONDS));
    private final PauseTransition startup = new PauseTransition(Duration.seconds(STARTUP_DELAY_SECONDS));
    private final List<Runnable> listeners = new ArrayList<>();
    private Timeline timer;
    private int timerMinutes;

    private State state = new State(Phase.OFF, null, null);
    private boolean running;
    private boolean again;
    /** True while a run's result is being written into the settings: that is not "the user changed something". */
    private boolean applying;

    private boolean startupDone;
    /** The run under way, for {@link #cancel()}; null between runs. Written on the worker. */
    private volatile SyncEngine runningEngine;

    /** The key bindings as {@link #onSettingsChanged} last saw them. */
    private String keybindingsSeen;

    /** Held for the life of the process once this one is the syncing instance. */
    private FileChannel lockChannel;

    private FileLock lock;
    /** Another process holds the lock. Asked once: see the class comment. */
    private boolean secondary;

    private volatile String machine = "";

    SettingsSync(Settings settings, BooleanSupplier anyWindowFocused) {
        this.settings = settings;
        this.mac = Keybindings.MAC;
        this.anyWindowFocused = anyWindowFocused;
        dirty.setOnFinished(e -> request(false, false));
        startup.setOnFinished(e -> request(false, false));
        worker.execute(() -> machine = machineName());
        keybindingsSeen = renderKeybindings();
        settings.addOnChange(this::onSettingsChanged);
        settingsChanged();
    }

    // ---------------------------------------------------------------- state

    State state() {
        return state;
    }

    /** Runs {@code listener} on the FX thread after every state change. */
    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Switched on with a repository that can be used — whether or not this process is the one syncing. */
    boolean configured() {
        return isConfigured(settings.syncEnabled(), settings.syncRepository(), settings.syncBranch());
    }

    /** The rule {@link #configured()} applies. Pure. */
    static boolean isConfigured(boolean enabled, String url, String branch) {
        return enabled
                && SyncEngine.isUsableUrl(url)
                && !SyncEngine.hasCredentials(url)
                && SyncEngine.isUsableBranch(branch);
    }

    /** Connected, and this is the process that syncs. */
    boolean active() {
        return configured() && primary();
    }

    /** Whether another Termina on this settings folder is the one syncing. */
    boolean secondary() {
        return secondary;
    }

    /** The folder that holds the clone and the backups. */
    Path syncDir() {
        return settings.directory().resolve("sync");
    }

    private void setState(State next) {
        state = next;
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }

    /**
     * Takes the instance lock the first time it is needed. Never retried after a refusal: the process
     * that holds it may let go later, but this one's settings are its own in-memory copy from launch,
     * and syncing those would undo whatever the other one changed.
     */
    private boolean primary() {
        if (lock != null) return true;
        if (secondary) return false;
        try {
            Files.createDirectories(syncDir());
            lockChannel = FileChannel.open(
                    syncDir().resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
        } catch (IOException | OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            secondary = true;
            closeQuietly();
        }
        return lock != null;
    }

    private void closeQuietly() {
        if (lockChannel == null) return;
        try {
            lockChannel.close();
        } catch (IOException ignored) {
            // nothing was locked through it
        }
        lockChannel = null;
    }

    // ---------------------------------------------------------------- triggers

    /** Re-reads the sync settings: starts or stops the timers. */
    void settingsChanged() {
        boolean auto = active() && settings.syncAuto();
        int minutes = auto ? settings.syncIntervalMinutes() : 0;
        if (minutes != timerMinutes) { // an unrelated settings change must not push the next run back
            timerMinutes = minutes;
            if (timer != null) {
                timer.stop();
                timer = null;
            }
            if (auto) {
                timer = new Timeline(new KeyFrame(Duration.minutes(minutes), e -> tick()));
                timer.setCycleCount(Animation.INDEFINITE);
                timer.play();
            }
        }
        if (auto) {
            if (!startupDone) {
                startupDone = true;
                startup.playFromStart();
            }
        } else {
            dirty.stop();
            startup.stop();
        }
        if (!active()) {
            if (state.phase() != Phase.OFF) setState(new State(Phase.OFF, null, null));
        } else if (state.phase() == Phase.OFF) {
            setState(new State(Phase.IDLE, null, null));
        }
    }

    private void onSettingsChanged() {
        settingsChanged();
        String now = renderKeybindings();
        if (Objects.equals(now, keybindingsSeen)) return;
        keybindingsSeen = now;
        if (!applying && active() && settings.syncAuto()) dirty.playFromStart();
    }

    private void tick() {
        // A terminal nobody is looking at does not talk to the network.
        if (anyWindowFocused.getAsBoolean()) request(false, false);
    }

    /** "Sync Now". */
    void syncNow() {
        request(true, false);
    }

    /** Repeats a run that stopped at {@link SyncReport.Status#NEEDS_CONFIRMATION}, allowing the removal. */
    void syncAllowingLargeRemoval() {
        request(true, true);
    }

    /** Stops the run under way if it has not started writing; false when it is too late or nothing runs. */
    boolean cancel() {
        SyncEngine engine = runningEngine;
        return running && engine != null && engine.cancel();
    }

    /** Sync was just switched on: runs the first sync now. */
    void connected() {
        startupDone = true; // the run below is the startup run
        settingsChanged();
        request(true, false);
    }

    /**
     * Sync was just switched off: forgets the clone. The bindings here and in the repository stay;
     * connecting again starts as a first sync, which keeps everything on both sides.
     */
    void disconnected() {
        settingsChanged();
        Path repo = syncDir().resolve("repo");
        worker.execute(() -> {
            try {
                deleteTree(repo);
            } catch (IOException ignored) {
                // a leftover clone is reused or repointed by the next connect
            }
        });
    }

    private void request(boolean userAsked, boolean allowLargeRemoval) {
        if (!active()) return;
        if (running) {
            again = true;
            return;
        }
        running = true;
        dirty.stop();
        setState(new State(Phase.SYNCING, state.report(), state.at()));
        String url = settings.syncRepository();
        String branch = settings.syncBranch();
        Path repo = syncDir().resolve("repo");
        SyncTarget target = new LiveTarget();
        worker.execute(() -> {
            List<String> path =
                    LoginShellPath.directories().stream().map(Path::toString).toList();
            GitRunner git = new GitRunner(gitCommand(), repo, userAsked, String.join(File.pathSeparator, path));
            SyncEngine engine = new SyncEngine(git, url, branch, target, machine);
            runningEngine = engine;
            SyncReport report = engine.run(allowLargeRemoval);
            runningEngine = null;
            Platform.runLater(() -> finished(report, userAsked));
        });
    }

    /**
     * How to start git. A bare {@code git} would be looked up on the PATH this process was given, which
     * for an app started from the Dock or a desktop launcher is a stripped one — on a Mac that finds
     * Apple's stub, which offers to install the developer tools instead of running the git the user
     * installed. Looked for on the login shell's PATH as well, the way a clicked link's command is.
     */
    private static List<String> gitCommand() {
        Path found = CommandPath.resolve("git", LoginShellPath.directories(), Files::isExecutable);
        return List.of(found == null ? "git" : found.toString());
    }

    private void finished(SyncReport report, boolean userAsked) {
        running = false;
        if (!active()) { // disconnected while it ran
            again = false;
            setState(new State(Phase.OFF, null, null));
            return;
        }
        if (report.status() == SyncReport.Status.CANCELLED) {
            again = false;
            SyncReport last = state.report();
            setState(new State(last == null || last.ok() ? Phase.IDLE : Phase.PROBLEM, last, state.at()));
            return;
        }
        // "The bindings changed while I was syncing" is not a problem: the change itself scheduled a run.
        boolean busy = report.status() == SyncReport.Status.LOCAL_BUSY;
        boolean problem = !report.ok() && !busy;
        setState(new State(problem ? Phase.PROBLEM : Phase.IDLE, report, LocalTime.now()));
        if (again || busy) {
            again = false;
            dirty.playFromStart();
        }
    }

    // ---------------------------------------------------------------- this computer's side

    private String renderKeybindings() {
        return KeybindingsFile.text(KeybindingsFile.pathFor(mac), settings.keyBindings());
    }

    /**
     * The live key bindings as the repository's file, read and written on the FX thread — the thread
     * every other write to {@link Settings} happens on. Before anything is replaced it is copied to
     * {@code sync/backups/<timestamp>/}: what a sync takes from the repository replaces bindings that were
     * never pushed anywhere.
     */
    private final class LiveTarget implements SyncTarget {

        private final String path = KeybindingsFile.pathFor(mac);

        @Override
        public Set<String> paths() {
            return Set.of(path);
        }

        @Override
        public Map<String, String> read() throws IOException {
            String text = onFx(SettingsSync.this::renderKeybindings);
            return text == null ? Map.of() : Map.of(path, text);
        }

        @Override
        public boolean apply(Map<String, String> expected, Map<String, String> changes) throws IOException {
            return Boolean.TRUE.equals(onFx(() -> {
                String current = renderKeybindings();
                if (!Objects.equals(current, expected.get(path))) return false;
                if (!changes.containsKey(path)) return true;
                if (current != null) backup(Map.of(path, current));
                applying = true;
                try {
                    String text = changes.get(path);
                    settings.replaceKeyBindings(KeybindingsFile.entries(text));
                    keybindingsSeen = renderKeybindings();
                } finally {
                    applying = false;
                }
                return true;
            }));
        }
    }

    /** Copies {@code files} (repository path to text) into a new backup set, keeping the newest few. */
    private void backup(Map<String, String> files) throws IOException {
        Path set = syncDir().resolve("backups").resolve(LocalDateTime.now().format(STAMP));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path copy = set.resolve(file.getKey());
            Files.createDirectories(copy.getParent());
            Files.writeString(copy, file.getValue(), StandardCharsets.UTF_8);
        }
        prune(syncDir().resolve("backups"), BACKUPS_KEPT);
    }

    /** Deletes all but the newest {@code keep} sets under {@code backups}. */
    static void prune(Path backups, int keep) {
        try (Stream<Path> sets = Files.list(backups)) {
            List<Path> newestFirst = sets.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString())
                            .reversed())
                    .toList();
            for (Path old : newestFirst.subList(Math.min(keep, newestFirst.size()), newestFirst.size())) {
                deleteTree(old);
            }
        } catch (IOException ignored) {
            // an old backup that cannot be removed is only disk space
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true); // git marks its objects read-only; Windows refuses to delete those
                Files.deleteIfExists(p);
            }
        }
    }

    /** Runs {@code work} on the FX thread and waits for its result. */
    private static <T> T onFx(Callable<T> work) throws IOException {
        CompletableFuture<T> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                done.complete(work.call());
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        });
        try {
            return done.get(1, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    // ---------------------------------------------------------------- words

    /** "3 received, 1 sent" and, when there are any, the conflicts. Pure. */
    static String summary(SyncReport report, Function<String, String> label) {
        StringBuilder sb = new StringBuilder(
                report.changedAnything()
                        ? tr(
                                "sync.result.counts",
                                report.received().size(),
                                report.sent().size())
                        : tr("sync.result.upToDate"));
        if (!report.conflicts().isEmpty()) {
            List<String> conflicts = report.conflicts();
            int shown = Math.min(5, conflicts.size());
            String names = conflicts.subList(0, shown).stream().map(label).collect(Collectors.joining(", "));
            sb.append(". ").append(tr("sync.result.conflicts", shown < conflicts.size() ? names + ", …" : names));
        }
        return sb.toString();
    }

    /** One line saying why a run did not finish and, where there is one, the next step. Pure. */
    static String problemText(SyncReport report) {
        return switch (report.status()) {
            case NO_GIT -> tr("sync.problem.noGit");
            case FETCH_FAILED -> tr("sync.problem.fetchFailed", report.detail());
            case PUSH_FAILED -> tr("sync.problem.pushFailed", report.detail());
            case NEWER_FORMAT -> tr("sync.problem.newerFormat");
            case NEEDS_CONFIRMATION -> tr("sync.problem.needsConfirmation");
            default -> tr("sync.problem.failed", report.detail());
        };
    }

    /** What the Sync page shows on its status row. */
    String stateText(Function<String, String> label) {
        if (configured() && secondary) return tr("sync.state.secondary");
        SyncReport report = state.report();
        LocalTime at = state.at();
        return switch (state.phase()) {
            case OFF -> tr("sync.state.off");
            case SYNCING -> tr("sync.state.syncing");
            case PROBLEM -> report == null ? tr("sync.state.never") : problemText(report);
            case IDLE ->
                report == null || !report.ok() || at == null
                        ? tr("sync.state.never")
                        : tr(
                                "sync.state.ok",
                                at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                                summary(report, label));
        };
    }

    private static String machineName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) name = System.getenv("HOSTNAME");
        if (name == null || name.isBlank()) {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (IOException | RuntimeException e) {
                name = "";
            }
        }
        return name;
    }
}
