package com.termina.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * Runs git in the sync clone, a repository Termina owns and nothing else reads. Blocking; the caller
 * picks the thread.
 *
 * <p>Every command runs hardened: hooks, fsmonitor, the pager, commit signing and end-of-line
 * conversion are off, because a hook or a signing key's pinentry would be a program the user never
 * asked to run. A non-interactive runner also never asks for anything — no terminal, no askpass, no
 * credential-manager window, ssh in batch mode — so a remote that needs a sign-in fails with git's
 * message instead of waiting on a prompt nobody can see. An interactive one keeps the user's askpass
 * and credential helper, for the run the user is watching.
 *
 * <p>A port of Editora's {@code QuietGit}, {@code GitSafety} and {@code GitOutcome}, cut to what the
 * sync needs.
 */
public final class GitRunner {

    /** Ceiling for a command that talks to the remote. */
    static final Duration NETWORK = Duration.ofMinutes(2);

    /** Ceiling for a local command. */
    static final Duration LOCAL = Duration.ofSeconds(30);

    /** Output past this is cut: nothing the sync reads is anywhere near it. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final List<String> HARDENED = List.of(
            "-c", "core.fsmonitor=false",
            "-c", "core.hooksPath=" + (WINDOWS ? "NUL" : "/dev/null"),
            "-c", "core.pager=cat",
            "-c", "core.autocrlf=false",
            "-c", "core.safecrlf=false",
            // A link in the repository is checked out as a small text file, never followed.
            "-c", "core.symlinks=false",
            "-c", "commit.gpgsign=false",
            "-c", "tag.gpgsign=false",
            "-c", "log.showSignature=false",
            "-c", "protocol.ext.allow=never",
            "-c", "advice.detachedHead=false");

    /** No askpass program from configuration, and a credential helper told not to interact. */
    private static final List<String> SILENT = List.of("-c", "core.askPass=", "-c", "credential.interactive=false");

    /**
     * What binds a git process to one particular repository. Inherited from a shell that exports
     * {@code GIT_DIR} (a bare-repository dotfiles setup), every command would act on that one instead.
     */
    static final List<String> REPO_LOCAL_ENV = List.of(
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_CONFIG",
            "GIT_CONFIG_PARAMETERS",
            "GIT_CONFIG_COUNT",
            "GIT_OBJECT_DIRECTORY",
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_IMPLICIT_WORK_TREE",
            "GIT_GRAFT_FILE",
            "GIT_INDEX_FILE",
            "GIT_NO_REPLACE_OBJECTS",
            "GIT_REPLACE_REF_BASE",
            "GIT_PREFIX",
            "GIT_SHALLOW_FILE",
            "GIT_COMMON_DIR");

    /** What a finished command did. {@code exit} is negative when it did not start or ran out of time. */
    public record Result(int exit, String out, String err, boolean cancelled) {

        public boolean ok() {
            return exit == 0 && !cancelled;
        }

        /**
         * What git printed, for showing to the user: stdout then stderr, without blank lines or git's
         * {@code hint:} lines, which name terminal commands rather than anything in the app.
         */
        public String transcript() {
            StringBuilder sb = new StringBuilder();
            for (String part : new String[] {out, err}) {
                if (part == null) continue;
                for (String line : part.split("\\R")) {
                    if (line.isBlank() || line.startsWith("hint:")) continue;
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(line.strip());
                }
            }
            return sb.toString();
        }

        /** A push the remote refused because it has commits this side lacks. */
        public boolean nonFastForward() {
            if (exit <= 0) return false;
            String text = (out + "\n" + err).toLowerCase(Locale.ROOT);
            return text.contains("[rejected]")
                    && (text.contains("(non-fast-forward)") || text.contains("(fetch first)"));
        }

        static Result failed(@Nullable String why) {
            return new Result(-1, "", why == null ? "" : why, false);
        }
    }

    /** Stops the command in flight. Any thread. */
    public static final class Cancellation {
        private @Nullable Process process;
        private boolean cancelled;

        public synchronized void cancel() {
            cancelled = true;
            if (process != null) {
                // destroy, not destroyForcibly: SIGTERM lets git remove its own lock files.
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
            }
        }

        synchronized boolean attach(Process p) {
            process = p;
            return !cancelled;
        }

        synchronized boolean cancelled() {
            return cancelled;
        }
    }

    private final List<String> command;
    private final Path dir;
    private final boolean interactive;
    private final String path;
    /** {@code -c} pairs added last, so they outrank the user's configuration; see {@link #with}. */
    private final List<String> extraConfig;

    private final Map<String, String> env;

    /**
     * @param command how to start git: {@code [git]}, or an absolute path found on the login shell's PATH
     * @param path the PATH git's own children (ssh, a credential helper) should see; blank to inherit
     */
    public GitRunner(List<String> command, Path dir, boolean interactive, String path) {
        this(command, dir, interactive, path, List.of());
    }

