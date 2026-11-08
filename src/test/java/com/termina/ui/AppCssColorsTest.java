package com.termina.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** No fixed colour in a component rule (ICON-08, Pauta 2.0.2). */
class AppCssColorsTest {

    private static final Pattern LITERAL_COLOR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|\\brgba?\\(|\\bhsba?\\(");

    @Test
    void componentRulesUseTokensNotLiteralColours() throws IOException {
        assertEquals(List.of(), literalColoursOutsideRoot(read("/com/termina/styles/app.css")));
    }

    @Test
    void theCheckSeesAColourInARuleButNotInTheTokenBlock() {
        String css = ".root {\n  -scrim: rgba(0, 0, 0, 0.3);\n  -x: #fff;\n}\n.a {\n  -fx-fill: #123456;\n}\n"
                + "/* a #abc in a comment */\n.b { -fx-effect: dropshadow(gaussian, rgba(0,0,0,.4), 4, 0, 0, 1); }\n";
        assertEquals(
                2,
                literalColoursOutsideRoot(css).size(),
                literalColoursOutsideRoot(css).toString());
    }

    /** Every literal colour in a rule other than a {@code .root} block, comments ignored, by line. */
    static List<String> literalColoursOutsideRoot(String css) {
        String text = stripComments(css);
        List<String> found = new ArrayList<>();
        int depth = 0;
        boolean inRoot = false;
        int line = 1;
        StringBuilder selector = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') line++;
            if (c == '{') {
                if (depth == 0) inRoot = selector.toString().trim().equals(".root");
                depth++;
                selector.setLength(0);
                continue;
            }
            if (c == '}') {
                depth = Math.max(0, depth - 1);
                if (depth == 0) inRoot = false;
                selector.setLength(0);
                continue;
            }
            if (depth == 0) {
                selector.append(c);
                continue;
            }
            if (inRoot) continue;
            Matcher m = LITERAL_COLOR.matcher(text);
            m.region(i, text.length());
            if (m.lookingAt()) found.add("line " + line + ": " + m.group());
        }
        return found;
    }

    private static String stripComments(String css) {
        // Keeps the newlines, so line numbers still match the file.
        Matcher m = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(css);
        StringBuilder out = new StringBuilder();
        while (m.find())
            m.appendReplacement(out, Matcher.quoteReplacement(m.group().replaceAll("[^\n]", " ")));
        m.appendTail(out);
        return out.toString();
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = AppCssColorsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
