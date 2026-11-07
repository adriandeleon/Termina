package com.termina.report;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes out of a problem report what it should not carry into a public issue (DIAG-06, SET-15,
 * Pauta 2.0.2): the home folder's path, the user's login name, e-mail-shaped and user@host
 * addresses, credentials in URLs, and tokens. Over-redacting a word is the cheaper mistake; the
 * report is shown, editable, before anything leaves the machine.
 */
public final class Redactor {

    private final String home;
    private final String user;

    /**
     * @param home the home folder's path, replaced by {@code ~}
     * @param user the login name, replaced by {@code <user>} wherever it stands as a word
     */
    public Redactor(String home, String user) {
        this.home = home;
        this.user = user;
    }

    /** This machine's home folder and login name. */
    public static Redactor forThisMachine() {
        return new Redactor(System.getProperty("user.home", ""), System.getProperty("user.name", ""));
    }

    /** {@code scheme://user:secret@host}: the part before the host. */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\\s@]+@");

    /** Known token shapes: GitHub, GitLab, Slack, AWS. */
    private static final List<Pattern> TOKENS = List.of(
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            // A long run of hex or base64 is a key or a token far more often than anything a reader needs.
            Pattern.compile("\\b[A-Za-z0-9+/_-]{40,}={0,2}"));

    /** {@code Bearer x}, {@code token=x}, {@code password: x} and their like. */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(bearer|token|password|passwd|secret|api[_-]?key|access[_-]?key|authorization)(\\s*[=:]\\s*|\\s+)(\\S+)");

    /**
     * {@code someone@example.com}, and an ssh-style {@code user@host}. Not a stack frame's
     * {@code module@version/}, as in {@code com.termina@0.15.0/com.termina.App}: which module and
     * version a frame came from is what a report is for.
     */
    private static final Pattern ADDRESS =
            Pattern.compile("[A-Za-z0-9._%+-]+@(?!\\d+(?:\\.\\d+)*(?:-[A-Za-z0-9.]+)?/)[A-Za-z0-9.-]+");

    public String redact(String text) {
        String out = text;
        if (!home.isBlank() && home.length() > 1) {
            out = out.replace(home, "~");
            out = out.replace(home.replace('\\', '/'), "~");
            out = out.replace(home.replace('/', '\\'), "~");
        }
        out = URL_CREDENTIALS.matcher(out).replaceAll("$1<credentials>@");
        out = SECRET_ASSIGNMENT.matcher(out).replaceAll("$1$2<redacted>");
        for (Pattern token : TOKENS) out = token.matcher(out).replaceAll("<token>");
        out = ADDRESS.matcher(out)
                .replaceAll(match -> match.group().equals("<credentials>@") ? match.group() : "<address>");
        if (user.length() >= 2) {
            out = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(user) + "(?![A-Za-z0-9_])")
                    .matcher(out)
                    .replaceAll(Matcher.quoteReplacement("<user>"));
        }
        return out;
    }
}
