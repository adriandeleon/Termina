package com.termina.ui;

import java.io.File;
import java.util.List;

import com.termina.Os;
import com.termina.process.ChildPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI-10 (Pauta 6.0.1): the login shell's PATH is asked for at launch, so every program the app
 * starts later has it, and not only the ones started after a sync or a clicked link.
 */
class LoginShellPathTest {

    @Test
    void thereIsALoginShellToAskOffWindowsWhenOneIsNamed() {
        assertTrue(LoginShellPath.worthAsking(Os.Kind.LINUX, "/bin/bash"));
        assertTrue(LoginShellPath.worthAsking(Os.Kind.MAC, "/bin/zsh"));
        assertFalse(LoginShellPath.worthAsking(Os.Kind.WINDOWS, "/bin/bash"), "Windows has no login shell's PATH");
        assertFalse(LoginShellPath.worthAsking(Os.Kind.LINUX, null));
        assertFalse(LoginShellPath.worthAsking(Os.Kind.LINUX, " "));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "no login shell to ask")
    void askingPublishesAPathForEveryLaterChild() throws InterruptedException {
        Thread asker = LoginShellPath.warm();
        // Null when another test in this JVM has asked already, which published as well.
        if (asker != null) asker.join(20_000);
        if (System.getenv("SHELL") == null) return; // nothing to ask, and nothing asked
        String known = ChildPath.known();
        assertNotNull(known, "the answer is published for ChildPath.apply");
        List<String> published = List.of(known.split(File.pathSeparator));
        String inherited = System.getenv("PATH");
        if (inherited != null && !inherited.isBlank()) {
            String first = inherited.split(File.pathSeparator)[0];
            assertTrue(published.contains(first), "the inherited PATH comes first and is kept: " + known);
        }
    }

    /** The app asks once the first frame is drawn, with the other work nobody waits for. */
    @Test
    void theAppAsksAfterTheFirstFrame() throws java.io.IOException {
        String app = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/termina/App.java"))
                .replace("\r\n", "\n");
        int after = app.indexOf("com.termina.ui.FirstFrame.after(() -> {");
        int asked = app.indexOf("com.termina.ui.LoginShellPath.warmInBackground();");
        int started = app.indexOf("com.termina.ui.FirstFrame.start();");
        assertTrue(after > 0 && asked > after && started > asked, "inside the after-first-frame work");
    }
}
