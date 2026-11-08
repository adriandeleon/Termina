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
 * (WIN-05, CONF-01..04, Pauta 2.0.2).
 *
 * <p>The question names the thing; the confirming button carries the action's own verb, never OK
 * or Yes, and is styled as dangerous; Cancel is the way out, and Escape or the window's close
 * button answer it. The dialog takes the app's stylesheet and UI font: it is a scene of its own and
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
     * @param cancelIsDefault whether Enter answers Cancel, for an action that cannot be taken back
     *     and is easy to confirm by reflex — closing a running program
     * @param more shown expanded under the question: what the action will act on, when a count is
     *     not enough to decide on
     */
    record Options(boolean danger, boolean cancelIsDefault, javafx.scene.@Nullable Node more) {
        /** A removal: the danger style, Enter confirms. */
        static final Options REMOVAL = new Options(true, false, null);
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
        if (alert.getDialogPane().lookupButton(go) instanceof Button button) {
            if (options.danger()) button.getStyleClass().add(atlantafx.base.theme.Styles.DANGER);
            if (options.cancelIsDefault()) button.setDefaultButton(false);
        }
        if (options.cancelIsDefault() && alert.getDialogPane().lookupButton(cancel) instanceof Button button) {
            button.setDefaultButton(true);
        }
        if (options.more() != null) {
            alert.getDialogPane().setExpandableContent(options.more());
            alert.getDialogPane().setExpanded(true);
        }
        style(alert.getDialogPane());
        return alert.showAndWait().orElse(cancel) == go;
    }

    /** The app's stylesheet and UI font on a dialog of our own, so it matches the windows. */
    static void style(javafx.scene.control.DialogPane pane) {
        var css = Confirm.class.getResource("/com/termina/styles/app.css");
        if (css != null) pane.getStylesheets().add(css.toExternalForm());
        var font = Confirm.class.getResource("/com/termina/styles/ui-font.css");
        if (font != null) pane.getStylesheets().add(font.toExternalForm());
    }
}
