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
