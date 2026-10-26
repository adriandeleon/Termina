package com.termina.theme;

/** A file that is not a colour scheme this can read, with a reason fit to show the user. */
public final class ThemeImportException extends Exception {

    public ThemeImportException(String message) {
        super(message);
    }

    public ThemeImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
