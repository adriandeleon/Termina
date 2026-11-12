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

    /** How many dialogs have been answered, so a test can wait for one shown a moment later. */
    private static final java.util.concurrent.atomic.AtomicInteger ANSWERED =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A dialog as it was when it was answered: what its buttons were and which was the default.
     *
     * @param stock the buttons left as JavaFX's own OK, Cancel, Yes, No, Apply or Close
     * @param defaults how many buttons were default buttons
     * @param danger whether a button had the danger style
     * @param cancelIsDefault whether the default button was the cancelling one
     * @param uiFont whether the scene or the pane carried the interface font's stylesheet
     * @param buttonOrder the button bar's order string
     * @param cancelLeftmost whether the cancelling button was drawn left of every other
     */
    record Seen(
            String header,
            List<String> buttons,
            List<String> stock,
            int defaults,
            boolean danger,
            boolean cancelIsDefault,
            boolean uiFont,
            String buttonOrder,
            boolean cancelLeftmost) {}

    private static final List<Seen> SEEN = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final List<ButtonType> STOCK = List.of(
            ButtonType.OK, ButtonType.CANCEL, ButtonType.YES, ButtonType.NO, ButtonType.APPLY, ButtonType.CLOSE);

    /** Every dialog answered so far, in order. */
    static List<Seen> seen() {
        return List.copyOf(SEEN);
    }

    private static Seen describe(DialogPane pane) {
        List<String> buttons = new java.util.ArrayList<>();
        List<String> stock = new java.util.ArrayList<>();
        int defaults = 0;
        boolean danger = false;
        boolean cancelIsDefault = false;
        for (ButtonType type : pane.getButtonTypes()) {
            if (!(pane.lookupButton(type) instanceof Button button)) continue;
            buttons.add(button.getText());
            if (STOCK.contains(type)) stock.add(button.getText());
            if (button.getStyleClass().contains("danger")) danger = true;
            if (button.isDefaultButton()) {
                defaults++;
                cancelIsDefault = type.getButtonData().isCancelButton();
            }
        }
        List<String> sheets = new java.util.ArrayList<>(pane.getStylesheets());
        if (pane.getScene() != null) sheets.addAll(pane.getScene().getStylesheets());
        boolean uiFont = sheets.stream().anyMatch(sheet -> sheet.endsWith("ui-font.css"));
        // Where the buttons were drawn, which is the skin's doing and what the user sees.
        pane.applyCss();
        pane.layout();
        double cancelX = Double.NaN;
        double leftmostOther = Double.POSITIVE_INFINITY;
        for (ButtonType type : pane.getButtonTypes()) {
            if (!(pane.lookupButton(type) instanceof Button button)) continue;
            double x = button.localToScene(0, 0).getX();
            if (type.getButtonData().isCancelButton()) cancelX = x;
            else leftmostOther = Math.min(leftmostOther, x);
        }
        boolean cancelLeftmost = Double.isNaN(cancelX) || cancelX < leftmostOther;
        String order = pane.lookup(".button-bar") instanceof ButtonBar bar ? bar.getButtonOrder() : "";
        return new Seen(
                String.valueOf(pane.getHeaderText()),
                buttons,
                stock,
                defaults,
                danger,
                cancelIsDefault,
                uiFont,
                order,
                cancelLeftmost);
    }

    private DialogAnswers() {}

    /** The number of dialogs answered so far. */
    static int answered() {
        return ANSWERED.get();
    }

    /**
     * @param accept the confirming button (OK, the action's own verb) when true; Cancel when false
     * @param text typed into the dialog's text field first, when it has one and this is not null
     */
    static void next(boolean accept, @Nullable String text) {
        int mine = LATEST.incrementAndGet();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        Platform.runLater(() -> answer(mine, accept, text, deadline));
    }

    private static void answer(int mine, boolean accept, @Nullable String text, long deadline) {
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
                    SEEN.add(describe(pane));
                    ANSWERED.incrementAndGet();
                    button.fire();
                    return;
                }
            }
        }
        // Not up yet: a dialog shown from a later runLater. Look again on the next pulse.
        // A question asked after a worker has looked something up — the close prompt reads the
        // process table first — comes a little later than the call that asks it: looked for again
        // until a deadline, not a count, since an idle FX thread runs a thousand re-posts in a blink.
        if (System.nanoTime() < deadline) {
            javafx.animation.PauseTransition again =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(15));
            again.setOnFinished(e -> answer(mine, accept, text, deadline));
            again.play();
        }
    }
}
