package com.termina.ui;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which code points are drawn in a Nerd Font, and which installed Nerd Font that is. */
class IconFontTest {

    /** The families JavaFX reported on the machine where the stray glyphs were found. */
    private static final List<String> INSTALLED = List.of(
            "Arial",
            "DejaVu Sans",
            "JetBrains Mono",
            "JetBrainsMono NF",
            "JetBrainsMono NF ExtraBold",
            "JetBrainsMono NFM",
            "JetBrainsMono NFP",
            "JetBrainsMonoNL NF",
            "JetBrainsMonoNL NFM",
            "MathJax_WinIE6");

    @Test
    void privateUseCoversAllThreeRanges() {
        assertTrue(IconFont.isPrivateUse(0xE000));
        assertTrue(IconFont.isPrivateUse(0xE271)); // eza's icon for a symlinked image
        assertTrue(IconFont.isPrivateUse(0xF8FF));
        assertTrue(IconFont.isPrivateUse(0xF10B5)); // supplementary plane A
        assertTrue(IconFont.isPrivateUse(0x10FFFD)); // plane B
        assertFalse(IconFont.isPrivateUse(0xDFFF));
        assertFalse(IconFont.isPrivateUse(0xF900));
        assertFalse(IconFont.isPrivateUse(0xFFFFE));
        assertFalse(IconFont.isPrivateUse('a'));
        assertFalse(IconFont.isPrivateUse(0x2500)); // box drawing is the face's own
    }

    @Test
    void findsPrivateUseInARun() {
        assertTrue(IconFont.containsPrivateUse(" initrd.img"));
        assertTrue(IconFont.containsPrivateUse("x" + new String(Character.toChars(0xF10B5))));
        assertFalse(IconFont.containsPrivateUse("initrd.img -> boot/"));
        assertFalse(IconFont.containsPrivateUse("─┼─ ⠿ é"));
        // An emoji is a surrogate pair too, but not one in a private-use plane.
        assertFalse(IconFont.containsPrivateUse(new String(Character.toChars(0x1F600))));
        assertFalse(IconFont.containsPrivateUse(""));
    }

    @Test
    void prefersTheNerdBuildOfTheTerminalsOwnFace() {
        // "JetBrains Mono" and "JetBrainsMono" are the same face. NL is a different one.
        assertEquals(Optional.of("JetBrainsMono NF"), IconFont.chooseFamily("JetBrains Mono", INSTALLED));
    }

    @Test
    void theSymbolsBuildComesNextThenAnyOtherInNameOrder() {
        List<String> installed = List.of("Hack Nerd Font", "Symbols Nerd Font Mono", "Arial");
        assertEquals(Optional.of("Symbols Nerd Font Mono"), IconFont.chooseFamily("Fira Code", installed));
        assertEquals(
                Optional.of("Hack Nerd Font"),
                IconFont.chooseFamily("Fira Code", List.of("Meslo LGS Nerd Font", "Hack Nerd Font")));
    }

    @Test
    void powerlineSeparatorsAreTheOnesKeptAtTextSize() {
        assertTrue(IconFont.isPowerline(0xE0A0)); // branch
        assertTrue(IconFont.isPowerline(0xE0B0)); // the arrow every prompt segment ends in
        assertTrue(IconFont.isPowerline(0xE0B4)); // rounded
        assertTrue(IconFont.isPowerline(0xE0D7));
        assertFalse(IconFont.isPowerline(0xE0D8));
        assertFalse(IconFont.isPowerline(0xE271));
        assertFalse(IconFont.isPowerline(0xE5FF)); // a folder: shrunk to the cell
    }

    @Test
    void aCellSizedIconIsCentredInTheRow() {
        // A 14px row with its baseline 11px down, and 8px-wide cells: the icon is drawn at 8px,
        // its em box 0.8 up and 0.2 down, so its centre is 2.4px above the baseline it is drawn on.
        double size = IconFont.cellSize(8);
        assertEquals(8, size, 1e-9);
        double shift = IconFont.cellBaselineShift(11, 14, size);
        double iconCentre = 11 + shift - 0.3 * size;
        assertEquals(7, iconCentre, 1e-9);
    }

    @Test
    void theBundledSymbolsFaceIsTheFloor() {
        // The app always has it: it beats an unrelated Nerd Font, and yields to the terminal
        // face's own Nerd build, which the user installed on purpose.
        List<String> installed = List.of(Fonts.ICONS, "JetBrainsMonoNL NF", "Hack Nerd Font");
        assertEquals(Optional.of(Fonts.ICONS), IconFont.chooseFamily("Cascadia Code", installed));
        assertEquals(Optional.of("Hack Nerd Font"), IconFont.chooseFamily("Hack", installed));
    }

    @Test
    void plainBeforeMonoAndNeverProportional() {
        assertEquals(
                Optional.of("JetBrainsMonoNL NF"),
                IconFont.chooseFamily("Cascadia Code", List.of("JetBrainsMonoNL NFM", "JetBrainsMonoNL NF")));
        assertEquals(
                Optional.of("Hack Nerd Font Mono"),
                IconFont.chooseFamily("Cascadia Code", List.of("Hack Nerd Font Propo", "Hack Nerd Font Mono")));
        assertEquals(Optional.empty(), IconFont.chooseFamily("Cascadia Code", List.of("JetBrainsMono NFP")));
    }

    @Test
    void aWeightRegisteredAsItsOwnFamilyIsNotChosen() {
        assertEquals(Optional.empty(), IconFont.chooseFamily("Cascadia Code", List.of("JetBrainsMono NF ExtraBold")));
    }

    @Test
    void aNerdFontTerminalFaceDrawsItsOwnIcons() {
        assertEquals(Optional.empty(), IconFont.chooseFamily("JetBrainsMono NF", INSTALLED));
        assertEquals(Optional.empty(), IconFont.chooseFamily("Hack Nerd Font Mono", INSTALLED));
    }

    @Test
    void noNerdFontLeavesTheFallbackAlone() {
        assertEquals(Optional.empty(), IconFont.chooseFamily("JetBrains Mono", List.of("Arial", "MathJax_WinIE6")));
    }
}
