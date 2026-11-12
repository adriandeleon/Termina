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
 * included — a multi-line paste, closing a running program — so they share one shape: {@link
 * Options} says how each differs.
 */
final class Confirm {

    private Confirm() {}

    /**
     * Asks, and answers whether the action may go ahead.
     *
     * @param owner the window the question is about
     * @param title the dialog's title, usually the command's own name
     * @param question names the thing and what happens to it
     * @param detail what it means, or an empty string for none
     * @param action the confirming button's label: the action's verb
     */
    static boolean ask(Window owner, String title, String question, String detail, String action) {
        return ask(owner, title, question, detail, action, Options.REMOVAL);
    }

    /**
     * How a question differs from a removal's.
     *
     * @param danger whether the confirming button has the danger style: the action ends or
     *     destroys something (CONF-03)
     * @param cancelIsDefault whether Enter answers Cancel. A dangerous question's always does,
     *     whatever is said here (CONF-13, Pauta 6.0.1): the red button runs only from itself
     * @param more shown expanded under the question: what the action will act on, when a count is
     *     not enough to decide on
     */
    record Options(boolean danger, boolean cancelIsDefault, javafx.scene.@Nullable Node more) {
        /** A removal: the danger style, and Enter keeps what the user has. */
        static final Options REMOVAL = new Options(true, true, null);

        /** Whether Cancel is the default button: asked for, or the action is dangerous. */
        boolean cancelDefault() {
            return danger || cancelIsDefault;
        }
    }

    /** {@link #ask(Window, String, String, String, String)}, shaped by {@code options}. */
    static boolean ask(Window owner, String title, String question, String detail, String action, Options options) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(question);
        alert.setContentText(detail.isBlank() ? null : detail);
        ButtonType go = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(go, cancel);
        if (options.danger() && alert.getDialogPane().lookupButton(go) instanceof Button button) {
            button.getStyleClass().add(atlantafx.base.theme.Styles.DANGER);
        }
        if (options.cancelDefault()) cancelIsDefault(alert.getDialogPane(), go, cancel);
        if (options.more() != null) {
            alert.getDialogPane().setExpandableContent(options.more());
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
        style(dialog.getDialogPane());
    }

    /** The app's stylesheet and UI font on a dialog of our own, so it matches the windows. */
    static void style(javafx.scene.control.DialogPane pane) {
        var css = Confirm.class.getResource("/com/termina/styles/app.css");
        if (css != null && !pane.getStylesheets().contains(css.toExternalForm())) {
            pane.getStylesheets().add(css.toExternalForm());
        }
        var font = Confirm.class.getResource("/com/termina/styles/ui-font.css");
        if (font != null && !pane.getStylesheets().contains(font.toExternalForm())) {
            pane.getStylesheets().add(font.toExternalForm());
        }
    }
}
