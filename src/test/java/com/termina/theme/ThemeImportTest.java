package com.termina.theme;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every format reads the same scheme to the same colours. The fixtures are Dracula (MIT) written
 * out in each format, so one set of expected values checks all six readers.
 */
class ThemeImportTest {

    private static final int[] DRACULA = {
        0x21222c, 0xff5555, 0x50fa7b, 0xf1fa8c, 0xbd93f9, 0xff79c6, 0x8be9fd, 0xf8f8f2,
        0x6272a4, 0xff6e6e, 0x69ff94, 0xffffa5, 0xd6acff, 0xff92df, 0xa4ffff, 0xffffff,
    };

    static byte[] fixture(String name) throws IOException {
        try (InputStream in = ThemeImportTest.class.getResourceAsStream("/themes/" + name)) {
            if (in == null) throw new IOException("no fixture " + name);
            return in.readAllBytes();
        }
    }

    private static ColorScheme only(String file) throws Exception {
        List<ColorScheme> schemes = ThemeImport.read(file, fixture(file));
        assertEquals(1, schemes.size());
        return schemes.getFirst();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "Dracula.itermcolors",
                "Dracula.terminal",
                "Dracula.json",
                "Dracula",
                "Dracula.conf",
                "Dracula.toml"
            })
    void everyFormatReadsAllSixteenColoursAndTheRest(String file) throws Exception {
        ColorScheme scheme = only(file);
        assertEquals("Dracula", scheme.name());
        assertArrayEquals(DRACULA, scheme.ansi());
        assertEquals(0x282a36, scheme.background());
        assertEquals(0xf8f8f2, scheme.foreground());
        assertEquals(0xf8f8f2, scheme.cursor());
        assertEquals(0x44475a, scheme.selection());
    }

    @Test
    void aWindowsTerminalSettingsFileGivesEverySchemeInIt() throws Exception {
        List<ColorScheme> schemes = ThemeImport.read("settings.json", fixture("settings.json"));
        assertEquals(
                List.of("Dracula", "Dracula Copy"),
                schemes.stream().map(ColorScheme::name).toList());
        assertArrayEquals(DRACULA, schemes.get(1).ansi());
    }

    @Test
    void aTerminalProfileThatSetsOnlyABackgroundGetsTerminalsOwnPalette() throws Exception {
        ColorScheme scheme = only("Partial.terminal");
        assertEquals("Only Background", scheme.name());
        assertEquals(0x102030, scheme.background());
        assertEquals(0x990000, scheme.ansi()[1]);
        assertEquals(null, scheme.cursor());
    }

    @Test
    void aGreyArchivedAsWhiteIsRead() throws Exception {
        // Dracula's bright white is written as NSWhite in the fixture, not NSRGB.
        assertEquals(0xffffff, only("Dracula.terminal").ansi()[15]);
    }

    @Test
    void aPropertyListCannotReadAFileThroughAnEntity() throws Exception {
        ThemeImportException refused = assertThrows(
                ThemeImportException.class, () -> ThemeImport.read("Evil.itermcolors", fixture("Evil.itermcolors")));
        assertFalse(refused.getMessage().contains("root:"), refused.getMessage());
    }

    @Test
    void somethingElseEntirelySaysWhatItCanRead() {
        ThemeImportException refused = assertThrows(
                ThemeImportException.class,
                () -> ThemeImport.read("notes.txt", "hello\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(refused.getMessage().contains("iTerm2"));
    }

    @Test
    void aSchemeMissingAColourIsRefusedRatherThanHalfRead() {
        String kitty = "foreground #ffffff\nbackground #000000\ncolor0 #000000\n";
        assertThrows(
                ThemeImportException.class,
                () -> ThemeImport.read("half.conf", kitty.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sniffingTellsTheTextFormatsApart() {
        assertEquals(TextSchemes.Format.GHOSTTY, TextSchemes.sniff("palette = 0=#000000\n"));
        assertEquals(TextSchemes.Format.KITTY, TextSchemes.sniff("color0 #000000\n"));
        assertEquals(TextSchemes.Format.ALACRITTY, TextSchemes.sniff("[colors.primary]\n"));
        assertEquals(null, TextSchemes.sniff("nothing here"));
    }

    @Test
    void hexColoursInEachSpelling() throws Exception {
        assertEquals(0x112233, TextSchemes.hexColour("#112233"));
        assertEquals(0x112233, TextSchemes.hexColour("112233"));
        assertEquals(0x112233, TextSchemes.hexColour("0x112233"));
        assertEquals(0x112233, TextSchemes.hexColour("#123"));
        assertThrows(ThemeImportException.class, () -> TextSchemes.hexColour("red"));
    }

    @Test
    void ourOwnFileRoundTrips() throws Exception {
        ColorScheme scheme = only("Dracula.itermcolors");
        ColorScheme back = ThemeFile.read(ThemeFile.write(scheme));
        assertEquals(scheme, back);
    }

    @Test
    void aNameThatLooksLikeAPropertiesCommentSurvivesTheRoundTrip() throws Exception {
        ColorScheme scheme = new ColorScheme("# not a comment\\", 0, 0xffffff, DRACULA, null, null);
        assertEquals(
                "# not a comment\\", ThemeFile.read(ThemeFile.write(scheme)).name());
    }

    @Test
    void damagedBinaryPlistsAreRefused() {
        assertThrows(
                ThemeImportException.class, () -> BinaryPlist.parse("bplist00".getBytes(StandardCharsets.US_ASCII)));
        byte[] junk = new byte[64];
        System.arraycopy("bplist00".getBytes(StandardCharsets.US_ASCII), 0, junk, 0, 8);
        java.util.Arrays.fill(junk, 8, 64, (byte) 0x7f);
        assertThrows(ThemeImportException.class, () -> BinaryPlist.parse(junk));
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
