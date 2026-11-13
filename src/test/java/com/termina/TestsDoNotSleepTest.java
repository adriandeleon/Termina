package com.termina;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * QUAL-07 (Pauta 6.0.1): a test waits for the thing it needs, on a frame ({@code testing.Await}),
 * on a signal the code under test fires ({@code testing.Signal}) or on a worker's own queue, and
 * never for a number of milliseconds somebody guessed.
 */
class TestsDoNotSleepTest {

    private static final Path TESTS = Path.of("src/test/java");

    /** Spelled apart, so the rule's own search does not find this file. */
    private static final List<String> SLEEPS = List.of(
            "Thread" + ".sleep",
            "TimeUnit.SECONDS" + ".sleep",
            "TimeUnit.MILLISECONDS" + ".sleep",
            "LockSupport" + ".park");

    @Test
    void noTestSleeps() throws IOException {
        assertEquals(List.of(), sourcesWith(SLEEPS), "a test that sleeps, by any of its spellings");
    }

    /**
     * Time that has to pass because it is the input, not a wait: an echo held back for JediTerm's
     * predictor, which reads the system clock, and a child process that never answers. Each waits
     * a stated time on a latch nothing opens, and there are these two and no third.
     */
    @Test
    void onlyTheTwoThatMustLetTimePass() throws IOException {
        Pattern timedWaitOnNothing = Pattern.compile("new [\\w.]*CountDownLatch\\(1\\)\\s*\\.await\\(\\s*\\d");
        assertEquals(
                List.of("CommandOutputTest.java", "TypeAheadModelTest.java"),
                sourcesWhere(source -> timedWaitOnNothing.matcher(source).find()),
                "a timed wait on a latch nothing opens is a sleep by another name");
    }

    private static List<String> sourcesWith(List<String> needles) throws IOException {
        return sourcesWhere(source -> needles.stream().anyMatch(source::contains));
    }

    private static List<String> sourcesWhere(Predicate<String> matches) throws IOException {
        try (Stream<Path> files = Files.walk(TESTS)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> {
                        try {
                            return matches.test(Files.readString(file));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(file -> file.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }
}
