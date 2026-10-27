package com.termina.theme;

import com.termina.i18n.Messages;
import org.jspecify.annotations.Nullable;

/**
 * A file that is not a colour scheme this can read, with a reason fit to show the user — in the
 * UI's language, which is why it is made from a {@link Reason} and not from a sentence.
 */
public final class ThemeImportException extends Exception {

    /** Why, as a catalogue key under {@code theme.error.}; the arguments fill its placeholders. */
    public enum Reason {
        /** {0}: the format it is not ("iTerm2", "Windows Terminal", "property list"). */
        NOT_FORMAT("notFormat"),
        /** {0}: the format whose structure is broken. */
        DAMAGED("damaged"),
        EMPTY("empty"),
        TOO_LARGE("tooLarge"),
        NO_SCHEMES("noSchemes"),
        /** {0}: the colour's name as the file would call it. */
        MISSING_COLOUR("missingColour"),
        BAD_COLOUR("badColour"),
        /** {0}: the text that was meant to be a colour. */
        NOT_A_COLOUR("notAColour"),
        UNKNOWN_FORMAT("unknownFormat");

        private final String key;

        Reason(String key) {
            this.key = key;
        }

        public String key() {
            return "theme.error." + key;
        }
    }

    private final Reason reason;

    public ThemeImportException(Reason reason, @Nullable Object... args) {
        super(Messages.tr(reason.key(), args));
        this.reason = reason;
    }

    public ThemeImportException(Throwable cause, Reason reason, @Nullable Object... args) {
        super(Messages.tr(reason.key(), args), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
