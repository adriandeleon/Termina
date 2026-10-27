package com.termina.update;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The network path against a local server that answers however the test likes. A bare socket rather
 * than {@code jdk.httpserver}, which the module the tests are patched into does not read.
 */
class UpdateServiceTest {

    private static final String RELEASE = "{\"tag_name\": \"v99.0.0\", \"html_url\": \"https://example.com/r\"}";

    /** Writes the response, status line and all, to the client. */
    private interface Responder {
        void respond(OutputStream out) throws IOException, InterruptedException;
    }

    private ServerSocket server;
    private UpdateService service;
    /** Released at the end of the test, so a responder holding a stalled response can finish. */
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() throws IOException {
        release.countDown();
        if (service != null) service.shutdown();
        if (server != null) server.close();
    }

    private UpdateService serving(Duration deadline, Responder responder) throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    BufferedReader in =
                            new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                        // the request's headers; the answer does not depend on them
                    }
                    responder.respond(socket.getOutputStream());
                } catch (IOException e) {
                    // closed at the end of the test, or the client hung up
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        accept.setDaemon(true);
        accept.start();
        service = new UpdateService("http://127.0.0.1:" + server.getLocalPort() + "/latest", deadline);
        return service;
    }

    private static Responder answer(int status, byte[] body) {
        return out -> {
            out.write(head(status, body.length));
            out.write(body);
            out.flush();
        };
    }

    private static byte[] head(int status, int length) {
        return ("HTTP/1.1 " + status + " X\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void aNewerReleaseIsFound() throws IOException {
        UpdateService s = serving(Duration.ofSeconds(10), answer(200, RELEASE.getBytes(StandardCharsets.UTF_8)));
        UpdateService.Outcome outcome = s.checkNow("1.0.0");
        assertTrue(outcome.available(), String.valueOf(outcome));
        assertEquals("99.0.0", outcome.latest().version());
    }

    @Test
    void noReleasesYetIsNothingNewer() throws IOException {
        UpdateService s = serving(Duration.ofSeconds(10), answer(404, "{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(UpdateService.Outcome.none(), s.checkNow("1.0.0"));
    }

    @Test
    void anotherStatusIsAnHttpFailure() throws IOException {
        UpdateService s = serving(Duration.ofSeconds(10), answer(403, "rate limited".getBytes(StandardCharsets.UTF_8)));
        UpdateService.Outcome outcome = s.checkNow("1.0.0");
        assertEquals(UpdateCheck.ErrorKind.HTTP, outcome.error());
        assertEquals("403", outcome.detail());
    }

    @Test
    void aBodyPastTheCapIsNotAPayload() throws IOException {
        // BodyHandlers.ofString buffered the whole response before the cap could look at it, so
        // the cap bounded nothing and the real limit was the timeout.
        byte[] large = new byte[UpdateService.MAX_BYTES + 1];
        Arrays.fill(large, (byte) ' ');
        UpdateService s = serving(Duration.ofSeconds(10), answer(200, large));
        assertEquals(UpdateService.Outcome.none(), s.checkNow("1.0.0"));
    }

    @Test
    void exactlyTheCapIsStillRead() throws IOException {
        byte[] json = RELEASE.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[UpdateService.MAX_BYTES];
        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(json, 0, padded, 0, json.length);
        UpdateService s = serving(Duration.ofSeconds(10), answer(200, padded));
        assertTrue(s.checkNow("1.0.0").available());
    }

    @Test
    void aBodyThatNeverComesEndsAtTheDeadline() throws IOException {
        // Headers, a few bytes, then nothing: HttpRequest.timeout covered only the headers, and the
        // read that followed blocked the one checking thread for good.
        UpdateService s = serving(Duration.ofMillis(800), out -> {
            out.write(head(200, 10_000));
            out.write("{\"tag".getBytes(StandardCharsets.UTF_8));
            out.flush();
            release.await(30, TimeUnit.SECONDS);
        });
        long start = System.nanoTime();
        UpdateService.Outcome outcome = s.checkNow("1.0.0");
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(UpdateCheck.ErrorKind.TIMEOUT, outcome.error(), String.valueOf(outcome));
        assertFalse(outcome.available());
        assertTrue(tookMs < 5_000, "returned after " + tookMs + " ms");
    }

    @Test
    void theCapStopsReadingAtTheFirstChunkPastIt() {
        UpdateService.CappedBody body = new UpdateService.CappedBody(4);
        List<ByteBuffer> chunk = List.of(ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8)));
        boolean[] cancelled = {false};
        body.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {}

            @Override
            public void cancel() {
                cancelled[0] = true;
            }
        });
        body.onNext(List.of(chunk.get(0).duplicate()));
        assertFalse(body.getBody().toCompletableFuture().isDone());
        body.onNext(List.of(chunk.get(0).duplicate()));
        assertTrue(cancelled[0], "the rest of the body is not read");
        assertNull(body.getBody().toCompletableFuture().join());

        UpdateService.CappedBody whole = new UpdateService.CappedBody(4);
        whole.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {}

            @Override
            public void cancel() {}
        });
        whole.onNext(List.of(ByteBuffer.wrap("abcd".getBytes(StandardCharsets.UTF_8))));
        whole.onComplete();
        assertEquals("abcd", whole.getBody().toCompletableFuture().join());
    }
}
