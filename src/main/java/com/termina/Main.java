package com.termina;

import javafx.application.Application;

import com.termina.cli.CommandLine;
import com.termina.config.Settings;
import com.termina.instance.SingleInstance;
import org.jspecify.annotations.Nullable;

/**
 * The entry point every launcher names, and deliberately <em>not</em> an {@link Application}.
 *
 * <p>When the main class extends {@code Application}, the Java launcher starts the JavaFX toolkit
 * before {@code main} runs ({@code LauncherHelper$FXHelper}). Everything a command line can ask for
 * without a window then needs a display it never uses: with none, {@code --version},
 * {@code --help}, {@code --report} and a mistyped option all died with "Unable to open DISPLAY"
 * before a line of ours ran, and {@code --report} is the one that exists for when the window will
 * not start (CLI-01, CLI-05). So the command line is answered here, and the toolkit starts only in
 * {@link Application#launch(Class, String...)}, for a launch that opens a window.
 * {@code MainTest} fails if this class ever becomes an {@code Application}.
 */
public final class Main {

    /** How the launcher is invoked, for the usage text. */
    private static final String LAUNCH_NAME = "termina";

    private Main() {}

    public static void main(String[] args) {
        // Must be the first statement, before any AWT class can load. We require java.desktop
        // (TtyConnector's default methods reference java.awt.Dimension) but never use AWT, and on
        // macOS an initialised AWT/Java2D pipeline contends with JavaFX's Glass/Prism for the
        // single AppKit run loop — an intermittent hang rather than a clean failure.
        System.setProperty("java.awt.headless", "true");
        // Before the arguments are even read (DIAG-04, Pauta 2.0.2): anything that goes wrong
        // from here on is captured, not printed to a console a packaged app does not have. It
        // keeps records in memory only; the file beside the settings is attached once the config
        // folder is known.
        com.termina.ui.DebugLog.install();
        // Parsed before anything else starts. --version is what somebody pastes into a bug report,
        // so it must not depend on a display, a config file, or the toolkit coming up at all.
        CommandLine cli = CommandLine.parse(args);
        Integer exit = earlyExit(cli, System.out, System.err);
        if (exit != null) System.exit(exit);

        com.termina.perf.Startup.mark("main");
        // A capture or an AOT training run is a process of its own whatever the folder: handed to a
        // Termina already running, it would photograph or train nothing.
        boolean ownProcess = cli.newInstance()
                || System.getProperty("termina.capture") != null
                || System.getProperty("termina.aotTrain") != null;
        // Before the toolkit as well: a launch that only hands its arguments to the Termina
        // already running needs no display either, and should not wait for one to come up.
        if (!ownProcess) App.hold(claimOrHandOff(cli, args));
        Application.launch(App.class, args);
    }

    /**
     * What a command line that does not open a window does — an error, {@code --help},
     * {@code --report}, {@code --version} — and the process's exit status; null to go on and launch.
     */
    static @Nullable Integer earlyExit(CommandLine cli, java.io.PrintStream out, java.io.PrintStream err) {
        if (cli.error() != null) {
            err.println(AppInfo.NAME + ": " + cli.error());
            err.println("Try '" + LAUNCH_NAME + " --help' for the options.");
            return 2;
        }
        if (cli.help()) {
            out.println(CommandLine.usage(LAUNCH_NAME));
            return 0;
        }
        if (cli.report()) {
            // No window: for when the window is what will not start. CLI-05.
            return com.termina.report.ReportCommand.run(Settings.defaultFile(cli.configDir(), cli.dev()), out);
        }
        if (cli.version()) {
            // The first line stays "Termina <version>", which is what anything parsing it reads.
            AppInfo.details().forEach(out::println);
            return 0;
        }
        return null;
    }

    /**
     * One Termina per config folder (CLI-07): the first launch claims it; a later one hands its
     * arguments to the first, which opens a window for them, and exits here. When the holder does
     * not answer, this one starts on its own rather than not at all, and says why on stderr.
     *
     * @return this process's hold on the folder, for {@link App} to serve once its first window is
     *     up; null when the folder could not be claimed and this one starts anyway
     */
    static @Nullable SingleInstance claimOrHandOff(CommandLine cli, String[] args) {
        java.nio.file.Path configDir = Settings.defaultFile(cli.configDir(), cli.dev())
                .toAbsolutePath()
                .getParent();
        if (configDir == null) return null;
        SingleInstance claimed;
        try {
            claimed = SingleInstance.claim(configDir);
        } catch (java.io.IOException | RuntimeException e) {
            return null; // a folder that cannot be locked is no reason not to start
        }
        if (claimed != null) return claimed;
        var request = new SingleInstance.Request(System.getProperty("user.dir", ""), java.util.List.of(args));
        if (SingleInstance.handOff(configDir, request, java.time.Duration.ofSeconds(5))) {
            System.exit(0);
        }
        System.err.println(
                AppInfo.NAME + ": another Termina holds " + configDir + " but did not answer; starting a second one.");
        return null;
    }
}
