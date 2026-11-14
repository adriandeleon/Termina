package com.termina.report;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A problem report (DIAG-06, Pauta 2.0.2): the two fields of the bug report form, built here,
 * redacted, and handed to the user to read, edit and send themselves. Nothing here touches the
 * network; the issue is opened in the browser, where the user submits it or does not.
 *
 * @param environment what {@code termina --version} prints, for the form's {@code environment} field
 * @param report a summary of the settings and the end of the Debug Log, for its {@code report} field
 */
public record ProblemReport(String environment, String report) {

    /** How much of the Debug Log goes in: its end, where whatever went wrong is. */
    static final int LOG_LINES = 200;

    /**
     * Past this a prefilled link may not open: browsers and GitHub cut long URLs. The report then
     * goes by the clipboard instead, and the link carries the environment and a hint to paste.
     */
    public static final int MAX_LINK_LENGTH = 7_500;

    /** The settings worth knowing about a problem; profiles, commands and the sync address are not. */
    private static final List<String> SETTINGS_SHOWN = List.of(
            "theme",
            "font.",
            "terminal.",
            "input.",
            "ui.",
            "window.opacity",
            "window.menuBarInTitleBar",
            "updates.check",
            "sync.enabled",
            "sync.auto");

    /** Builds and redacts a report. */
    public static ProblemReport build(
            List<String> details, Map<String, String> settings, String debugLog, Redactor redactor) {
        String environment = String.join("\n", details);
        List<String> lines = new ArrayList<>();
        lines.add("Settings that differ from the defaults:");
        Map<String, String> shown = new TreeMap<>();
        settings.forEach((key, value) -> {
            if (SETTINGS_SHOWN.stream()
                    .anyMatch(prefix -> prefix.endsWith(".") ? key.startsWith(prefix) : key.equals(prefix))) {
                shown.put(key, value);
            }
        });
        if (shown.isEmpty()) lines.add("  (none)");
        shown.forEach((key, value) -> lines.add("  " + key + " = " + value));
        long bindings = settings.keySet().stream()
                .filter(key -> key.startsWith("keys."))
                .count();
        long profiles = settings.keySet().stream()
                .filter(key -> key.matches("profile\\.\\d+\\.name"))
                .count();
        lines.add("  key bindings changed: " + bindings + ", user profiles: " + profiles);
        lines.add("");
        List<String> log = debugLog.isBlank() ? List.of() : debugLog.lines().toList();
        lines.add(
                log.isEmpty()
                        ? "Debug Log: empty."
                        : "Debug Log (last " + Math.min(LOG_LINES, log.size()) + " lines):");
        lines.addAll(log.subList(Math.max(0, log.size() - LOG_LINES), log.size()));
        return new ProblemReport(redactor.redact(environment), redactor.redact(String.join("\n", lines)));
    }

    /** Both fields as one text, for the clipboard. */
    public String text() {
        return "Environment\n\n" + environment + "\n\nProblem report\n\n" + report + "\n";
    }

    /**
     * Where to open the issue. Both fields ride in the link while it stays under
     * {@link #MAX_LINK_LENGTH}; past that the form's report field carries {@code pasteHint} instead,
     * which says where the report is (DIAG-13, Pauta 6.0.1), so the user who lands on the form
     * finds the instruction in the field it is about.
     *
     * @param repository {@code owner/name}
     * @param pasteHint what the report field says when the report does not fit: paste it from the
     *     clipboard, or attach the file it was written to
     */
    public Link link(String repository, String pasteHint) {
        String base = "https://github.com/" + repository + "/issues/new?template=bug_report.yml&environment="
                + encode(environment);
        String full = base + "&report=" + encode(report);
        if (full.length() <= MAX_LINK_LENGTH) return new Link(full, false);
        String hinted = base + "&report=" + encode(pasteHint);
        // An environment the user has grown past the limit by hand leaves room for nothing else.
        return new Link(hinted.length() <= MAX_LINK_LENGTH ? hinted : base, true);
    }

    /**
     * @param url the new-issue page, prefilled
     * @param pasteReport whether the report did not fit and has to be pasted from the clipboard
     */
    public record Link(String url, boolean pasteReport) {}

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
