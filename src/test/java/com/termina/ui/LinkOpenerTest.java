package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinkOpenerTest {
    @Test
    void commandResolutionIsDeferredToTheLauncher() {
        List<Runnable> work = new ArrayList<>();
        AtomicBoolean resolved = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        LinkOpener opener = new LinkOpener(
                url -> {}, () -> "editor {file}", (command, missing) -> failed.set(true), work::add, argv -> {
                    resolved.set(true);
                    assertEquals(
                            List.of("editor", Path.of("file with spaces.txt").toString()), argv);
                    // Stop before launching an external editor; the failure still goes through the worker.
                    throw new IllegalStateException("probe");
                });
        opener.openFile(Path.of("file with spaces.txt"), 1, 1);
        assertFalse(resolved.get(), "the click must not resolve a login shell on its own thread");
        assertEquals(1, work.size());
        work.getFirst().run();
        assertTrue(resolved.get());
        assertTrue(failed.get());
    }
}
