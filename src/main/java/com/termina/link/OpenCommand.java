package com.termina.link;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What to run to open a link.
 *
 * <p>Two routes. A URL, and a file with no command configured, go to the desktop's own opener —
 * the same thing that happens when you double-click the file in the file manager. A file with a
 * command configured goes to that command instead, which is the only way a line number can survive:
 * {@code open} and {@code xdg-open} take a file and nothing else, so "open this at line 42" cannot
 * be expressed to them at all.
 */
public final class OpenCommand {

    /** Substituted into a configured command. */
    public static final String FILE = "{file}";

    public static final String LINE = "{line}";

    public static final String COLUMN = "{column}";

    private OpenCommand() {}

    /**
     * The argv for a configured command, or an empty list when nothing is configured.
     *
     * <p><b>Split first, substitute second.</b> The template is tokenised on whitespace and each
     * placeholder is then replaced inside a token, so a file whose path contains a space stays one
     * argument. Substituting first and splitting afterwards would tear that path in half, and no
     * amount of quoting in the template could put it back together — the quoting the user would
     * have to write depends on the file they happen to click.
     *
     * <p>A missing line or column becomes 1 rather than 0 or empty: every editor understands line
     * 1, an empty substitution leaves a dangling {@code file.txt:} that many parse as a filename,
     * and 0 is a line that does not exist.
     */
    public static List<String> forTemplate(String template, Path file, int line, int column) {
        if (template == null || template.isBlank() || file == null) return List.of();
        List<String> argv = new ArrayList<>();
        for (String token : tokenize(template)) {
            argv.add(token.replace(FILE, file.toString())
                    .replace(LINE, Integer.toString(Math.max(1, line)))
                    .replace(COLUMN, Integer.toString(Math.max(1, column))));
        }
        return List.copyOf(argv);
    }

    /**
     * Splits a command template on whitespace, honouring single and double quotes.
     *
     * <p>Quotes are for the parts the user writes — a path to an application with a space in it —
     * not for the substituted values, which are handled by the split-first rule above.
     */
    static List<String> tokenize(String template) {
        // The same parser the shell profiles are stored with. Two of them would agree on every case
        // anyone thought to write down and drift on the rest, and both are reading a command line
        // the user typed into a settings field.
        return com.termina.cli.Argv.split(template);
    }

    /**
     * The desktop's own opener for a URL or a file.
     *
     * <p>The Windows form is {@code cmd /c start "" <target>} — the empty string is a title for the
     * console window {@code start} would otherwise take the target for, which turns opening a link
     * into opening a window called that link.
     */
    public static List<String> systemOpen(String osName, String target) {
        if (target == null || target.isBlank()) return List.of();
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) return List.of("open", target);
        if (os.startsWith("windows")) return List.of("cmd", "/c", "start", "", target);
        return List.of("xdg-open", target);
    }
}
