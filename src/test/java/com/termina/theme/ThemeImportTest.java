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

    @ParameterizedTest
    @ValueSource(strings = {"Dracula", "Dracula.conf", "Dracula.toml", "Dracula.json", "Dracula.itermcolors"})
    void aByteOrderMarkDoesNotHideTheFormat(String file) throws Exception {
        // Ghostty and Alacritty files saved by a Windows editor start with U+FEFF; it is not
        // whitespace, so the format sniffing saw it instead of the first line.
        byte[] plain = fixture(file);
        byte[] marked = new byte[plain.length + 3];
        marked[0] = (byte) 0xEF;
        marked[1] = (byte) 0xBB;
        marked[2] = (byte) 0xBF;
        System.arraycopy(plain, 0, marked, 3, plain.length);
        List<ColorScheme> schemes = ThemeImport.read(file, marked);
        assertArrayEquals(DRACULA, schemes.getFirst().ansi());
    }

    @Test
    void reasonsAreInTheUiLanguage() {
        ThemeImportException refused = assertThrows(ThemeImportException.class, () -> TextSchemes.hexColour("red"));
        assertEquals(ThemeImportException.Reason.NOT_A_COLOUR, refused.reason());
        assertEquals(com.termina.i18n.Messages.tr("theme.error.notAColour", "red"), refused.getMessage());
        assertFalse(refused.getMessage().startsWith("theme.error."), refused.getMessage());
    }

    /**
     * A {@code bplist00} of the given objects, each already encoded, with the top object first.
     * Four-byte offsets and references, so a long chain fits.
     */
    private static byte[] bplist(List<byte[]> objects) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes("bplist00".getBytes(StandardCharsets.US_ASCII));
        int[] offsets = new int[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = out.size();
            out.writeBytes(objects.get(i));
        }
        int table = out.size();
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(offsets.length * 4 + 32);
        for (int offset : offsets) buffer.putInt(offset);
        buffer.put(new byte[6]).put((byte) 4).put((byte) 4);
        buffer.putLong(objects.size()).putLong(0).putLong(table);
        out.writeBytes(buffer.array());
        return out.toByteArray();
    }

    /** An array of references (marker 0xA0 | count), four bytes each. */
    private static byte[] array(int... refs) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1 + refs.length * 4);
        b.put((byte) (0xA0 | refs.length));
        for (int ref : refs) b.putInt(ref);
        return b.array();
    }

    private static final byte[] ZERO = {0x10, 0x00};

    @Test
    void sharedReferencesAreReadOnceNotOncePerPath() {
        // Object i is [i+1, i+1]: read afresh per reference that is 2^40 visits. Remembered, 41.
        List<byte[]> objects = new java.util.ArrayList<>();
        int depth = 40;
        for (int i = 0; i < depth; i++) objects.add(array(i + 1, i + 1));
        objects.add(ZERO);
        byte[] file = bplist(objects);
        Object top = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2), () -> BinaryPlist.parse(file));
        org.junit.jupiter.api.Assertions.assertTrue(top instanceof List<?>);
    }

    @Test
    void aDeepChainIsRefusedRatherThanOverflowingTheStack() {
        // 200,000 one-element arrays, each holding the next: 1.8 MB, under the import's size cap.
        int length = 200_000;
        List<byte[]> objects = new java.util.ArrayList<>(length + 1);
        for (int i = 0; i < length; i++) objects.add(array(i + 1));
        objects.add(ZERO);
        byte[] file = bplist(objects);
        ThemeImportException refused = assertThrows(ThemeImportException.class, () -> BinaryPlist.parse(file));
        assertEquals(ThemeImportException.Reason.DAMAGED, refused.reason());
    }

    @Test
    void aCycleIsStillRefused() {
        byte[] file = bplist(List.of(array(1), array(0)));
        assertThrows(ThemeImportException.class, () -> BinaryPlist.parse(file));
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
