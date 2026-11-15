package com.termina.instance;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One Termina per config folder, and the hand-off to it (CLI-07, CLI-08, Pauta 2.0.2). */
class SingleInstanceTest {

    @Test
    void aRequestSurvivesTheWire() {
        SingleInstance.Request request = new SingleInstance.Request(
                "/home/x/proj", List.of("-d", "src", "-T", "build \"one\"", "-e", "make", "-j", "8"));
        assertEquals(request, SingleInstance.decode(SingleInstance.encode(request)));
    }

    @Test
    void aSecondClaimOnTheSameFolderIsRefused(@TempDir Path dir) throws Exception {
        try (SingleInstance first = SingleInstance.claim(dir)) {
            assertNotNull(first);
            assertNull(SingleInstance.claim(dir), "the folder is held");
        }
        try (SingleInstance again = SingleInstance.claim(dir)) {
            assertNotNull(again, "released when the holder closes");
        }
    }

    @Test
    void aSecondLaunchHandsItsArgumentsToTheFirst(@TempDir Path dir) throws Exception {
        LinkedBlockingQueue<SingleInstance.Request> received = new LinkedBlockingQueue<>();
        try (SingleInstance first = SingleInstance.claim(dir)) {
            assertNotNull(first);
            first.serve(received::add);
            SingleInstance.Request sent = new SingleInstance.Request("/tmp", List.of("-e", "htop"));
            assertEquals(SingleInstance.Answer.ACCEPTED, SingleInstance.handOff(dir, sent, Duration.ofSeconds(5)));
            assertEquals(sent, received.poll(5, TimeUnit.SECONDS));
        }
    }

    /** The setting that makes every launch its own: the holder says so, and keeps nothing. */
    @Test
    void aHolderSetToLeaveLaunchesAloneDeclinesThem(@TempDir Path dir) throws Exception {
        LinkedBlockingQueue<SingleInstance.Request> received = new LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicBoolean accepting = new java.util.concurrent.atomic.AtomicBoolean(false);
        try (SingleInstance first = SingleInstance.claim(dir)) {
            assertNotNull(first);
            first.serve(accepting::get, received::add);
            SingleInstance.Request sent = new SingleInstance.Request("/tmp", List.of());
            assertEquals(SingleInstance.Answer.DECLINED, SingleInstance.handOff(dir, sent, Duration.ofSeconds(5)));
            assertNull(received.poll(200, TimeUnit.MILLISECONDS), "a declined request opens nothing");
            // Asked per request: changing the setting changes the next launch, without a restart.
            accepting.set(true);
            assertEquals(SingleInstance.Answer.ACCEPTED, SingleInstance.handOff(dir, sent, Duration.ofSeconds(5)));
            assertEquals(sent, received.poll(5, TimeUnit.SECONDS));
        }
    }

    /**
     * A double click: the second launch finds the folder claimed before the first is listening —
     * it binds only once its window is up — and has to wait for it rather than start a second one.
     */
    @Test
    void aLaunchThatArrivesBeforeTheHolderListensWaitsForIt(@TempDir Path dir) throws Exception {
        LinkedBlockingQueue<SingleInstance.Request> received = new LinkedBlockingQueue<>();
        try (SingleInstance first = SingleInstance.claim(dir)) {
            assertNotNull(first);
            SingleInstance.Request sent = new SingleInstance.Request("/tmp", List.of("-T", "early"));
            var answer = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> SingleInstance.handOff(dir, sent, Duration.ofSeconds(5)));
            // Not a sleep: the hand-off is still trying when this wait runs out.
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> answer.get(400, TimeUnit.MILLISECONDS),
                    "still trying");
            first.serve(received::add);
            assertEquals(SingleInstance.Answer.ACCEPTED, answer.get(5, TimeUnit.SECONDS));
            assertEquals(sent, received.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void anUnknownReplyIsNoAnswer() {
        assertEquals(SingleInstance.Answer.ACCEPTED, SingleInstance.answerOf("ok\r"));
        assertEquals(SingleInstance.Answer.DECLINED, SingleInstance.answerOf("own"));
        assertEquals(SingleInstance.Answer.NONE, SingleInstance.answerOf(""));
        assertEquals(SingleInstance.Answer.NONE, SingleInstance.answerOf("maybe"));
    }

    /** Whoever can connect can ask for a window running any command, so nobody else may. */
    @Test
    void theSocketIsItsOwnersAlone(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews()
                .contains("posix"));
        try (SingleInstance first = SingleInstance.claim(dir)) {
            assertNotNull(first);
            first.serve(request -> {});
            assertEquals(
                    "rw-------",
                    java.nio.file.attribute.PosixFilePermissions.toString(
                            java.nio.file.Files.getPosixFilePermissions(SingleInstance.socketFor(dir))));
        }
    }

