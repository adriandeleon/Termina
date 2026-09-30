package com.termina.process;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandOutputTest {
    private ProcessBuilder command(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Child.class.getName(), mode);
    }

    @Test
    void capturesOutputEvenWhenTheCommandExitsNonZero() throws Exception {
        assertEquals(
                "Ubuntu\n",
                new String(
                        CommandOutput.read(command("output"), Duration.ofSeconds(10))
                                .orElseThrow(),
                        StandardCharsets.UTF_8));
    }

    @Test
    void enforcesDeadlineWhileStdoutRemainsOpen() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> assertTrue(CommandOutput.read(command("hang"), Duration.ofMillis(200))
                        .isEmpty()));
    }

    @Test
    void outputLargerThanAPipeDoesNotDeadlock() throws Exception {
        assertEquals(
                200_000,
                CommandOutput.read(command("large"), Duration.ofSeconds(10)).orElseThrow().length);
    }

    @Test
    void oversizedOutputIsRejected() throws Exception {
        assertTrue(
                CommandOutput.read(command("oversized"), Duration.ofSeconds(10)).isEmpty());
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "hang" -> Thread.sleep(30_000);
                case "large" -> System.out.write(new byte[200_000]);
                case "oversized" -> System.out.write(new byte[1_100_000]);
                default -> {
                    System.out.print("Ubuntu\n");
                    System.out.flush();
                    System.exit(1);
                }
            }
        }
    }
}
