package com.termina.ui;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import static com.termina.i18n.Messages.tr;

/**
 * The status bar (WIN-01, MLOG-02, Pauta 2.0.2): off by default, a row under the terminal when
 * turned on. Its echo area on the left says the latest message, red for an error and amber for a
 * warning; on the right, how many errors nobody has looked at yet. A click on either opens the
 * Message Log. Not focus-traversable, like the rest of the chrome: Tab is the shell's.
 */
final class StatusBar extends HBox {

    private final MessageLog log;
    private final Label echo = new Label();
    private final Label unread = new Label();
    private final Runnable refresh = this::refresh;

    StatusBar(MessageLog log, Runnable openLog) {
        this.log = log;
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setFocusTraversable(false);
        echo.getStyleClass().add("status-echo");
        echo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(echo, Priority.ALWAYS);
        unread.getStyleClass().addAll("status-unread", "state-error");
        for (Label label : new Label[] {echo, unread}) {
            label.setCursor(Cursor.HAND);
            label.setOnMouseClicked(e -> openLog.run());
        }
        getChildren().addAll(echo, unread);
        refresh();
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
                + (unread.isVisible() ? " (" + unread.getText() + ")" : "");
    }
}
