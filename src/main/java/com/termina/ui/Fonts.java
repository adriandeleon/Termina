package com.termina.ui;

import java.io.InputStream;
import java.util.List;

import javafx.scene.text.Font;

/**
 * The bundled fonts.
 *
 * <p>Bundled so a terminal looks the same on every machine it runs on. A font picker over whatever
 * happens to be installed gives Menlo on macOS, Consolas on Windows and DejaVu on Linux — three
 * different metrics, three different weights, and no way to describe a setup to somebody else. The
 * same families ship with Editora, which is where the themes come from.
 *
 * <p>{@link #load()} must run before any stylesheet is applied, or the CSS naming Inter resolves to
 * nothing and silently falls back to the system font.
 */
public final class Fonts {

    /** Bundled monospace families, best first. These lead the font picker. */
    public static final List<String> BUNDLED_MONO =
            List.of("JetBrains Mono", "Cascadia Code", "Fira Code", "IBM Plex Mono", "Source Code Pro");

    /** The interface font. Not a terminal face, so deliberately absent from the picker. */
    public static final String UI = "Inter";

    private static final String[] FILES = {
        "jetbrains-mono/JetBrainsMono-Regular.ttf",
        "jetbrains-mono/JetBrainsMono-Bold.ttf",
        "jetbrains-mono/JetBrainsMono-Italic.ttf",
        "jetbrains-mono/JetBrainsMono-BoldItalic.ttf",
        "cascadia-code/CascadiaCode-Regular.ttf",
        "cascadia-code/CascadiaCode-Bold.ttf",
        "cascadia-code/CascadiaCode-Italic.ttf",
        "cascadia-code/CascadiaCode-BoldItalic.ttf",
        // Fira Code ships no italic; JavaFX slants the regular face for it.
        "fira-code/FiraCode-Regular.ttf",
        "fira-code/FiraCode-Bold.ttf",
        "ibm-plex-mono/IBMPlexMono-Regular.ttf",
        "ibm-plex-mono/IBMPlexMono-Bold.ttf",
        "ibm-plex-mono/IBMPlexMono-Italic.ttf",
        "ibm-plex-mono/IBMPlexMono-BoldItalic.ttf",
        "source-code-pro/SourceCodePro-Regular.ttf",
        "source-code-pro/SourceCodePro-Bold.ttf",
        "source-code-pro/SourceCodePro-Italic.ttf",
        "source-code-pro/SourceCodePro-BoldItalic.ttf",
        // Bold matters as much as regular here: the terminal renders a bold SGR attribute with a
        // real bold face, and the macOS system font has none JavaFX can rasterise cleanly — it
        // synthesises one and mangles the glyphs.
        "inter/Inter-Regular.ttf",
        "inter/Inter-Bold.ttf",
        "inter/Inter-Italic.ttf",
        "inter/Inter-BoldItalic.ttf",
    };

    private Fonts() {}

    /**
     * Puts the interface font on a scene.
     *
     * <p>Per scene, not once in app.css, because every dialog and popup is a scene of its own — and
     * because a scene stylesheet survives the runtime user-agent swap a theme change performs.
     */
    public static void installUiFont(javafx.scene.Scene scene) {
        var css = Fonts.class.getResource("/com/termina/styles/ui-font.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
    }

    /** Registers every bundled font with the toolkit. A file that fails to load is skipped. */
    public static void load() {
        for (String file : FILES) {
            try (InputStream in = Fonts.class.getResourceAsStream("/com/termina/fonts/" + file)) {
                if (in != null) Font.loadFont(in, 12);
            } catch (Exception ignored) {
                // One unreadable font is not worth failing a launch over; the rest still load.
            }
        }
    }
}
