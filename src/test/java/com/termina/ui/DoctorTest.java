package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.termina.Os;
import com.termina.i18n.Messages;
import com.termina.ui.Doctor.Check;
import com.termina.ui.Doctor.Status;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAG-07 (Pauta 6.0.1): the Doctor's checks, with the machine replaced, so a missing tool, a
 * folder that cannot be written and each system's own rows are all asked from here.
 */
class DoctorTest {

    /** A machine that has what it is told it has. */
    private static final class Fake implements Doctor.Probes {
        final Set<String> tools = new HashSet<>();
        final Map<String, String> env = new HashMap<>();
        final Set<Path> executable = new HashSet<>();
        boolean writable = true;

        @Override
        public @Nullable Path userTool(String program) {
            return tools.contains(program) ? Path.of("/opt/homebrew/bin", program) : null;
        }

        @Override
        public @Nullable Path systemTool(String program) {
            return tools.contains(program) ? Path.of("/usr/bin", program) : null;
        }

        @Override
        public @Nullable String firstLine(List<String> argv) {
            return argv.getFirst().endsWith("git") ? "git version 2.47.1\n" : null;
        }

        @Override
        public boolean isExecutable(Path file) {
            return executable.contains(file);
        }

        @Override
        public boolean isWritableDirectory(Path directory) {
            return writable;
        }

        @Override
        public @Nullable String env(String name) {
            return env.get(name);
        }
    }

    private static final Path CONFIG = Path.of("/home/someone/.termina");
    private static final String BASH = onThisHost("/bin/bash");

    /**
     * A path as the system the tests run on writes it, and absolute there. The checks are asked
     * about Linux and macOS from a Windows runner too, where {@code /bin/bash} has no drive, so
     * is not absolute, and prints with backslashes.
     */
    private static String onThisHost(String path) {
        return Path.of(path).toAbsolutePath().toString();
    }

    /** What a row says of a tool the {@link Fake} found: the path it gave, as this host prints it. */
    private static String found(String path) {
        return Path.of(path).toString();
    }

    private static Fake complete() {
        Fake machine = new Fake();
        machine.tools.addAll(List.of(
                "git",
                "gh",
                "xclip",
                "wl-copy",
                "wl-paste",
                "notify-send",
                "gdbus",
                "xdg-open",
                "osascript",
                "open",
                "powershell.exe"));
        machine.executable.add(Path.of(BASH));
        machine.env.put("DISPLAY", ":0");
        return machine;
    }

