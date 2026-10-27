package com.termina.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Makes a private repository for settings sync with the GitHub CLI, so connecting does not start with
 * a trip to the browser. Termina never sees a token: gh signs in as its own active account. Blocking;
 * the caller picks the thread.
 *
 * <p>A port of Editora's {@code GitHubService.createPrivateRepo} and {@code remoteUrl}.
 */
public final class GitHubRepo {

    /** Ceiling for {@code gh repo create}, which talks to GitHub. */
    public static final Duration NETWORK = Duration.ofMinutes(1);

    /** Ceiling for a question gh answers from its own configuration. */
    public static final Duration QUICK = Duration.ofSeconds(15);

    private static final int MAX_BYTES = 1024 * 1024;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** The repository named by the address {@code gh repo create} prints. */
    private static final Pattern WEB_URL =
            Pattern.compile("https://([A-Za-z0-9.-]+)/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?\\s*$");

    /**
     * A repository {@link #createPrivate} made.
     *
     * @param url what to hand git as the remote, or blank when it failed
     * @param error gh's message when it failed
     */
    public record Created(boolean ok, String url, String error) {}

    private record Result(int exit, String out, String err) {
        boolean ok() {
            return exit == 0;
        }

        /** What gh said, for showing to the user: its error first, else what it printed. */
        String message() {
            String said = err.isBlank() ? out : err;
            return said.strip().replaceAll("\\s*\\R\\s*", " ");
        }
    }

    private GitHubRepo() {}

    /**
     * Whether {@code name} is one GitHub accepts as a repository's name, and one gh cannot read as an
     * option (no leading hyphen). Pure.
     */
    public static boolean isName(String name) {
        return name != null
                && name.matches("[A-Za-z0-9._][A-Za-z0-9._-]{0,99}")
                && !name.equals(".")
                && !name.equals("..");
    }

    /**
     * Whether gh is installed and signed in ({@code gh auth status} succeeds) — the condition for
     * offering to create a repository at all.
     */
    public static boolean signedIn(List<String> ghCommand) {
        return run(ghCommand, QUICK, "auth", "status").ok();
    }

    /**
     * Creates a private repository called {@code name} under gh's active account
     * ({@code gh repo create <name> --private}) and gives the URL git should use for it: the SSH form
     * when the user's gh is set to the ssh protocol, the HTTPS one otherwise.
     */
    public static Created createPrivate(List<String> ghCommand, String name, String description) {
        Result created = run(ghCommand, NETWORK, "repo", "create", name, "--private", "--description", description);
        if (!created.ok()) return new Created(false, "", created.message());
        Result protocol = run(ghCommand, QUICK, "config", "get", "git_protocol");
        String url = remoteUrl(created.out(), protocol.ok() ? protocol.out() : "");
        return url.isEmpty() ? new Created(false, "", created.message()) : new Created(true, url, "");
    }

    /**
     * The git remote for the repository whose web address {@code gh repo create} printed:
     * {@code git@host:owner/name.git} for the {@code ssh} protocol, else
     * {@code https://host/owner/name.git}. Blank when no address is found. Pure.
     */
    public static String remoteUrl(String output, String protocol) {
        if (output == null) return "";
        for (String line : output.strip().split("\\R")) {
            Matcher m = WEB_URL.matcher(line.strip());
            if (m.find()) {
                String path = m.group(2) + "/" + m.group(3) + ".git";
                return protocol != null && protocol.strip().equalsIgnoreCase("ssh")
                        ? "git@" + m.group(1) + ":" + path
                        : "https://" + m.group(1) + "/" + path;
            }
        }
        return "";
    }

    /** Runs gh with nothing to answer a prompt and nothing on its terminal. */
    private static Result run(List<String> ghCommand, Duration timeout, String... args) {
        if (ghCommand == null || ghCommand.isEmpty()) return new Result(-1, "", "");
        List<String> argv = new ArrayList<>(ghCommand);
        argv.addAll(List.of(args));
        Path out = null;
        Path err = null;
        Process process = null;
        try {
            out = Files.createTempFile("termina-gh-", ".out");
            err = Files.createTempFile("termina-gh-", ".err");
            ProcessBuilder builder = new ProcessBuilder(argv)
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(WINDOWS ? "NUL" : "/dev/null")));
            Map<String, String> env = builder.environment();
            env.put("GH_PROMPT_DISABLED", "1");
            env.put("GH_NO_UPDATE_NOTIFIER", "1");
            env.put("NO_COLOR", "1");
            process = builder.start();
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return new Result(-1, "", "gh did not finish in " + timeout.toSeconds() + " s");
            }
            return new Result(process.exitValue(), read(out), read(err));
        } catch (IOException e) {
            return new Result(-1, "", String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            deleteQuietly(out);
            deleteQuietly(err);
        }
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
