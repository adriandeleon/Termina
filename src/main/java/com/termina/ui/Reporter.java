package com.termina.ui;

/**
 * Where a command that runs for a while says how it went: the window that ran it, which puts the
 * message in its status bar and the Message Log, or in a dialog when the bar is hidden.
 */
@FunctionalInterface
public interface Reporter {

    void report(MessageLog.Severity severity, String message);

    default void info(String message) {
        report(MessageLog.Severity.INFO, message);
    }

    default void error(String message) {
        report(MessageLog.Severity.ERROR, message);
    }
}
