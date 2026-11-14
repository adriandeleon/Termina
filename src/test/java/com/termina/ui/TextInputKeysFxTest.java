package com.termina.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Emacs editing chords on a real text field, the palette's and the find bar's. */
@Tag("fx")
class TextInputKeysFxTest {

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

    @Test
    void theChordsEditTheFieldAsEmacsDoes() throws Exception {
        String result = onFx(() -> {
            TextField field = new TextField();
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(field), 300, 100));
            stage.show();
            TextInputKeys.install(field, false);
            field.setText("hello brave world");
            field.positionCaret(0);
            // Every chord in the table runs once, in a field with text in it.
            for (String chord : TextInputKeys.EMACS.keySet()) {
                field.setText("hello brave world");
                field.positionCaret(6);
                TextInputKeys.EMACS.get(chord).accept(field);
            }
            // Through the installed filter: C-a to the start, C-k kills the line.
            field.setText("hello brave world");
            field.positionCaret(6);
            field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.A, false, true, false, false));
            field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, true, false, false));
            String text = field.getText();
            stage.hide();
            return text;
        });
        assertEquals("", result);
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
        return result.get(10, TimeUnit.SECONDS);
    }
}