    private GitRunner(List<String> command, Path dir, boolean interactive, String path, List<String> extraConfig) {
        this.command = List.copyOf(command == null || command.isEmpty() ? List.of("git") : command);
        this.dir = dir;
        this.interactive = interactive;
        this.path = path == null ? "" : path;
        this.extraConfig = List.copyOf(extraConfig);
        this.env = environment(System.getenv(), interactive, this.path);
    }

    public Path dir() {
        return dir;
    }

    /** The same runner, but one that never asks for anything. */
    public GitRunner silent() {
        return interactive ? new GitRunner(command, dir, false, path, extraConfig) : this;
    }

    /**
     * The same runner with {@code config} ({@code -c key=value} pairs) added to every command after
     * everything else, so it outranks the user's own configuration. Replaces any added before.
     */
    public GitRunner with(List<String> config) {
        return config == null || config.isEmpty() ? this : new GitRunner(command, dir, interactive, path, config);
    }

    /** Whether the configured git starts at all. */
    public boolean available() {
        List<String> argv = new ArrayList<>(command);
        argv.add("--version");
        return execute(null, LOCAL, argv, null).ok();
    }

    /** A local command. */
    public Result run(String... args) {
        return execute(dir, LOCAL, argv(interactive, args), null);
    }

    /** A command that talks to the remote. */
    public Result network(String... args) {
        return network(null, args);
    }

    /** As {@link #network(String...)}, stopped (its process tree terminated) by {@code cancel}. */
    public Result network(@Nullable Cancellation cancel, String... args) {
        return execute(dir, NETWORK, argv(interactive, args), cancel);
    }

    /** The whole command line. */
    List<String> argv(boolean interactive, String... args) {
        List<String> argv = new ArrayList<>(command);
        argv.addAll(HARDENED);
        if (!interactive) argv.addAll(SILENT);
        argv.addAll(extraConfig);
        argv.addAll(List.of(args));
        return argv;
    }

    /**
     * The changes to the inherited environment, as name to value, a null value meaning "remove".
     *
     * <p>ssh is put in batch mode only when the user has not exported an ssh command of their own:
     * {@code GIT_SSH_COMMAND} outranks {@code core.sshCommand}, and replacing theirs would break the
     * sign-in it exists for. Messages are pinned to English ({@code LC_MESSAGES}), which is what
     * {@link Result#nonFastForward} reads. Pure.
     */
    static Map<String, String> environment(Map<String, String> inherited, boolean interactive, String path) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : REPO_LOCAL_ENV) env.put(name, null);
        env.put("GIT_OPTIONAL_LOCKS", "0");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_PAGER", "cat");
        env.put("GIT_EDITOR", ":");
        env.put("LC_MESSAGES", "C");
        env.put("LANGUAGE", "C");
        String all = inherited == null ? null : inherited.get("LC_ALL");
        if (all != null && !all.isEmpty()) {
            // LC_ALL would outrank LC_MESSAGES; its value is kept where it decides how text decodes.
            env.put("LC_ALL", "");
            env.put("LC_CTYPE", all);
        }
        if (path != null && !path.isBlank()) env.put("PATH", path);
        if (!interactive) {
            env.put("GIT_ASKPASS", "");
            env.put("SSH_ASKPASS", "");
            env.put("SSH_ASKPASS_REQUIRE", "never");
            env.put("GCM_INTERACTIVE", "never");
            boolean ownSsh = inherited != null
                    && (notBlank(inherited.get("GIT_SSH_COMMAND")) || notBlank(inherited.get("GIT_SSH")));
            if (!ownSsh) env.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");
        }
        return Collections.unmodifiableMap(env);
    }

    private static boolean notBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private Result execute(@Nullable Path workDir, Duration timeout, List<String> argv, @Nullable Cancellation cancel) {
        Path out = null;
        Path err = null;
        Process process = null;
        try {
            out = Files.createTempFile("termina-git-", ".out");
            err = Files.createTempFile("termina-git-", ".err");
            ProcessBuilder builder = new ProcessBuilder(argv)
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
            if (workDir != null) builder.directory(workDir.toFile());
            Map<String, String> childEnv = builder.environment();
            env.forEach((name, value) -> {
                if (value == null) childEnv.remove(name);
                else childEnv.put(name, value);
            });
            process = builder.start();
            if (cancel != null && !cancel.attach(process)) {
                process.destroy();
            }
            boolean finished = process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
            boolean cancelled = cancel != null && cancel.cancelled();
            if (!finished) {
                return new Result(
                        -1, "", cancelled ? "" : "git did not finish in " + timeout.toSeconds() + " s", cancelled);
            }
            return new Result(process.exitValue(), read(out), read(err), cancelled);
        } catch (IOException e) {
            return Result.failed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(WINDOWS ? "NUL" : "/dev/null");
    }

    private static String read(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static void deleteQuietly(@Nullable Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Windows may still hold it while the killed process exits.
            file.toFile().deleteOnExit();
        }
    }
}
