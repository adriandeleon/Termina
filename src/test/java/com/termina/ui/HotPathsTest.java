package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-21 (Pauta 6.0.1): each hot path that once regressed keeps a test that fails on the old
 * code. The ones with a behaviour to watch are beside their classes: {@code FrameGateTest} (an
 * idle terminal stops its timer), {@code SettingsSaveTest} (one write for a burst of changes),
 * {@code PromptMarksTest} (the painter's list is not copied each frame), {@code TitleBurstFxTest}
 * (a burst of titles is one update), {@code StyleCompilerFxTest} and {@code DeferredFontsTest}.
 * The one here has only an order to hold.
 */
class HotPathsTest {

    /**
     * For the fix in cedc1f5: the scroll bar is brought up to date after the frame has let go of
     * the buffer's lock. Updated inside it, the bar's listeners ran, and could ask the buffer for
     * its size, while the emulator's thread waited to write.
     */
    @Test
    void theScrollBarIsUpdatedAfterTheFrameReleasesTheBuffer() throws IOException {
        String view = Files.readString(Path.of("src/main/java/com/termina/ui/TerminalView.java"));
        int start = view.indexOf("    private void paint(GraphicsContext g, double w, double h) {");
        assertTrue(start > 0, "the paint method is where this test looks");
        int end = view.indexOf("\n    }\n", start);
        String render = view.substring(start, end);
        int lock = render.indexOf("buffer.lock();");
        int unlock = render.lastIndexOf("buffer.unlock();");
        int scrollBar = render.indexOf("updateScrollBar();");
        assertTrue(lock > 0 && unlock > lock, "the frame is drawn under the buffer's lock");
        assertTrue(scrollBar > unlock, "and the scroll bar is updated once it is released");
        assertTrue(render.indexOf("updateScrollBar();", scrollBar + 1) < 0, "once a frame");
    }

    /**
     * For the same commit: every settings write reaches every pane with its palette, and a pane
     * repaints and restyles its scroll bar only if a colour changed. That rests on a palette
     * being equal to another of the same colours, which a record holding an array is not by
     * default, and on the painter reading one colour without cloning the sixteen.
     */
    @Test
    void anUnchangedPaletteIsEqualSoAPaneCanSkipIt() throws IOException {
        TerminalPalette theme = Theme.EDITORA_DARK.palette();
        TerminalPalette same = new TerminalPalette(theme.background(), theme.foreground(), theme.ansi());
        assertNotSame(theme, same);
        assertEquals(theme, same);
        assertEquals(theme.hashCode(), same.hashCode());

        javafx.scene.paint.Color[] other = theme.ansi();
        other[3] = javafx.scene.paint.Color.web("#123456");
        assertNotEquals(theme, new TerminalPalette(theme.background(), theme.foreground(), other));

        for (int index = 0; index < 16; index++) assertEquals(theme.ansi()[index], theme.ansi(index));

        String view = Files.readString(Path.of("src/main/java/com/termina/ui/TerminalView.java"));
        assertTrue(view.contains("if (paletteApplied && palette.equals(this.palette)) return;"), "and the pane asks");
    }

    /**
     * And two more of that commit's, each an answer worked out once and then only read. Whether
     * type-ahead is wanted was asked of the foreground program's name on every write; it is
     * decided when the program changes. The colour search matches are washed with was derived for
     * every frame that drew one; it is derived when the palette is set.
     */
    @Test
    void whatAWriteOrAFrameOnlyReadsIsWorkedOutWhenItChanges() throws IOException {
        String view = Files.readString(Path.of("src/main/java/com/termina/ui/TerminalView.java"));
        assertTrue(
                view.contains("session.setTypeAheadWanted(() -> typeAheadAllowed && programPredicts);"),
                "a write reads two fields");
        assertEquals(1, count(view, "TypeAheadModel.predictsFor("), "asked once, where the program changes");

        // Once where the field is declared and once where the palette is set; never where it is drawn.
        assertEquals(
                2,
                count(view, "withAlpha(palette.ansi(3), SEARCH_ALPHA)")
                        + count(view, "withAlpha(Theme.EDITORA_DARK.palette().ansi(3), SEARCH_ALPHA)"));
        int draw = view.indexOf("    private void drawSearchMatches(");
        assertTrue(draw > 0, "the method that washes the matches is where this test looks");
        String drawing = view.substring(draw, view.indexOf("\n    }\n", draw));
        assertTrue(drawing.contains("searchWash") && !drawing.contains("withAlpha("), "the frame reads the colour");
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
