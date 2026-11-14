package com.termina.report;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.termina.AppInfo;
import com.termina.config.Settings;
import org.jspecify.annotations.Nullable;

/**
 * {@code termina --report} (CLI-05, Pauta 6.0.1): Report a Problem without the window, for when the
 * window is the problem. Writes the redacted report, with the Debug Log of the last session, to a
 * file in the current folder, prints the file's path and opens a new GitHub issue for it in the
 * browser. Plain English, like {@code --help}; nothing is uploaded.
 */
public final class ReportCommand {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ReportCommand() {}

    /**
     * @param settingsFile the settings file the launch resolved, which names the folder to report on
     * @return the process's exit status
     */
    public static int run(Path settingsFile, PrintStream out) {
        return run(settingsFile, Path.of(""), out, ReportCommand::open);
    }

    /**
     * As above, writing into {@code outDir} and handing the issue's address to {@code opener}, which
     * says whether it opened.
     */
    static int run(Path settingsFile, Path outDir, PrintStream out, java.util.function.Predicate<String> opener) {
        Settings settings = new Settings(settingsFile);
        settings.load();
        ProblemReport report = ProblemReport.build(
                AppInfo.details(), settings.values(), lastSessionLog(settings.directory()), Redactor.forThisMachine());
        Path file = write(report.text(), outDir, fileName(LocalDateTime.now()));
        if (file == null) {
            out.println("Could not write the problem report to " + outDir.toAbsolutePath() + " or the temp folder.");
            return 1;
        }
        ProblemReport.Link link = report.link(
                AppInfo.GITHUB_REPO,
                "Attach " + file.getFileName() + " here (drag it into this box), or paste its contents.");
        out.println("Wrote the problem report to " + file);
        out.println("Nothing has been sent. Read the file; remove anything you would rather not make public.");
        if (link.pasteReport()) {
            out.println("It is too long to go into the link: attach the file to the issue, or paste its "
                    + "contents into the \"Problem report\" field.");
        }
        boolean opened = opener.test(link.url());
        out.println(opened ? "Opening a new issue in your browser:" : "Open this address to report it:");
        out.println(link.url());
        return 0;
    }

    /** {@code termina-report-20261112-153000.txt}: sorts by time, and a second report replaces none. */
    static String fileName(LocalDateTime now) {
        return "termina-report-" + STAMP.format(now) + ".txt";
    }

    /**
     * Writes {@code text} to {@code dir/name}, or to the temp folder where that one cannot be written
     * (a read-only folder, a launch from {@code /}).
     *
     * @return the file written, or null when neither folder took it
     */
    static @Nullable Path write(String text, Path dir, String name) {
        return write(text, List.of(dir, Path.of(System.getProperty("java.io.tmpdir", "."))), name);
    }

    /** {@link #write(String, Path, String)} over the folders to try, in order. */
    static @Nullable Path write(String text, List<Path> folders, String name) {
        for (Path folder : folders) {
            try {
                return Files.writeString(folder.toAbsolutePath().resolve(name), text, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                // The next folder.
            }
        }
        return null;
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
        // The same command a clicked link runs: one place says how each system opens an address.
        List<String> command = com.termina.link.OpenCommand.systemOpen(com.termina.Os.name(), url);
        if (command.isEmpty()) return false;
        try {
            com.termina.process.ChildPath.apply(new ProcessBuilder(command))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
