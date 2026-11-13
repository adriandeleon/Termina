package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.termina.i18n.Messages;
import com.termina.process.RunningProgram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CONF-12 (Pauta 6.0.1): the words of every question are built by pure functions, read here with
 * no dialog and no toolkit.
 */
class QuestionsTest {

    private static List<Questions.Question> removals() {
        return List.of(
                Questions.resetSettings(),
                Questions.removeSyncCredentials(),
                Questions.removeTheme("Dracula"),
                Questions.resetKeyTaken("Ctrl+Shift+T", "New Tab", "Duplicate Tab"),
                Questions.resetAllKeys(),
                Questions.removeProfile("fish"),
                Questions.closeRunning(List.of("vim"), 1),
                Questions.quitRunning(List.of("vim")));
    }

    @Test
    void everyQuestionNamesItsActionAndARemovalKeepsWhatTheUserHasOnEnter() {
        List<Questions.Question> all = new ArrayList<>(removals());
        all.add(Questions.paste(40, 3, false));
        all.add(Questions.paste(90_000, 1, true));
        for (Questions.Question question : all) {
            assertFalse(question.title().isBlank(), question.toString());
            assertFalse(question.header().isBlank(), question.toString());
            assertFalse(question.action().isBlank(), question.toString());
            // The action's own verb, never the stock pair's (CONF-01).
            for (String stock : List.of("OK", "Yes", "No")) assertNotEquals(stock, question.action());
            assertNotEquals(Messages.tr("dialog.cancel"), question.action());
        }
        for (Questions.Question removal : removals()) {
            assertTrue(removal.danger(), removal.header());
            assertTrue(removal.cancelDefault(), removal.header());
        }
    }

    @Test
    void aQuestionNamesTheThingItIsAbout() {
        assertTrue(Questions.removeTheme("Dracula").header().contains("Dracula"));
        assertTrue(Questions.removeProfile("fish").header().contains("fish"));
        Questions.Question taken = Questions.resetKeyTaken("Ctrl+Shift+T", "New Tab", "Duplicate Tab");
        assertTrue(taken.header().contains("Ctrl+Shift+T"), taken.header());
        assertTrue(taken.detail().contains("New Tab") && taken.detail().contains("Duplicate Tab"), taken.detail());
        assertEquals(
                Messages.tr("settings.reset.confirm"), Questions.resetSettings().action());
    }

    @Test
    void aPasteIsNotDangerousAndSaysHowMuch() {
        Questions.Question lines = Questions.paste(40, 3, false);
        assertFalse(lines.danger());
        assertFalse(lines.cancelDefault(), "Paste is the default: most such pastes are meant");
        assertEquals(Messages.tr("dialog.pasteMultiline", 3), lines.header());
        assertEquals(Messages.tr("dialog.paste"), lines.action());

        Questions.Question large = Questions.paste(90_000, 1, true);
        assertEquals(Messages.tr("dialog.pasteLarge", 90_000), large.header());
        assertNotEquals(lines.detail(), large.detail());
    }

    @Test
    void closingSaysWhatIsRunningAndQuittingNamesQuit() {
        Questions.Question one = Questions.closeRunning(List.of("vim"), 1);
        Questions.Question several = Questions.closeRunning(List.of("vim", "top"), 3);
        assertNotEquals(one.header(), several.header(), "one tab, or several");
        assertTrue(one.detail().contains("vim"));
        assertTrue(several.detail().contains("vim, top"));
        assertEquals(Messages.tr("dialog.closeAnyway"), one.action());
        assertEquals(
                Messages.tr("dialog.quit"),
                Questions.quitRunning(List.of("vim")).action());

        // A few are named, then counted.
        List<String> many = new ArrayList<>();
        for (int i = 0; i < RunningProgram.MAX_NAMED + 4; i++) many.add("job" + i);
        String summary = Questions.runningSummary(many);
        assertTrue(summary.contains("job0") && summary.contains("job" + (RunningProgram.MAX_NAMED - 1)), summary);
        assertFalse(summary.contains("job" + RunningProgram.MAX_NAMED), summary);
        assertTrue(summary.contains("4"), summary);
    }

    /** The rule's own check, on the source: no question's words are put together where it is asked. */
    @Test
    void everyQuestionAskedIsOneOfThese() throws IOException {
        Pattern ask = Pattern.compile("Confirm\\.ask\\(\\s*(\\w+),\\s*(\\S+?)[.(]");
        List<String> inline = new ArrayList<>();
        int asked = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.endsWith("Confirm.java")) continue;
                Matcher call = ask.matcher(Files.readString(file));
                while (call.find()) {
                    asked++;
                    if (!"Questions".equals(call.group(2))) inline.add(file.getFileName() + ": " + call.group());
                }
            }
        }
        assertEquals(List.of(), inline);
        assertEquals(9, asked, "every place the app asks; a new one is a new kind here");
    }
}
