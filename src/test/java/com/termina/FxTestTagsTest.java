package com.termina;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every test class that starts the JavaFX toolkit is tagged {@code fx} (QUAL-06, Pauta 2.0.2), so
 * {@code ./mvnw test -DexcludedGroups=fx} runs the pure suite on its own: fast, and on a machine
 * where the toolkit cannot start.
 */
class FxTestTagsTest {

    @Test
    void everyClassThatStartsTheToolkitIsTaggedFx() throws IOException {
        List<Path> starting;
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            starting = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("FxTestTagsTest.java"))
                    .filter(FxTestTagsTest::startsToolkit)
                    .toList();
        }
        assertFalse(starting.isEmpty(), "found no FX tests at all; is the scan looking in the right place?");
        List<Path> untagged =
                starting.stream().filter(p -> !read(p).contains("@Tag(\"fx\")")).toList();
        assertEquals(List.of(), untagged);
    }

    private static boolean startsToolkit(Path file) {
        String source = read(file);
        return source.contains("Platform.startup(") || source.contains("FxToolkit.");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
