package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** New Tab wears one glyph wherever it is reached (ICON-20, Pauta 2.0.2). */
class NewTabGlyphTest {

    @Test
    void theStripButtonAndTheMenusDrawTheSamePath() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/com/termina/styles/app.css"));
        int rule = css.indexOf(".tab-strip-glyph {");
        assertTrue(rule >= 0, "the strip's glyph rule is gone");
        String block = css.substring(rule, css.indexOf('}', rule));
        assertTrue(
                block.contains("-fx-shape: \"" + MenuIcons.ADD + "\""),
                "the strip's + and MenuIcons.newTab() draw different paths");
    }
}
