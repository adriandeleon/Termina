package com.termina.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/**
 * The one question asked before something the user made is removed, reset or overwritten
 * (WIN-05, CONF-01..04, Pauta 2.0.2; CONF-13, Pauta 6.0.1).
 *
 * <p>The question names the thing; the confirming button carries the action's own verb, never OK
 * or Yes, and is styled as dangerous; Cancel is the way out and the default button, and Escape or
 * the window's close button answer it. The dialog takes the app's stylesheet and UI font: it is a scene of its own and
 * would otherwise draw in the platform's default face.
 *
 * <p>Every question the app asks before acting goes through here, the ones that are not removals
 * included — a multi-line paste, closing a running program — so they share one shape. The words
 * and how each differs are a {@link Questions.Question}, built by pure functions.
 */
final class Confirm {

    private Confirm() {}

    /** Asks, and answers whether the action may go ahead. */
    static boolean ask(Window owner, Questions.Question question) {
        return ask(owner, question, null);
    }

    /**
     * What answers every question in place of the person, or null: a test's or a capture run's
     * (CONF-12, Pauta 6.0.1). While it is set no dialog is built or shown, so a UI test never
     * presses a native button and a headless run never waits on a modal one.
     */
    private static volatile java.util.function.@Nullable Predicate<Questions.Question> answerer;

    /** Answers every question with {@code answers} until given null. For tests and capture runs. */
    static void answerWith(java.util.function.@Nullable Predicate<Questions.Question> answers) {
        answerer = answers;
    }

    /**
     * {@link #ask(Window, Questions.Question)} with something shown expanded under the question:
     * what the action will act on, when a count is not enough to decide on.
     */
    static boolean ask(Window owner, Questions.Question question, javafx.scene.@Nullable Node more) {
        java.util.function.Predicate<Questions.Question> answers = answerer;
        if (answers != null) return answers.test(question);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle(question.title());
        alert.setHeaderText(question.header());
        alert.setContentText(question.detail().isBlank() ? null : question.detail());
        ButtonType go = new ButtonType(question.action(), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(go, cancel);
        if (question.danger() && alert.getDialogPane().lookupButton(go) instanceof Button button) {
            button.getStyleClass().add(atlantafx.base.theme.Styles.DANGER);
        }
        if (question.cancelDefault()) cancelIsDefault(alert.getDialogPane(), go, cancel);
        if (more != null) {
            alert.getDialogPane().setExpandableContent(more);
            alert.getDialogPane().setExpanded(true);
        }
        style(alert.getDialogPane());
        return alert.showAndWait().orElse(cancel) == go;
    }

    /**
     * Makes Cancel the one default button of a question, before it is shown: JavaFX opens a dialog
     * with the focus on its first default button, so Enter in a question that has just opened keeps
     * what the user has (CONF-13, Pauta 6.0.1).
     */
    static void cancelIsDefault(javafx.scene.control.DialogPane pane, ButtonType go, ButtonType cancel) {
        if (pane.lookupButton(go) instanceof Button button) button.setDefaultButton(false);
        if (pane.lookupButton(cancel) instanceof Button button) button.setDefaultButton(true);
    }

    /**
     * A text prompt's buttons worded from the catalog, and the app's stylesheet and font on it
     * (CONF-14, Pauta 6.0.1): the stock OK and Cancel a {@code TextInputDialog} comes with are
     * worded in the JVM's locale, not the app's. The replacements keep the stock pair's roles,
     * which is what the dialog's result reads.
     *
     * @param action the confirming button's label: the action's verb
     */
    static void prompt(javafx.scene.control.TextInputDialog dialog, String action) {
        dialog.getDialogPane()
                .getButtonTypes()
                .setAll(
                        new ButtonType(action, ButtonBar.ButtonData.OK_DONE),
                        new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        // The field has no label of its own: what the prompt asks is its name (ACC-01).
        String asks = dialog.getHeaderText() != null && !dialog.getHeaderText().isBlank()
                ? dialog.getHeaderText()
                : dialog.getContentText() != null && !dialog.getContentText().isBlank()
                        ? dialog.getContentText()
                        : dialog.getTitle();
        if (dialog.getEditor().getAccessibleText() == null) dialog.getEditor().setAccessibleText(asks);
        style(dialog.getDialogPane());
    }

    /**
     * The one order a dialog's buttons are in, on every OS (WIN-11, Pauta 6.0.1): Cancel to the
     * left of every action, the group at the right. JavaFX's own order follows the OS, which puts
     * the confirming button left of Cancel on Windows and right of it elsewhere.
     */
    static final String BUTTON_ORDER = "L_HE+FBIX_CUNYOA_R";

    /**
     * The app's stylesheet and UI font on a dialog of our own, so it matches the windows, and its
     * buttons in {@link #BUTTON_ORDER}. Every dialog the app shows goes through here.
     */
    static void style(javafx.scene.control.DialogPane pane) {
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) bar.setButtonOrder(BUTTON_ORDER);
        String css = UiZoom.sheet("app.css");
        if (!pane.getStylesheets().contains(css)) pane.getStylesheets().add(css);
        var font = Confirm.class.getResource("/com/termina/styles/ui-font.css");
        if (font != null && !pane.getStylesheets().contains(font.toExternalForm())) {
            pane.getStylesheets().add(font.toExternalForm());
        }
    }
}
