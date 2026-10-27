package com.termina.term;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * What the operating-system commands Termina handles itself mean — parsed, and nothing more.
 *
 * <p>Pure, so each can be tested on the exact strings shells and programs send, and kept apart
 * from what is done about them: the session only reads the stream, and the view decides.
 */
public final class OscSequences {

    /** Shell integration: a prompt, the command line, its output and its end. */
    public static final int SEMANTIC_PROMPT = 133;

    /** The shell's working directory, as a {@code file://} URI. */
    public static final int CURRENT_DIRECTORY = 7;

    /** A program setting the clipboard, base64. */
    public static final int CLIPBOARD = 52;

    /**
     * A notification, iTerm2's form: the payload is the message. ConEmu reuses the number for a
     * family of numbered subcommands ({@code 9;4;…} is a progress bar, {@code 9;9;…} the directory),
     * which {@link #notification} tells apart.
     */
    public static final int NOTIFY = 9;

    /** A notification, urxvt's form: {@code notify;title;body}. What foot, kitty and WezTerm also read. */
    public static final int NOTIFY_RXVT = 777;

    /** A title longer than this is cut: it is a line in a desktop bubble, not a document. */
    static final int MAX_TITLE = 128;

    static final int MAX_BODY = 1024;

    private OscSequences() {}

    /** The codes the session takes out of the stream. */
    public static boolean handled(int code) {
        return code == SEMANTIC_PROMPT
                || code == CURRENT_DIRECTORY
                || code == CLIPBOARD
                || code == NOTIFY
                || code == NOTIFY_RXVT;
    }

    /** A desktop notification a program asked for. The title may be empty; the body is not. */
    public record Notification(String title, String body) {}

    /**
     * The notification an OSC 9 or 777 asks for, cleaned: control characters out, both parts cut to
     * a length a bubble can show. Empty for anything else that arrives under those numbers — a ConEmu
     * subcommand, a urxvt extension other than {@code notify}, a message with nothing in it.
     */
    public static Optional<Notification> notification(int code, String payload) {
        if (payload == null) return Optional.empty();
        String title;
        String body;
        if (code == NOTIFY) {
            if (conEmuSubcommand(payload) >= 0) return Optional.empty();
            title = "";
            body = payload;
        } else if (code == NOTIFY_RXVT) {
            String[] parts = payload.split(";", 3);
            if (parts.length < 2 || !parts[0].equals("notify")) return Optional.empty();
            title = parts[1];
            body = parts.length > 2 ? parts[2] : "";
            // "notify;Done" is a title with nothing under it; shown as the message, not as nothing.
            if (body.isBlank()) {
                body = title;
                title = "";
            }
        } else {
            return Optional.empty();
        }
        title = clean(title, MAX_TITLE);
        body = clean(body, MAX_BODY);
        return body.isEmpty() ? Optional.empty() : Optional.of(new Notification(title, body));
    }

    /**
     * The directory an OSC {@code 9;9} names — ConEmu's, which Windows Terminal reads too and which
     * the PowerShell and cmd prompt recipes for Windows Terminal emit. The path is a Windows one, as
     * given, and may be quoted. Empty for any other OSC 9.
     */
    public static Optional<String> conEmuDirectory(String payload) {
        if (payload == null || conEmuSubcommand(payload) != 9) return Optional.empty();
        int semicolon = payload.indexOf(';');
        if (semicolon < 0) return Optional.empty();
        String path = payload.substring(semicolon + 1).strip();
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
        return path.isEmpty() ? Optional.empty() : Optional.of(path);
    }

    /**
     * The number of a ConEmu subcommand — digits alone, or digits and a semicolon — or -1 when the
     * payload is an ordinary message. "42 tests passed" is a message; "4;1;50" is a progress bar.
     */
    static int conEmuSubcommand(String payload) {
        int end = 0;
        while (end < payload.length() && end < 3 && Character.isDigit(payload.charAt(end))) end++;
        if (end == 0) return -1;
        if (end < payload.length() && payload.charAt(end) != ';') return -1;
        return Integer.parseInt(payload.substring(0, end));
    }