    @Test
    void aLongFolderPathPrefersTheRuntimeFolderAndOtherwiseAPrivateOneInTemp() {
        Path deep = Path.of("/tmp", "a".repeat(120), "config").toAbsolutePath();
        Path runtime = SingleInstance.socketFor(deep, "/run/user/1000", "/tmp", "adl");
        assertEquals(
                Path.of("/run/user/1000", "termina").toAbsolutePath(),
                runtime.getParent().toAbsolutePath());
        // No runtime folder: a folder of the user's own in temp, never the shared temp folder itself.
        Path temp = SingleInstance.socketFor(deep, null, "/tmp", "a d/l");
        assertEquals(Path.of("/tmp", "termina-a_d_l"), temp.getParent());
        assertEquals(runtime.getFileName(), temp.getFileName(), "the same name wherever it goes");
        // A short folder keeps its socket where it always was.
        assertEquals(
                Path.of("/tmp/c").toAbsolutePath().normalize().resolve("instance.sock"),
                SingleInstance.socketFor(Path.of("/tmp/c"), "/run/user/1000", "/tmp", "adl"));
    }

    @Test
    void aSocketInAFolderOfItsOwnStillWorks(@TempDir Path dir) throws Exception {
        // Deep enough that the socket leaves the config folder for the temp folder.
        Path deep = dir.resolve("b".repeat(110));
        LinkedBlockingQueue<SingleInstance.Request> received = new LinkedBlockingQueue<>();
        try (SingleInstance first = SingleInstance.claim(deep)) {
            assertNotNull(first);
            first.serve(received::add);
            SingleInstance.Request sent = new SingleInstance.Request("/tmp", List.of());
            assertEquals(SingleInstance.Answer.ACCEPTED, SingleInstance.handOff(deep, sent, Duration.ofSeconds(5)));
            assertEquals(sent, received.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nobodyListeningIsAnAnswerNotAHang(@TempDir Path dir) {
        long start = System.nanoTime();
        assertEquals(
                SingleInstance.Answer.NONE,
                SingleInstance.handOff(dir, new SingleInstance.Request("/", List.of()), Duration.ofSeconds(2)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos());
    }

    /** PERF-05 (Pauta 6.0.1): the blocking connect and read are never on the common pool. */
    @Test
    void theHandOffRunsOnADaemonThreadOfItsOwn() throws Exception {
        java.util.concurrent.CompletableFuture<Thread> ran = new java.util.concurrent.CompletableFuture<>();
        SingleInstance.onHandOffThread(() -> ran.complete(Thread.currentThread()));
        Thread thread = ran.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(SingleInstance.HAND_OFF_THREAD, thread.getName());
        assertTrue(thread.isDaemon());
        assertFalse(thread instanceof java.util.concurrent.ForkJoinWorkerThread);
    }

    @Test
    void aLongFolderPathGetsASocketInTheTempFolderThatBothSidesAgreeOn() {
        Path deep = Path.of("/tmp", "a".repeat(120), "config");
        Path socket = SingleInstance.socketFor(deep);
        assertTrue(socket.toString().length() <= SingleInstance.MAX_SOCKET_PATH + 40, socket.toString());
        assertTrue(socket.getFileName().toString().startsWith("termina-"));
        assertEquals(socket, SingleInstance.socketFor(deep));
        // Absolute, as the code makes it: on Windows "/tmp/c" gains the current drive.
        Path shortFolder = Path.of("/tmp/c");
        assertEquals(
                shortFolder.toAbsolutePath().normalize().resolve("instance.sock"),
                SingleInstance.socketFor(shortFolder));
    }

    @org.junit.jupiter.api.Test
    void aRequestIsReadLenientlyAndWhatIsNotOneIsSaidSo() {
        // Fields it does not know are passed over, whatever their shape.
        SingleInstance.Request read = SingleInstance.decode(
                "{\"version\": 2, \"extra\": {\"a\": [1, 2]}, \"cwd\": \"/tmp\", \"args\": [\"-T\", \"x\"]}");
        org.junit.jupiter.api.Assertions.assertEquals("/tmp", read.cwd());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("-T", "x"), read.args());
        for (String bad : new String[] {"[1]", "{\"args\": []}", "{not json", ""}) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> SingleInstance.decode(bad), bad);
        }
    }

    @org.junit.jupiter.api.Test
    void handingOffToAFolderNobodyListensOnIsNoAnswer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        org.junit.jupiter.api.Assertions.assertEquals(
                SingleInstance.Answer.NONE,
                SingleInstance.handOff(
                        dir,
                        new SingleInstance.Request(dir.toString(), java.util.List.of()),
                        java.time.Duration.ofSeconds(2)));
    }
}
