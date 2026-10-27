package com.termina.update;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** Connecting, and waiting for the response headers. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * The whole request, body included. {@code HttpRequest.timeout} stops at the headers: a server that
     * sent them and then nothing held the one checking thread forever, and every later check — a manual
     * one too — queued behind it unanswered.
     */
    static final Duration DEADLINE = Duration.ofSeconds(30);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "termina-update-check");
        thread.setDaemon(true);
        return thread;
    });

    private final String endpoint;
    private final Duration deadline;

    /**
     * One client for the life of the service. A client owns a connection pool and a selector
     * thread, and the first version built one per check and closed none of them.
     */
    private volatile @Nullable HttpClient client;

    public UpdateService() {
        this(AppInfo.LATEST_RELEASE_API, DEADLINE);
    }

    UpdateService(String endpoint, Duration deadline) {
        this.endpoint = endpoint;
        this.deadline = deadline;
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

    /** One check, on the calling thread; returns within {@link #deadline} whatever the server does. */
    Outcome checkNow(String currentVersion) {
        CompletableFuture<HttpResponse<@Nullable String>> pending = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    // GitHub rejects a request with no User-Agent outright.
                    .header("User-Agent", AppInfo.NAME + "/" + AppInfo.VERSION)
                    .GET()
                    .build();

            // The body is read whole, under the cap, before the future completes, so one wait bounds
            // everything. Only a 200 has a body worth reading.
            pending = client().sendAsync(
                            request,
                            info -> info.statusCode() == 200
                                    ? new CappedBody(MAX_BYTES)
                                    : HttpResponse.BodySubscribers.replacing(null));
            HttpResponse<@Nullable String> response = pending.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() == 404) {
                // No releases published yet, or the repository is private. Not an error worth
                // showing: there is simply nothing newer.
                return Outcome.none();
            }
            if (response.statusCode() != 200) {
                return Outcome.failed(UpdateCheck.ErrorKind.HTTP, String.valueOf(response.statusCode()));
            }
            String body = response.body();
            // A body past the cap is not a release payload, whatever it is. Treated as nothing
            // newer rather than as an error: the user did nothing wrong and can do nothing about it.
            if (body == null) return Outcome.none();

            ReleaseInfo latest = UpdateCheck.parseLatest(body);
            if (latest == null) return Outcome.none();
            return UpdateCheck.isNewer(currentVersion, latest.version()) ? Outcome.found(latest) : Outcome.none();
        } catch (TimeoutException e) {
            if (pending != null) pending.cancel(true); // aborts the exchange rather than leaving it reading
            return Outcome.failed(UpdateCheck.ErrorKind.TIMEOUT, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (pending != null) pending.cancel(true);
            return Outcome.failed(UpdateCheck.ErrorKind.OTHER, e.getClass().getSimpleName());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return Outcome.failed(UpdateCheck.classify(cause), cause.getClass().getSimpleName());
        } catch (Exception e) {
            // Offline, DNS failure, timeout, TLS problem. A background check that fails is not the
            // user's problem; only a check they asked for reports anything.
            return Outcome.failed(UpdateCheck.classify(e), e.getClass().getSimpleName());
        }
    }

    /**
     * The body as text once it has all arrived, or null as soon as it passes {@code max} bytes — and
     * the rest is then not read.
     *
     * <p>Not {@code BodyHandlers.ofString}, which buffers the whole body before anything can look at its
     * length, so a cap on the string bounded memory only after the fact. Not {@code ofInputStream}
     * either: that completes at the headers, and a read of a body that never comes blocks outside any
     * timeout.
     */
    static final class CappedBody implements HttpResponse.BodySubscriber<@Nullable String> {
        private final int max;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<@Nullable String> result = new CompletableFuture<>();
        private Flow.@Nullable Subscription subscription;

        CappedBody(int max) {
            this.max = max;
        }

        @Override
        public CompletionStage<@Nullable String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer item : items) {
                int n = item.remaining();
                if (bytes.size() + (long) n > max) {
                    result.complete(null);
                    Flow.Subscription s = subscription;
                    if (s != null) s.cancel();
                    return;
                }
                byte[] chunk = new byte[n];
                item.get(chunk);
                bytes.write(chunk, 0, n);
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toString(StandardCharsets.UTF_8));
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        HttpClient existing = client;
        if (existing != null) existing.close();
    }
}