    private static Check row(List<Check> rows, String id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> ids(List<Check> rows) {
        return rows.stream().map(Check::id).toList();
    }

    @Test
    void aMachineWithEverythingHasNoFailedRowAndEachSystemHasItsOwn() {
        List<Check> linux = Doctor.run(Os.Kind.LINUX, complete(), "", BASH, CONFIG);
        assertEquals(List.of("shell", "config", "git", "gh", "selection", "notifications", "opener"), ids(linux));
        assertEquals(0, Doctor.failures(linux));
        assertEquals(
                found("/opt/homebrew/bin/git") + " — git version 2.47.1",
                row(linux, "git").detail());
        assertEquals(found("/opt/homebrew/bin/gh"), row(linux, "gh").detail(), "a tool that says nothing is its path");
        for (Check row : linux) {
            assertNull(row.fixKey(), row.id());
            assertNull(row.link(), row.id());
        }

        // macOS has no selection clipboard to check; Windows opens a link without a program.
        assertEquals(
                List.of("shell", "config", "git", "gh", "notifications", "opener"),
                ids(Doctor.run(Os.Kind.MAC, complete(), "", BASH, CONFIG)));
        assertEquals(
                List.of("shell", "config", "git", "gh", "notifications"),
                ids(Doctor.run(Os.Kind.WINDOWS, complete(), "", "cmd.exe", CONFIG)));
        assertEquals(0, Doctor.failures(Doctor.run(Os.Kind.WINDOWS, complete(), "", "cmd.exe", CONFIG)));
    }

    @Test
    void aMissingToolIsARowThatSaysWhatToInstall() {
        Fake machine = complete();
        machine.tools.removeAll(List.of("git", "gh", "xclip", "notify-send", "gdbus", "xdg-open"));
        List<Check> rows = Doctor.run(Os.Kind.LINUX, machine, "", BASH, CONFIG);
        assertEquals(5, Doctor.failures(rows));

        // Sync is the only thing that wants git: not having it is said, and is not an error.
        Check git = row(rows, "git");
        assertEquals(Status.OPTIONAL, git.status());
        assertEquals("doctor.fix.git", git.fixKey());
        assertEquals("https://git-scm.com/downloads", git.link());
        assertEquals("https://cli.github.com/", row(rows, "gh").link());

        assertEquals(Status.MISSING, row(rows, "selection").status());
        assertEquals("doctor.fix.selection.x11", row(rows, "selection").fixKey());
        assertEquals("doctor.fix.notifications", row(rows, "notifications").fixKey());
        assertEquals("doctor.fix.opener", row(rows, "opener").fixKey());
    }

    @Test
    void theSelectionToolsAreTheSessionsAndNotificationsFallBackToTheBus() {
        Fake wayland = complete();
        wayland.env.clear();
        wayland.env.put("WAYLAND_DISPLAY", "wayland-0");
        assertEquals(
                found("/usr/bin/wl-copy"),
                row(Doctor.run(Os.Kind.LINUX, wayland, "", BASH, CONFIG), "selection")
                        .detail());
        wayland.tools.removeAll(List.of("wl-copy", "wl-paste", "xclip"));
        assertEquals(
                "doctor.fix.selection.wayland",
                row(Doctor.run(Os.Kind.LINUX, wayland, "", BASH, CONFIG), "selection")
                        .fixKey());

        Fake noLibnotify = complete();
        noLibnotify.tools.remove("notify-send");
        Check viaBus = row(Doctor.run(Os.Kind.LINUX, noLibnotify, "", BASH, CONFIG), "notifications");
        assertEquals(Status.OK, viaBus.status());
        assertEquals(found("/usr/bin/gdbus"), viaBus.detail());
    }

    @Test
    void aShellThatCannotRunAndAFolderThatCannotBeWrittenAreProblems() {
        Fake machine = complete();
        String fsh = onThisHost("/usr/bin/fsh");
        String fish = onThisHost("/usr/bin/fish");
        Check configured = row(Doctor.run(Os.Kind.LINUX, machine, fsh, BASH, CONFIG), "shell");
        assertEquals(Status.PROBLEM, configured.status(), "the settings name a shell that is not there");
        assertEquals(fsh, configured.detail());
        assertEquals("doctor.fix.shell", configured.fixKey());

        machine.executable.add(Path.of(fish));
        assertEquals(
                Status.OK,
                row(Doctor.run(Os.Kind.LINUX, machine, fish, fish, CONFIG), "shell")
                        .status());
        assertEquals(
                Status.PROBLEM,
                row(Doctor.run(Os.Kind.LINUX, machine, "", onThisHost("/bin/nothing"), CONFIG), "shell")
                        .status());
        assertEquals(
                Status.PROBLEM,
                row(Doctor.run(Os.Kind.LINUX, machine, "", "", CONFIG), "shell").status());

        machine.writable = false;
        Check config = row(Doctor.run(Os.Kind.LINUX, machine, "", BASH, CONFIG), "config");
        assertEquals(Status.PROBLEM, config.status());
        assertEquals(CONFIG.toString(), config.detail());
        assertEquals("doctor.fix.config", config.fixKey());
    }

    /** Every word a row can ask the catalogue for is in it. */
    @Test
    void everyNameStatusAndFixIsInTheCatalogue() {
        List<Check> every = new ArrayList<>();
        for (Os.Kind os : Os.Kind.values()) {
            every.addAll(Doctor.run(os, complete(), "", BASH, CONFIG));
            Fake bare = new Fake();
            every.addAll(Doctor.run(os, bare, "/no/shell", "", CONFIG));
            bare.env.put("WAYLAND_DISPLAY", "wayland-0");
            bare.writable = false;
            every.addAll(Doctor.run(os, bare, "", BASH, CONFIG));
        }
        Set<String> keys = new HashSet<>();
        for (Check row : every) {
            keys.add(row.nameKey());
            keys.add(row.status().key());
            if (row.fixKey() != null) keys.add(row.fixKey());
            assertEquals(
                    row.failed(), row.fixKey() != null, row.id() + ": a failed row says what to do, an OK one nothing");
        }
        assertTrue(keys.size() >= 20, keys.toString());
        for (String key : keys) assertNotEquals(key, Messages.tr(key), "not in the catalogue: " + key);
    }

    @Test
    void theReportIsALineForEachRowWithTheFixUnderAFailedOne() {
        Fake machine = complete();
        machine.tools.remove("git");
        String text = Doctor.asText(Doctor.run(Os.Kind.MAC, machine, "", BASH, CONFIG), key -> "<" + key + ">");
        String[] lines = text.split("\n");
        assertEquals("<doctor.status.ok>  <doctor.row.shell>  /bin/bash", lines[0]);
        assertEquals("<doctor.status.optional>  <doctor.row.git>", lines[2]);
        assertEquals("    <doctor.fix.git> https://git-scm.com/downloads", lines[3]);
        assertFalse(text.contains("null"));
    }
}
