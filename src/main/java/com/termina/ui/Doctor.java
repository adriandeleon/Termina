package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.termina.Os;
import org.jspecify.annotations.Nullable;

/**
 * The Doctor's checks (DIAG-07, Pauta 6.0.1): the programs Termina starts and the places it writes,
 * each with a status and, where something is missing, what to install and where from.
 *
 * <p>What the checks ask of the machine is a parameter ({@link Probes}), so every system's rows
 * are tested from any one of them, a missing tool included. Nothing here draws or reads the
 * catalogue: a row is keys and facts, and {@link DoctorWindow} words it.
 */
final class Doctor {

    private Doctor() {}

    /** How a row came out. */
    enum Status {
        /** There, and usable. */
        OK,
        /** Not there, and a feature is off without it. */
        MISSING,
        /** Not there, and only an optional feature would use it. */
        OPTIONAL,
        /** There, and not usable: a folder that cannot be written, a shell that cannot run. */
        PROBLEM;

        /** The catalogue key of the word shown beside the row. */
        String key() {
            return "doctor.status." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One row.
     *
     * @param id which check: the catalogue key of its name is {@code doctor.row.<id>}
     * @param status how it came out
     * @param detail the fact found: a path, with a version where the tool gives one; empty for none
     * @param fixKey the catalogue key of what to do about it, or null when nothing is wrong
     * @param link where to get the missing thing, or null
     */
    record Check(
            String id,
            Status status,
            String detail,
            @Nullable String fixKey,
            @Nullable String link) {

        String nameKey() {
            return "doctor.row." + id;
        }

        boolean failed() {
            return status != Status.OK;
        }
    }

    /** What the checks ask of the machine. */
    interface Probes {
        /** A program the user installed, found the way Termina finds it: on the login shell's PATH. */
        @Nullable
        Path userTool(String program);

        /** A program the system provides, found on the inherited PATH and the system's directories. */
        @Nullable
        Path systemTool(String program);

        /** The first line a command prints, for a version; null when it cannot be run. */
        @Nullable
        String firstLine(List<String> argv);

        boolean isExecutable(Path file);

        boolean isWritableDirectory(Path directory);

        /** An environment variable, or null. */
        @Nullable
        String env(String name);
    }

    /**
     * Every row for a system.
     *
     * @param configured the shell the settings name, or blank for the user's own
     * @param started the program Termina starts in a new tab
     * @param configDir where the settings are kept
     */
    static List<Check> run(Os.Kind os, Probes probes, String configured, String started, Path configDir) {
        List<Check> rows = new ArrayList<>();
        rows.add(shell(probes, configured, started));
        rows.add(configFolder(probes, configDir));
        rows.add(userTool(probes, "git", Status.OPTIONAL, "doctor.fix.git", "https://git-scm.com/downloads"));
        rows.add(userTool(probes, "gh", Status.OPTIONAL, "doctor.fix.gh", "https://cli.github.com/"));
        switch (os) {
            case LINUX, OTHER -> {
                rows.add(selection(probes));
                rows.add(notificationsOnLinux(probes));
                rows.add(systemTool(probes, "opener", "xdg-open", "doctor.fix.opener"));
            }
            case MAC -> {
                rows.add(systemTool(probes, "notifications", "osascript", "doctor.fix.notifications.mac"));
                rows.add(systemTool(probes, "opener", "open", "doctor.fix.opener.mac"));
            }
            case WINDOWS ->
                rows.add(systemTool(probes, "notifications", "powershell.exe", "doctor.fix.notifications.windows"));
        }
        return List.copyOf(rows);
    }

    /** How many rows are not OK: the window's summary, and what a test asks first. */
    static long failures(List<Check> rows) {
        return rows.stream().filter(Check::failed).count();
    }

    /**
     * The shell. A configured one that cannot be run is a problem even though a tab still opens:
     * Termina starts the system's instead, which is not what the settings say.
     */
    private static Check shell(Probes probes, String configured, String started) {
        if (!configured.isBlank() && !probes.isExecutable(Path.of(configured))) {
            return new Check("shell", Status.PROBLEM, configured, "doctor.fix.shell", null);
        }
        if (started.isBlank()) return new Check("shell", Status.PROBLEM, "", "doctor.fix.shell", null);
        // A bare name is one the system finds for itself (cmd.exe): there is no file to ask about.
        Path program = Path.of(started);
        boolean runs = !program.isAbsolute() || probes.isExecutable(program);
        return new Check("shell", runs ? Status.OK : Status.PROBLEM, started, runs ? null : "doctor.fix.shell", null);
    }

    private static Check configFolder(Probes probes, Path configDir) {
        boolean writable = probes.isWritableDirectory(configDir);
        return new Check(
                "config",
                writable ? Status.OK : Status.PROBLEM,
                configDir.toString(),
                writable ? null : "doctor.fix.config",
                null);
    }

    /** A tool the user installs, with its version; missing is {@code without}. */
    private static Check userTool(Probes probes, String program, Status without, String fixKey, String link) {
        Path found = probes.userTool(program);
        if (found == null) return new Check(program, without, "", fixKey, link);
        String version = probes.firstLine(List.of(found.toString(), "--version"));
        return new Check(program, Status.OK, withVersion(found, version), null, null);
    }

    private static Check systemTool(Probes probes, String id, String program, String fixKey) {
        Path found = probes.systemTool(program);
        return found == null
                ? new Check(id, Status.MISSING, "", fixKey, null)
                : new Check(id, Status.OK, found.toString(), null, null);
    }

    /**
     * The selection clipboard, which JavaFX does not reach: the tools {@link PrimarySelection}
     * would use in this session, asked the way it asks.
     */
    private static Check selection(Probes probes) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        for (String name : List.of("WAYLAND_DISPLAY", "DISPLAY")) {
            String value = probes.env(name);
            if (value != null) env.put(name, value);
        }
        PrimarySelection.Tools tools = PrimarySelection.choose(env, probes::systemTool);
        if (tools != null)
            return new Check("selection", Status.OK, tools.write().getFirst(), null, null);
        String wayland = env.get("WAYLAND_DISPLAY");
        boolean onWayland = wayland != null && !wayland.isBlank();
        return new Check(
                "selection",
                Status.MISSING,
                "",
                onWayland ? "doctor.fix.selection.wayland" : "doctor.fix.selection.x11",
                null);
    }

