package com.termina.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;

import com.termina.i18n.Messages;
import com.termina.ui.Doctor.Check;
import com.termina.ui.Doctor.Status;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAG-07 (Pauta 6.0.1): the Doctor's window, on a machine with one tool missing: the row is there
 * with its status in words, what to install and the link to it; the checks run off the FX thread;
 * and the buttons check again and copy the rows.
 */
@Tag("fx")
class DoctorWindowFxTest {

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
        Platform.setImplicitExit(false);
    }

    private static final List<Check> ROWS = List.of(
            new Check("shell", Status.OK, "/bin/bash", null, null),
            new Check("git", Status.OPTIONAL, "", "doctor.fix.git", "https://git-scm.com/downloads"),
            new Check("selection", Status.MISSING, "", "doctor.fix.selection.x11", null));

    @Test
    void aMissingToolIsShownWithWhatToInstallAndTheButtonsCheckAgainAndCopy() throws Exception {
        List<String> opened = new ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        List<Boolean> onFxThread = new ArrayList<>();
        Stage owner = onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(), 300, 200));
            stage.show();
            return stage;
        });
        DoctorWindow doctor = onFx(() -> new DoctorWindow(opened::add, () -> {
            onFxThread.add(Platform.isFxApplicationThread());
            runs.incrementAndGet();
            return ROWS;
        }));
        try {
            assertFalse(onFx(doctor::hasRows), "nothing to copy before the first check");
            onFx(() -> {
                doctor.show(owner);
                return null;
            });
            waitFor(() -> onFx(doctor::hasRows));
            assertEquals(List.of(false), onFxThread, "the checks ran on a worker");

            List<String> words = onFx(() -> texts(doctor.listForTest()));
            assertEquals(3, onFx(() -> doctor.listForTest().getChildren().size()));
            assertTrue(words.contains(Messages.tr("doctor.status.optional")), words.toString());
            assertTrue(words.contains(Messages.tr("doctor.status.missing")), words.toString());
            assertTrue(words.contains(Messages.tr("doctor.row.selection")), words.toString());
            assertTrue(words.contains(Messages.tr("doctor.fix.selection.x11")), "what to install: " + words);
            assertTrue(words.contains("/bin/bash"), words.toString());
            assertEquals(
                    Messages.tr("doctor.summary.failed", 2),
                    onFx(() -> doctor.summaryForTest().getText()));

            // The link under the failed row goes where the row says.
            onFx(() -> {
                for (Node node : doctor.listForTest().lookupAll(".hyperlink")) ((Hyperlink) node).fire();
                return null;
            });
            assertEquals(List.of("https://git-scm.com/downloads"), opened);

            // Copy as text, then Check Again.
            String copied = onFx(() -> {
                button(doctor, "doctor.copy").fire();
                return Clipboard.getSystemClipboard().getString();
            });
            assertTrue(copied.contains(Messages.tr("doctor.row.git")), copied);
            assertTrue(copied.contains("https://git-scm.com/downloads"), copied);
            assertTrue(copied.contains(com.termina.AppInfo.NAME + " " + com.termina.AppInfo.VERSION), copied);

            onFx(() -> {
                button(doctor, "doctor.again").fire();
                return null;
            });
            waitFor(() -> runs.get() == 2);
            waitFor(() -> onFx(() -> !button(doctor, "doctor.again").isDisabled()));
            assertEquals(3, onFx(() -> doctor.listForTest().getChildren().size()), "the rows again, not twice");

            assertEquals(
                    Messages.tr("doctor.summary.ok", com.termina.AppInfo.NAME),
                    DoctorWindow.summaryOf(List.of(ROWS.getFirst())));
        } finally {
            onFx(() -> {
                doctor.scene().getWindow().hide();
                owner.hide();
                return null;
            });
        }
    }

    private static Button button(DoctorWindow doctor, String key) {
        String label = Messages.tr(key);
        String copied = Messages.tr("doctor.copied");
        for (Node node : doctor.scene().getRoot().lookupAll(".button")) {
            if (node instanceof Button b
                    && (label.equals(b.getText()) || ("doctor.copy".equals(key) && copied.equals(b.getText())))) {
                return b;
            }
        }
        throw new AssertionError("no button " + key);
    }

    private static List<String> texts(Parent parent) {
        List<String> out = new ArrayList<>();
        for (Node node : parent.lookupAll(".label")) {
            if (node instanceof Label label && label.getText() != null && !(node instanceof Hyperlink))
                out.add(label.getText());
        }
        return out;
    }

    private static void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}
