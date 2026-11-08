package com.termina.ui;

import java.util.List;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

/**
 * Answers the next modal dialog, for tests on the FX thread. Call {@link #next} just before the call
 * that shows the dialog: {@code showAndWait} runs a nested event loop, and the answer queued here runs
 * inside it, once the dialog is up — types into its field, if it has one, and presses a button.
 */
final class DialogAnswers {

    /** Only the latest answer acts: one queued for a dialog that never came must not answer the next. */
    private static final java.util.concurrent.atomic.AtomicInteger LATEST =
            new java.util.concurrent.atomic.AtomicInteger();

    private DialogAnswers() {}

    /**
     * @param accept the confirming button (OK, the action's own verb) when true; Cancel when false
     * @param text typed into the dialog's text field first, when it has one and this is not null
     */
    static void next(boolean accept, @Nullable String text) {
        int mine = LATEST.incrementAndGet();
        Platform.runLater(() -> answer(mine, accept, text, 20));
    }

    private static void answer(int mine, boolean accept, @Nullable String text, int triesLeft) {
        if (mine != LATEST.get()) return;
        for (Window window : List.copyOf(Window.getWindows())) {
            if (!window.isShowing() || window.getScene() == null) continue;
            if (!(window.getScene().getRoot() instanceof DialogPane pane)) continue;
            if (text != null) {
                javafx.scene.Node field = pane.lookup(".text-field");
                if (field instanceof TextField input) input.setText(text);
            }
            for (ButtonType type : pane.getButtonTypes()) {
                ButtonBar.ButtonData data = type.getButtonData();
                boolean cancel = data == ButtonBar.ButtonData.CANCEL_CLOSE || data.isCancelButton();
                if (cancel != accept && pane.lookupButton(type) instanceof Button button) {
                    button.fire();
                    return;
                }
            }
        }
        // Not up yet: a dialog shown from a later runLater. Look again on the next pulse.
        if (triesLeft > 0) Platform.runLater(() -> answer(mine, accept, text, triesLeft - 1));
    }
}
