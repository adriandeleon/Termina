package com.termina.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.termina.AppInfo;
import org.jspecify.annotations.Nullable;

/**
 * Asks GitHub whether there is a newer release.
 *
 * <p>Off the FX thread, with the answer delivered back on it. Contacts one HTTPS endpoint and sends
 * nothing about the user or the machine beyond what any HTTP request carries.
 */
public final class UpdateService {

    /**
     * What came back. {@code latest} is null unless there is something newer; {@code error} is
     * null unless the check failed, and then {@code detail} says how, for the log.
     */
    public record Outcome(
            boolean available,
            @Nullable ReleaseInfo latest,
            UpdateCheck.@Nullable ErrorKind error,
            @Nullable String detail) {
        public static Outcome none() {
            return new Outcome(false, null, null, null);
        }

        public static Outcome found(ReleaseInfo release) {
            return new Outcome(true, release, null, null);
        }

        public static Outcome failed(UpdateCheck.ErrorKind kind, String detail) {
            return new Outcome(false, null, kind, detail);
        }
    }

    /** The response is a few kilobytes; the cap is there so a wrong URL cannot stream forever. */
    static final int MAX_BYTES = 1024 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "termina-update-check");
        thread.setDaemon(true);
        return thread;
    });

    private final String endpoint;

    /**
     * One client for the life of the service. A client owns a connection pool and a selector
     * thread, and the first version built one per check and closed none of them.
     */
    private volatile @Nullable HttpClient client;

    public UpdateService() {
        this(AppInfo.LATEST_RELEASE_API);
    }

    UpdateService(String endpoint) {
        this.endpoint = endpoint;
    }

    /** Checks in the background and calls back on the FX thread. */
    public void check(String currentVersion, Consumer<Outcome> onResult) {
        executor.execute(() -> {
            Outcome outcome = checkNow(currentVersion);
            Platform.runLater(() -> onResult.accept(outcome));
        });
    }

    private HttpClient client() {
        HttpClient existing = client;
        if (existing != null) return existing;
        synchronized (this) {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return client;
        }
    }

    private Outcome checkNow(String currentVersion) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    // GitHub rejects a request with no User-Agent outright.
                    .header("User-Agent", AppInfo.NAME + "/" + AppInfo.VERSION)
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 404) {
                // No releases published yet, or the repository is private. Not an error worth
                // showing: there is simply nothing newer.
                return Outcome.none();
            }
            if (response.statusCode() != 200) {
                return Outcome.failed(UpdateCheck.ErrorKind.HTTP, String.valueOf(response.statusCode()));
            }
            String body;
            try (InputStream in = response.body()) {
                body = readCapped(in, MAX_BYTES);
            }
            // A body past the cap is not a release payload, whatever it is. Treated as nothing
            // newer rather than as an error: the user did nothing wrong and can do nothing about it.
            if (body == null) return Outcome.none();

            ReleaseInfo latest = UpdateCheck.parseLatest(body);
            if (latest == null) return Outcome.none();
            return UpdateCheck.isNewer(currentVersion, latest.version()) ? Outcome.found(latest) : Outcome.none();
        } catch (Exception e) {
            // Offline, DNS failure, timeout, TLS problem. A background check that fails is not the
            // user's problem; only a check they asked for reports anything.
            return Outcome.failed(UpdateCheck.classify(e), e.getClass().getSimpleName());
        }
    }

    /**
     * Reads at most {@code max} bytes, or returns null when there are more.
     *
     * <p>Streamed rather than {@code BodyHandlers.ofString}, which buffers the whole body before
     * anything can look at its length — so a cap applied to the string only ever bounded memory
     * after the fact, and the real limit was the timeout.
     */
    static @Nullable String readCapped(InputStream in, int max) throws IOException {
        byte[] buffer = in.readNBytes(max + 1);
        if (buffer.length > max) return null;
        return new String(buffer, StandardCharsets.UTF_8);
    }

    public void shutdown() {
        executor.shutdownNow();
        HttpClient existing = client;
        if (existing != null) existing.close();
    }
}
