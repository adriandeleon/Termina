package com.termina.report;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.termina.AppInfo;
import com.termina.config.Settings;

/**
 * {@code termina --report} (CLI-05, Pauta 2.0.2): Report a Problem without the window, for when the
 * window is the problem. Prints the redacted report, with the Debug Log of the last session, and
 * opens a new GitHub issue for it in the browser. Plain English, like {@code --help}; nothing is
 * uploaded.
 */
public final class ReportCommand {

    private ReportCommand() {}

    /** @return the process's exit status */
    public static int run(String configDir, PrintStream out) {
        Settings settings = new Settings(Settings.defaultFile(configDir));
        settings.load();
        ProblemReport report = ProblemReport.build(
                AppInfo.details(), settings.values(), lastSessionLog(settings.directory()), Redactor.forThisMachine());
        ProblemReport.Link link = report.link(AppInfo.GITHUB_REPO);
        out.println(report.text());
        out.println("----");
        out.println("Nothing has been sent. Read the report above; remove anything you would rather not make public.");
        if (link.pasteReport()) {
            out.println("It is too long to go into the link: copy it and paste it into the issue's "
                    + "\"Problem report\" field.");
        }
        boolean opened = open(link.url());
        out.println(opened ? "Opening a new issue in your browser:" : "Open this address to report it:");
        out.println(link.url());
        return 0;
    }

    /** The last session's Debug Log, which is what a report from outside the app has to go on. */
    static String lastSessionLog(Path configDir) {
        Path log = configDir.resolve(com.termina.ui.DebugLog.FILE_NAME);
        try {
            return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Hands the address to the platform's own opener, and does not wait for it. */
    private static boolean open(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("win")
                ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : os.contains("mac") ? List.of("open", url) : List.of("xdg-open", url);
        try {
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