    /** {@code notify-send}, or the session bus through {@code gdbus} where libnotify's tool is absent. */
    private static Check notificationsOnLinux(Probes probes) {
        Path notifySend = probes.systemTool("notify-send");
        if (notifySend != null) return new Check("notifications", Status.OK, notifySend.toString(), null, null);
        Path gdbus = probes.systemTool("gdbus");
        if (gdbus != null) return new Check("notifications", Status.OK, gdbus.toString(), null, null);
        return new Check("notifications", Status.MISSING, "", "doctor.fix.notifications", null);
    }

    /** "/usr/bin/git — git version 2.47.1"; the path alone when the tool said nothing. */
    static String withVersion(Path found, @Nullable String version) {
        return version == null || version.isBlank() ? found.toString() : found + " — " + version.strip();
    }

    /**
     * The rows as plain text, for a bug report: one line a row, the status spelled out, and under
     * a failed one what to do. The words are the caller's, so this stays free of the catalogue.
     */
    static String asText(List<Check> rows, java.util.function.Function<String, String> words) {
        StringBuilder out = new StringBuilder();
        for (Check row : rows) {
            out.append(words.apply(row.status().key())).append("  ").append(words.apply(row.nameKey()));
            if (!row.detail().isEmpty()) out.append("  ").append(row.detail());
            out.append('\n');
            if (row.fixKey() != null) {
                out.append("    ").append(words.apply(row.fixKey()));
                if (row.link() != null) out.append(" ").append(row.link());
                out.append('\n');
            }
        }
        return out.toString();
    }
}
