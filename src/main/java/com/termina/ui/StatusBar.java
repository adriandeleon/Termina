package com.termina.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import static com.termina.i18n.Messages.tr;

/**
 * The status bar (WIN-01, MLOG-02, Pauta 2.0.2): off by default, a row under the terminal when
 * turned on. Its echo area on the left says the latest message, red for an error and amber for a
 * warning; on the right, how many errors nobody has looked at yet. A click on either opens the
 * Message Log. Not focus-traversable, like the rest of the chrome: Tab is the shell's.
 *
 * <p>Between the two, once the update check has found a newer version, a link that names it and
 * opens its release (DIAG-09, Pauta 6.0.1). The echo area says so too, but only until the next
 * message; the link stays until it is followed or the version dismissed.
 */
final class StatusBar extends HBox {

    private final MessageLog log;
    private final Label echo;
    private final Label unread;
    private final javafx.scene.control.Hyperlink update = new javafx.scene.control.Hyperlink();
    private final Runnable refresh = this::refresh;

    StatusBar(MessageLog log, Runnable openLog) {
        this.log = log;
        // Clicked, each opens the Message Log; told to a screen reader as a button that does (ACC-02).
        echo = new PressLabel(openLog);
        unread = new PressLabel(openLog);
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setFocusTraversable(false);
        echo.getStyleClass().add("status-echo");
        echo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(echo, Priority.ALWAYS);
        unread.getStyleClass().addAll("status-unread", "state-error");
        update.getStyleClass().add("status-update");
        // Out of the Tab order with the rest of the bar; Help ▸ Version … is available is the
        // same action for the keyboard, and a command.
        update.setFocusTraversable(false);
        // In the bar only while there is a version to name: an empty link is a control with no
        // name, which is what a screen reader would be offered.
        getChildren().addAll(echo, unread);
        refresh();
    }

    /**
     * Shows the newer version the update check found, as a link to its release, or takes the link
     * down.
     *
     * @param version the version to name, or null when there is none to advertise
     * @param open opens that release's page
     */
    void showUpdate(@org.jspecify.annotations.Nullable String version, Runnable open) {
        boolean known = version != null;
        update.setText(known ? tr("status.updateAvailable", version) : "");
        update.setOnAction(e -> {
            update.setVisited(false);
            open.run();
        });
        getChildren().remove(update);
        if (known) getChildren().add(getChildren().indexOf(unread), update);
    }

    /** The update link's text, or an empty string while it is not shown: for tests. */
    String updateLinkForTest() {
        return getChildren().contains(update) ? update.getText() : "";
    }

    /** Follows the update link, as a click does: for tests. */
    void followUpdateLinkForTest() {
        update.fire();
    }

    /** Follows the log while in a scene; forgets it when the window goes. */
    void attach() {
        log.removeListener(refresh);
        log.addListener(refresh);
        refresh();
    }

    void detach() {
        log.removeListener(refresh);
    }

    private void refresh() {
        MessageLog.Entry latest = log.latest();
        echo.getStyleClass().removeAll("state-error", "state-warning");
        if (latest == null) {
            echo.setText("");
        } else {
            echo.setText(latest.text());
            switch (latest.severity()) {
                case ERROR -> echo.getStyleClass().add("state-error");
                case WARNING -> echo.getStyleClass().add("state-warning");
                case INFO -> {}
            }
        }
        int errors = log.unreadErrors();
        unread.setText(errors == 0 ? "" : tr("statusbar.unread", errors));
        unread.setVisible(errors > 0);
        unread.setManaged(errors > 0);
        String said = latest == null ? tr("statusbar.empty") : latest.text();
        echo.setAccessibleText(tr("statusbar.accessible", said));
    }

    /** What the echo area says now, for the capture hook. */
    String echoForCapture() {
        return echo.getText()
                + (echo.getStyleClass().contains("state-error") ? " [error]" : "")
                + (getChildren().contains(update) ? " [" + update.getText() + "]" : "")
                + (unread.isVisible() ? " (" + unread.getText() + ")" : "");
    }
}