    /** Text fit for a bubble: no control characters, which a program could use to forge lines. */
    private static String clean(String text, int max) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), max));
        for (int i = 0; i < text.length() && out.length() < max; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                continue;
            }
            if (out.length() + Character.charCount(cp) > max) break;
            out.appendCodePoint(cp);
        }
        return out.toString().strip();
    }

    /**
     * One OSC 133 mark.
     *
     * @param kind {@code A} the prompt starts, {@code B} the command line starts, {@code C} the
     *     command's output starts, {@code D} the command has finished
     * @param exitCode for {@code D}, the status the shell reported, or null when it gave none
     */
    public record PromptMark(char kind, @Nullable Integer exitCode) {}

    /**
     * Reads {@code A}, {@code B}, {@code C} or {@code D;<status>}, ignoring the {@code key=value}
     * options some integrations append ({@code A;aid=12}, {@code D;0;aid=12}). Empty for anything
     * else, which is the forward-compatible answer to a mark this code has not heard of.
     */
    public static Optional<PromptMark> promptMark(String payload) {
        if (payload == null || payload.isEmpty()) return Optional.empty();
        char kind = payload.charAt(0);
        if (kind < 'A' || kind > 'D') return Optional.empty();
        if (payload.length() > 1 && payload.charAt(1) != ';') return Optional.empty();
        Integer exit = null;
        if (kind == 'D' && payload.length() > 2) {
            String first = payload.substring(2).split(";", 2)[0];
            try {
                exit = Integer.parseInt(first.trim());
            } catch (NumberFormatException options) {
                // "D;aid=12": no status, only options.
            }
        }
        return Optional.of(new PromptMark(kind, exit));
    }

    /**
     * The directory an OSC 7 names, if it is on this machine.
     *
     * <p>Three conditions. The URI has to parse. Its host has to be this one — a shell on the far
     * end of {@code ssh} reports its own directories, and opening a new tab "there" would mean
     * opening it in some unrelated local directory that happens to share the path. And the path
     * has to be a directory that exists, which also weeds out a remote shell whose host name
     * happens to match. A Windows path arrives as {@code /C:/Users/...} and loses the leading slash.
     *
     * @param isLocalHost whether a host name names this machine; empty and {@code localhost} are
     *     checked here
     * @param isDirectory the filesystem's answer, a parameter so a test does not need one
     */
    public static Optional<String> localDirectory(
            String payload, Predicate<String> isLocalHost, Predicate<String> isDirectory) {
        if (payload == null || !payload.regionMatches(true, 0, "file:", 0, 5)) return Optional.empty();
        URI uri;
        try {
            uri = new URI(payload);
        } catch (Exception malformed) {
            return Optional.empty();
        }
        String host = uri.getHost();
        boolean local = host == null || host.isEmpty() || host.equalsIgnoreCase("localhost") || isLocalHost.test(host);
        String path = uri.getPath();
        if (!local || path == null || path.isEmpty()) return Optional.empty();
        if (path.length() >= 3
                && path.charAt(0) == '/'
                && path.charAt(2) == ':'
                && Character.isLetter(path.charAt(1))) {
            path = path.substring(1);
        }
        return isDirectory.test(path) ? Optional.of(path) : Optional.empty();
    }

    /**
     * The text an OSC 52 would put on the clipboard.
     *
     * <p>Empty for a query ({@code ?}), which is never answered: answering would hand the clipboard —
     * passwords, tokens, whatever was last copied anywhere — to any program that asks, including one
     * on the far side of an {@code ssh}. Empty too for data that is not base64 or not UTF-8, rather
     * than putting mojibake on the clipboard. The selection letters before the semicolon ({@code c},
     * {@code p}, {@code s}) are accepted and ignored: JavaFX has one clipboard.
     */
    public static Optional<String> clipboardText(String payload) {
        if (payload == null) return Optional.empty();
        int semicolon = payload.indexOf(';');
        if (semicolon < 0) return Optional.empty();
        String data = payload.substring(semicolon + 1).strip();
        if (data.isEmpty() || data.equals("?")) return Optional.empty();
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(data);
            var decoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            String text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
            return Optional.empty();
        }
    }
}
