package com.termina.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An option that only weakens security has no UI (SET-16, Pauta 3.0.0): it is a settings.json key,
 * and nothing in the interface writes it. Settings may still read it.
 */
class SecurityOptionsTest {

    /** The setters of options whose only use from a UI would be to make Termina less safe. */
    private static final List<String> NO_UI = List.of("setConfirmPaste(");

    @Test
    void noControlOrCommandWritesAWeakeningOption() throws IOException {
        List<String> found;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/termina/ui"))) {
            found = files.filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> NO_UI.stream()
                            .filter(setter -> read(p).contains(setter))
                            .map(setter -> p.getFileName() + ": " + setter))
                    .toList();
        }
        assertEquals(List.of(), found);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
