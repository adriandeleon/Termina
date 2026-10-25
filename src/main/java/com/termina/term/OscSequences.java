package com.termina.term;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Predicate;

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

    private OscSequences() {}

    /** The codes the session takes out of the stream. */
    public static boolean handled(int code) {
        return code == SEMANTIC_PROMPT || code == CURRENT_DIRECTORY || code == CLIPBOARD;
    }

    /**
     * One OSC 133 mark.
     *
     * @param kind {@code A} the prompt starts, {@code B} the command line starts, {@code C} the
     *     command's output starts, {@code D} the command has finished
     * @param exitCode for {@code D}, the status the shell reported, or null when it gave none
     */
    public record PromptMark(char kind, Integer exitCode) {}

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
