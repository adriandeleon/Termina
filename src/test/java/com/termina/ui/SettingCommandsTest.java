package com.termina.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;

import com.termina.config.Settings;
import com.termina.shell.ShellProfiles;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A palette command for every setting (SET-06, Pauta 2.0.2). */
@Tag("fx") // the font list asks the toolkit which families are installed
class SettingCommandsTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
    }

    /** A host that answers every question with {@code answer} and keeps what was reported. */
    private static final class FakeHost implements SettingCommands.Host {
        final List<String> reported = new ArrayList<>();
        final ShellProfiles profiles;

        @Nullable
        String answer;

        FakeHost(Settings settings) {
            profiles = new ShellProfiles(settings, "System shell");
        }

        @Override
        public @Nullable String ask(String title, String detail, String current) {
            return answer;
        }

        @Override
        public void report(MessageLog.Severity severity, String message) {
            reported.add(message);
        }

        @Override
        public ShellProfiles profiles() {
            return profiles;
        }

        @Override
        public @Nullable SettingsSync sync() {
            return null;
        }

        @Override
        public boolean offersMenuBarInTitleBar() {
            return true;
        }
    }

    private Settings settings() {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        return settings;
    }

    @Test
    void everySettingsRowHasACommandOrSaysWhyNot() throws Exception {
        // The check by hand that SET-06 names, made by code: every row the Settings window builds.
        String source = Files.readString(Path.of("src/main/java/com/termina/ui/SettingsWindow.java"));
        Matcher rows = Pattern.compile("row\\(\\s*Category\\.\\w+,\\s*[\\w.()]+,\\s*tr\\(\"([^\"]+)\"\\)")
                .matcher(source);
        Set<String> titles = new TreeSet<>();
        while (rows.find()) titles.add(rows.group(1));
        assertTrue(titles.size() > 30, "the row pattern still finds the rows: " + titles);

        Settings settings = settings();
        Set<String> commanded = new TreeSet<>();
        for (SettingCommands.Entry entry : SettingCommands.all(settings, new FakeHost(settings))) {
            commanded.add(entry.key());
        }
        List<String> missing = new ArrayList<>();
        for (String title : titles) {
            if (!commanded.contains(title) && !SettingCommands.COVERED_ELSEWHERE.containsKey(title)) missing.add(title);
        }
        assertEquals(List.of(), missing);
        for (String key : SettingCommands.COVERED_ELSEWHERE.keySet()) {
            assertTrue(titles.contains(key), key + " is excused but is no longer a row");
        }
    }

    @Test
    void aSwitchSaysWhichWayItWillGoAndFlips() {
        Settings settings = settings();
        boolean before = settings.showScrollBar();
        SettingCommands.Entry entry =
                SettingCommands.toggle("settings.showScrollBar", settings::showScrollBar, settings::setShowScrollBar);
        assertTrue(
                entry.action().label().endsWith(before ? "turn off" : "turn on"),
                entry.action().label());
        entry.action().action().run();
        assertEquals(!before, settings.showScrollBar());
    }

    @Test
    void aNumberIsAskedForAndAnythingElseIsReportedNotStored() {
        Settings settings = settings();
        FakeHost host = new FakeHost(settings);
        SettingCommands.Entry padding = SettingCommands.number(
                "settings.padding",
                host,
                () -> String.valueOf(settings.padding()),
                value -> settings.setPadding((int) Math.round(value)));
        assertTrue(padding.action().label().endsWith("…"), padding.action().label());

        host.answer = "12";
        padding.action().action().run();
        assertEquals(12, settings.padding());

        host.answer = "wide";
        padding.action().action().run();
        assertEquals(12, settings.padding(), "not a number: unchanged");
        assertEquals(1, host.reported.size());

        host.answer = null; // cancelled
        padding.action().action().run();
        assertEquals(12, settings.padding());
        assertEquals(1, host.reported.size(), "a cancel says nothing");
    }

    @Test
    void numbersReadWithEitherDecimalMark() {
        assertEquals(13.5, SettingCommands.parseNumber("13,5"));
        assertEquals(13.5, SettingCommands.parseNumber(" 13.5 "));
        assertNull(SettingCommands.parseNumber(""));
        assertNull(SettingCommands.parseNumber("NaN"));
        assertNull(SettingCommands.parseNumber("big"));
    }

    @Test
    void syncCannotConnectWithoutARepositoryOrBeEditedWhileConnected() {
        Settings settings = settings();
        FakeHost host = new FakeHost(settings);
        for (SettingCommands.Entry entry : SettingCommands.all(settings, host)) {
            if (entry.key().equals("settings.sync.connection")) {
                assertFalse(entry.unavailable() == null, "no sync running here, so connecting is greyed");
            }
        }
        settings.setSyncEnabled(true);
        for (SettingCommands.Entry entry : SettingCommands.all(settings, host)) {
            if (entry.key().equals("settings.sync.repository") || entry.key().equals("settings.sync.branch")) {
                assertFalse(entry.unavailable() == null, entry.key() + " while connected");
            }
        }
    }
}
