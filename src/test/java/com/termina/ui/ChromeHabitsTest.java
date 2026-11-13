package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Habits the source holds to, read from the source (Pauta 6.0.1): every tooltip from one factory
 * (TBAR-14), every thread named (PERF-04), motion through one helper (ACC-04), and "cannot be
 * undone" said where it is true (CONF-10).
 */
class ChromeHabitsTest {

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> filesWith(String needle, String... except) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path file : sources()) {
            String name = file.getFileName().toString();
            if (List.of(except).contains(name)) continue;
            if (Files.readString(file).contains(needle)) out.add(name);
        }
        return out;
    }

    @Test
    void everyTooltipComesFromTheOneFactoryWithItsDelay() throws IOException {
        assertEquals(List.of(), filesWith("new Tooltip(", "IconNames.java"));
        assertEquals(List.of(), filesWith("setShowDelay(", "IconNames.java"));
        assertEquals(400, IconNames.SHOW_DELAY.toMillis(), 0.001);
    }

    @Test
    void everyThreadHasAName() throws IOException {
        // A virtual thread from the stock executor has none: TerminalExecutors.virtual names them.
        assertEquals(List.of(), filesWith("newVirtualThreadPerTaskExecutor"));
        try (var pool = com.termina.term.TerminalExecutors.virtual("termina-test")) {
            String name = pool.submit(() -> Thread.currentThread().getName()).get();
            assertTrue(name.startsWith("termina-test-"), name);
            assertTrue(pool.submit(() -> Thread.currentThread().isVirtual()).get());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void motionPlaysUnlessTheSystemAsksForLess() throws IOException {
        assertTrue(Motion.plays(false));
        assertFalse(Motion.plays(true));
        // The visual bell, the one thing in the chrome that flashes, starts through the helper.
        String view = Files.readString(Path.of("src/main/java/com/termina/ui/TerminalView.java"));
        assertTrue(view.contains("Motion.play(bellClear)"), "the bell's flash goes through Motion");
        assertFalse(view.contains("bellClear.playFromStart()"));
    }

    @Test
    void aChordsModifiersReadTheSameInEveryLanguage() throws IOException {
        // INTL-13 (Pauta 6.0.1): Ctrl, Alt and Shift are never translated.
        List<String> translated = new ArrayList<>();
        java.util.regex.Pattern modifier =
                java.util.regex.Pattern.compile("(?U)\\b(Strg|Umschalt|Maj|Mayús|Maiusc|Mayus|Majuscule)\\b");
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/com/termina/i18n"))) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".properties")).toList()) {
                Properties catalog = new Properties();
                try (var in = Files.newBufferedReader(file)) {
                    catalog.load(in);
                }
                for (String key : catalog.stringPropertyNames()) {
                    if (modifier.matcher(catalog.getProperty(key)).find()) {
                        translated.add(file.getFileName() + " " + key);
                    }
                }
            }
        }
        assertEquals(List.of(), translated);
    }

    @Test
    void theMessageLogCopiesOnThePlatformsChordAndOnTheBoundOnes() {
        // MLOG-04 (Pauta 6.0.1): C with the shortcut modifier always, and the chords the bindings
        // give Copy and the log's own Copy; MLOG-03: no fixed Ctrl+G left to close it.
        javafx.scene.input.KeyCombination bound = javafx.scene.input.KeyCombination.valueOf("Alt+W");
        javafx.scene.input.KeyCombination own = javafx.scene.input.KeyCombination.valueOf("Ctrl+Shift+Y");
        boolean mac = com.termina.Os.isMac();
        assertTrue(MessageLogWindow.copies(key(javafx.scene.input.KeyCode.C, false, !mac, false, mac), null, null));
        assertTrue(MessageLogWindow.copies(key(javafx.scene.input.KeyCode.W, false, false, true, false), bound, null));
        assertTrue(MessageLogWindow.copies(key(javafx.scene.input.KeyCode.Y, true, true, false, false), null, own));
        assertFalse(MessageLogWindow.copies(key(javafx.scene.input.KeyCode.W, false, false, true, false), null, null));
        assertFalse(MessageLogWindow.copies(key(javafx.scene.input.KeyCode.C, false, false, false, false), bound, own));
    }

    private static javafx.scene.input.KeyEvent key(
            javafx.scene.input.KeyCode code, boolean shift, boolean control, boolean alt, boolean meta) {
        return new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, meta);
    }

    @Test
    void theMessageLogHasNoFixedCancelChord() throws IOException {
        String log = Files.readString(Path.of("src/main/java/com/termina/ui/MessageLogWindow.java"));
        assertFalse(log.contains("KeyCode.G"), "a fixed Ctrl+G, read from no key table");
        assertTrue(log.contains("closeOnEscape"));
    }

    @Test
    void anIdentifierIsNeverDigitGrouped() throws IOException {
        // INTL-10 (Pauta 6.0.1): a tab's or a profile's number is an identifier, written {0,number,#}.
        List<String> bare = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/com/termina/i18n"))) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".properties")).toList()) {
                Properties catalog = new Properties();
                try (var in = Files.newBufferedReader(file)) {
                    catalog.load(in);
                }
                for (String key : List.of(
                        "palette.goToTab",
                        "palette.movePaneToTab",
                        "palette.disabled.noSuchTab",
                        "palette.disabled.noSuchProfile",
                        "statusbar.unread")) {
                    String value = catalog.getProperty(key);
                    if (value == null || !value.contains("{0,number,#}")) {
                        bare.add(file.getFileName() + " " + key);
                        continue;
                    }
                    String written = java.text.MessageFormat.format(value, 12345, "x");
                    if (!written.contains("12345")) bare.add(file.getFileName() + " " + key + " groups its digits");
                }
            }
        }
        assertEquals(List.of(), bare);
    }

    @Test
    void theKeyboardReachesTheMenuBarWhereThereIsOneToReach() {
        // MENU-17 (Pauta 6.0.1): a command, since a plain F10 belongs to the program in the terminal.
        assertEquals(null, WindowRules.focusMenuBarUnavailableKey(false, true));
        assertEquals("palette.disabled.menuBarHidden", WindowRules.focusMenuBarUnavailableKey(false, false));
        assertEquals("settings.showMenuBar.mac", WindowRules.focusMenuBarUnavailableKey(true, true));
        assertTrue(Keymap.defaultFor("focusMenuBar") != null, "bound by default");
    }

    @Test
    void theMenusCardAndTheTopBandAreTheGuidelines() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/com/termina/styles/app.css"))
                .replaceAll("(?s)/\\*.*?\\*/", "");
        // MENU-18 (Pauta 6.0.1): a radius of 8, and a shadow of radius 16 at offset Y 16.
        String menu = rule(css, ".context-menu");
        assertTrue(menu.contains("-fx-background-radius: 8px"), menu);
        java.util.regex.Matcher shadow = java.util.regex.Pattern.compile(
                        "dropshadow\\([^,]+,[^,]+,\\s*(\\d+),[^,]+,\\s*(\\d+),\\s*(\\d+)\\)")
                .matcher(menu);
        assertTrue(shadow.find(), menu);
        assertEquals("16", shadow.group(1), "the shadow's radius");
        assertTrue(
                Integer.parseInt(shadow.group(3)) >= Integer.parseInt(shadow.group(1)),
                "offset Y no smaller than the radius");
        // TBAR-06: the menu bar, the title bar that holds it and the tab strip share one ground, with
        // no hairline between them.
        for (String selector : List.of(".menu-bar", ".header-bar")) {
            String bar = rule(css, selector);
            assertTrue(bar.contains("-fx-background-color: -color-bg-subtle;"), selector + ": " + bar);
            assertFalse(bar.contains("-color-border"), selector + " draws a line");
        }
        assertTrue(rule(css, ".tab-pane > .tab-header-area > .tab-header-background")
                .contains("-color-bg-subtle"));
    }

    /** The body of the first rule whose selector is exactly {@code selector}. */
    private static String rule(String css, String selector) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?m)^" + java.util.regex.Pattern.quote(selector) + "\\s*\\{([^}]*)\\}")
                .matcher(css);
        assertTrue(m.find(), "no rule for " + selector);
        return m.group(1);
    }

    @Test
    void noLiteralReachesAControlsText() throws IOException {
        // INTL-07 (Pauta 6.0.1): every string a control shows comes from the catalog, through
        // Messages.tr. A literal with a letter or a digit in a text position fails; a lone space
        // that holds a row's height, and the letter a font is measured with, are not text.
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile(
                "(?:new (?:Label|Button|ToggleButton|Hyperlink|MenuItem|CheckMenuItem|CheckBox|RadioButton|Tooltip|Text|Tab|TitledPane)"
                        + "|\\.set(?:Text|PromptText|Title|HeaderText|ContentText|AccessibleText))\\(\"([^\"]*)\"");
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            String name = file.getFileName().toString();
            // The development capture harness prints to a console and shows nothing to a user.
            if (name.equals("DevCapture.java")) continue;
            int number = 0;
            for (String line : Files.readAllLines(file)) {
                number++;
                java.util.regex.Matcher m = literal.matcher(line);
                while (m.find()) {
                    String text = m.group(1);
                    if (!text.matches(".*[\\p{L}\\p{N}].*")) continue;
                    if (line.contains("Text probe = new Text(")) continue; // a measurement, never shown
                    found.add(name + ":" + number + " \"" + text + "\"");
                }
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    void aDocCommentSitsOnItsMember() throws IOException {
        // DOCS-15 (Pauta 6.0.1): a doc comment above another doc comment documents nothing. It
        // happens when a member is put between a comment and what it was written for.
        java.util.regex.Pattern stranded = java.util.regex.Pattern.compile(
                "/\\*\\*(?:(?!\\*/).)*\\*/(?:\\s|//[^\\n]*\\n)*(?=/\\*\\*)", java.util.regex.Pattern.DOTALL);
        List<String> found = new ArrayList<>();
        List<Path> all = new ArrayList<>(sources());
        try (Stream<Path> tests = Files.walk(Path.of("src/test/java"))) {
            all.addAll(tests.filter(file -> file.toString().endsWith(".java")).toList());
        }
        for (Path file : all) {
            String text = Files.readString(file);
            java.util.regex.Matcher m = stranded.matcher(text);
            while (m.find()) {
                long line = text.substring(0, m.start())
                                .chars()
                                .filter(c -> c == '\n')
                                .count()
                        + 1;
                found.add(file.getFileName() + ":" + line);
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    void aMessageWithACountIsWordedForTheCount() throws IOException {
        // INTL-12 (Pauta 6.0.1): a choice limb for each form the language needs, never "file(s)",
        // and every choice pattern parses and reads right for nothing, one, two and many.
        List<String> wrong = new ArrayList<>();
        List<String> counted = List.of(
                "statusbar.unread",
                "config.part.PREFERENCES",
                "config.part.PROFILES",
                "config.part.THEMES",
                "pauta.count.pending",
                "pauta.count.deviation",
                "pauta.count.appOnly",
                "pauta.count.notApplicable");
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/com/termina/i18n"))) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".properties")).toList()) {
                Properties catalog = new Properties();
                try (var in = Files.newBufferedReader(file)) {
                    catalog.load(in);
                }
                for (String key : catalog.stringPropertyNames()) {
                    String value = catalog.getProperty(key);
                    if (value.contains("{") && value.contains("(s)"))
                        wrong.add(file.getFileName() + " " + key + " (s)");
                    if (counted.contains(key) && !value.contains(",choice,")) {
                        wrong.add(file.getFileName() + " " + key + " has no choice");
                    }
                    if (!value.contains(",choice,")) continue;
                    for (int count : new int[] {0, 1, 2, 5, 1234}) {
                        try {
                            String said = java.text.MessageFormat.format(value, count, count, count);
                            if (said.contains("{") || said.contains("|") || said.contains("#")) {
                                wrong.add(file.getFileName() + " " + key + " at " + count + ": " + said);
                            }
                            // One of something never reads as several, and several never as one.
                            if (counted.contains(key) && count == 1 && !said.contains("1")) {
                                wrong.add(file.getFileName() + " " + key + " loses its one: " + said);
                            }
                            if (counted.contains(key) && count == 5 && !said.contains("5")) {
                                wrong.add(file.getFileName() + " " + key + " loses its five: " + said);
                            }
                        } catch (IllegalArgumentException e) {
                            wrong.add(file.getFileName() + " " + key + " does not parse: " + e.getMessage());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), wrong);
        // And in English, the two forms as a person would write them.
        Properties english = new Properties();
        try (var in = Files.newBufferedReader(Path.of("src/main/resources/com/termina/i18n/messages.properties"))) {
            english.load(in);
        }
        assertEquals("1 unread error", java.text.MessageFormat.format(english.getProperty("statusbar.unread"), 1));
        assertEquals("3 unread errors", java.text.MessageFormat.format(english.getProperty("statusbar.unread"), 3));
        assertEquals(
                "Imported themes (1 file)",
                java.text.MessageFormat.format(english.getProperty("config.part.THEMES"), 1));
        assertEquals(
                "Imported themes (0 files)",
                java.text.MessageFormat.format(english.getProperty("config.part.THEMES"), 0));
    }

    @Test
    void aSyncThatFailsOnItsOwnIsToldOnce() {
        // SET-21 (Pauta 6.0.1): a failure of a run nobody asked for reaches the Message Log, and a
        // repository that stays unreachable is one message, not one each interval.
        var ok = new com.termina.sync.SyncReport(
                com.termina.sync.SyncReport.Status.OK, List.of(), List.of(), List.of(), "");
        var unreachable = new com.termina.sync.SyncReport(
                com.termina.sync.SyncReport.Status.FETCH_FAILED, List.of(), List.of(), List.of(), "no route");
        var refused = new com.termina.sync.SyncReport(
                com.termina.sync.SyncReport.Status.PUSH_FAILED, List.of(), List.of(), List.of(), "denied");
        var busy = new com.termina.sync.SyncReport(
                com.termina.sync.SyncReport.Status.LOCAL_BUSY, List.of(), List.of(), List.of(), "");
        assertFalse(SettingsSync.tellsOfFailure(ok, null));
        assertTrue(SettingsSync.tellsOfFailure(unreachable, null), "the first failure");
        assertTrue(SettingsSync.tellsOfFailure(unreachable, ok), "a failure after a good run");
        assertFalse(SettingsSync.tellsOfFailure(unreachable, unreachable), "the same failure again");
        assertTrue(SettingsSync.tellsOfFailure(refused, unreachable), "another kind of failure");
        assertFalse(SettingsSync.tellsOfFailure(busy, ok), "the bindings changing mid-run is no failure");
        // The pill's word for each phase has a key in the catalog.
        for (SettingsSync.Phase phase : SettingsSync.Phase.values()) {
            String key = SettingsWindow.syncPillKey(phase);
            assertFalse(com.termina.i18n.Messages.tr(key).equals(key), key + " is not in the catalog");
        }
    }

    @Test
    void cannotBeUndoneIsSaidOnlyWhereItIsTrue() throws IOException {
        Properties english = new Properties();
        try (var in = Files.newBufferedReader(Path.of("src/main/resources/com/termina/i18n/messages.properties"))) {
            english.load(in);
        }
        List<String> saying = english.stringPropertyNames().stream()
                .filter(key -> english.getProperty(key).contains("cannot be undone"))
                .sorted()
                .toList();
        // Removing a profile deletes it for good. Removing a theme says how it comes back.
        assertEquals(List.of("settings.profileRemove.confirmDetail"), saying);
        assertTrue(english.getProperty("settings.profileRemove.confirmDetail").endsWith("This cannot be undone."));
    }
}
