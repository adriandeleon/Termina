package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The picker's rules that need no window (QOPEN-01 to QOPEN-05, Pauta 6.0.1). */
class QuickPickTest {

    private static final List<String> THEMES =
            List.of("Follow the System", "Editora Dark", "Editora Light", "Clear Dark", "Clear Light", "High Contrast");

    @Test
    void withNothingTypedTheListIsInTheSuppliersOrder() {
        // QOPEN-04: recency, or the order of a menu, is an order already learned.
        assertEquals(THEMES, QuickPick.ordered(THEMES, "", name -> name));
        assertEquals(THEMES, QuickPick.ordered(THEMES, "   ", name -> name));
    }

    @Test
    void typingRanksByScoreAndTiesKeepTheSuppliersOrder() {
        // QOPEN-05: "dark" scores the same in both Dark themes, and they stay in the list's order.
        assertEquals(List.of("Editora Dark", "Clear Dark"), QuickPick.ordered(THEMES, "dark", name -> name));
        assertEquals(
                List.of("Clear Dark", "Editora Dark"),
                QuickPick.ordered(List.of("Clear Dark", "Editora Dark"), "dark", name -> name));
        // The same text four times scores the same four times: the order in is the order out.
        record Row(String name, int at) {}
        List<Row> same = List.of(new Row("zsh", 0), new Row("zsh", 1), new Row("zsh", 2), new Row("zsh", 3));
        assertEquals(same, QuickPick.ordered(same, "zs", Row::name));
        // A better match leads whatever its place, and what does not match is left out.
        assertEquals(
                "Clear Light",
                QuickPick.ordered(THEMES, "clear l", name -> name).getFirst());
        assertEquals(List.of(), QuickPick.ordered(THEMES, "qqq", name -> name));
    }

    @Test
    void theValueInForceIsSelectedUntilSomethingIsTyped() {
        // QOPEN-04: the picker opens on what is chosen now; once typing starts the best match leads.
        assertEquals(3, QuickPick.startIndex(THEMES, "", "Clear Dark"::equals));
        assertEquals(0, QuickPick.startIndex(THEMES, "c", "Clear Dark"::equals));
        assertEquals(0, QuickPick.startIndex(THEMES, "", "Not a theme"::equals));
        assertEquals(0, QuickPick.startIndex(THEMES, "", null));
    }

    @Test
    void theListIsAsTallAsItsRowsUpToALimitAndNeverNothing() {
        assertEquals(3 * 36 + 2, QuickPick.listHeight(3, 36, 2));
        assertEquals(QuickPick.MAX_VISIBLE * 36 + 2, QuickPick.listHeight(500, 36, 2));
        // One row at least, so a list with nothing in it has room to say so (QOPEN-10).
        assertEquals(36 + 2, QuickPick.listHeight(0, 36, 2));
        assertEquals(3 * 54.0, QuickPick.listHeight(3, 54, 0), "the row is given at the UI zoom");
    }

    @Test
    void aPickersTitleIsItsCommandsNameWithoutTheEllipsis() {
        // QOPEN-02: the title names the command.
        assertEquals("Select Color Theme", WindowRules.pickerTitle("Select Color Theme…"));
        assertEquals("Select Font", WindowRules.pickerTitle("Select Font..."));
        assertEquals("Nouvel onglet", WindowRules.pickerTitle("Nouvel onglet… "));
        assertEquals("Doctor", WindowRules.pickerTitle("Doctor"));
    }

    /**
     * QOPEN-01: one generic picker class serves every list choice. No dialog that chooses from a
     * list, and no second card that lists things to choose from by typing, anywhere in the app.
     */
    @Test
    void thereIsOnePickerClassAndNoChoiceDialog() throws IOException {
        Path main = Path.of("src/main/java");
        List<String> showsACard = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("ChoiceDialog"), file + " uses a ChoiceDialog");
                if (source.contains("host.show(")) showsACard.add(String.valueOf(file.getFileName()));
            }
        }
        // The cards that show in the overlay host: the palette, the Message Log and the picker.
        assertEquals(List.of("CommandPalette.java", "MessageLogCard.java", "QuickPick.java"), showsACard);

        String window = Files.readString(main.resolve("com/termina/ui/TerminalWindow.java"));
        for (String command : List.of(
                "command.view.selectTheme",
                "command.view.selectFont",
                "command.view.selectCursorShape",
                "command.file.newTabFromProfile")) {
            assertTrue(window.contains("item(\"" + command + "\""), command + " is registered");
        }
        // Each of the four is built by the one helper, which makes a QuickPick in the overlay host.
        assertEquals(4, window.split("= built = picker\\(", -1).length - 1);
        assertEquals(1, window.split("new QuickPick<>\\(", -1).length - 1);
    }
}
