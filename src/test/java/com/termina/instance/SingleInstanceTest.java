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
            assertTrue(SingleInstance.handOff(dir, sent, Duration.ofSeconds(5)));
            assertEquals(sent, received.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nobodyListeningIsAnAnswerNotAHang(@TempDir Path dir) {
        long start = System.nanoTime();
        assertFalse(SingleInstance.handOff(dir, new SingleInstance.Request("/", List.of()), Duration.ofSeconds(2)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos());
    }

    @Test
    void aLongFolderPathGetsASocketInTheTempFolderThatBothSidesAgreeOn() {
        Path deep = Path.of("/tmp", "a".repeat(120), "config");
        Path socket = SingleInstance.socketFor(deep);
        assertTrue(socket.toString().length() <= SingleInstance.MAX_SOCKET_PATH + 40, socket.toString());
        assertTrue(socket.getFileName().toString().startsWith("termina-"));
        assertEquals(socket, SingleInstance.socketFor(deep));
        assertEquals(Path.of("/tmp/c/instance.sock"), SingleInstance.socketFor(Path.of("/tmp/c")));
    }
}
