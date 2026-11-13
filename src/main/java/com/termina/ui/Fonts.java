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

    /**
     * The Nerd Font icons, and nothing else: no letters, so not a terminal face either. Drawn for
     * private-use code points when no Nerd Font build of the terminal's face is installed — see
     * {@link IconFont}. The Mono build, so every icon is one cell wide and none spills over the
     * name after it.
     */
    public static final String ICONS = "Symbols Nerd Font Mono";

    /**
     * The bundled monospace families and their faces, in {@link #BUNDLED_MONO} order. Only the one
     * the terminal uses is loaded before the first window; the rest wait for the first frame, or for
     * whatever asks for one of them first (PERF-15, Pauta 2.0.2).
     */
    private static final java.util.Map<String, List<String>> FAMILIES = java.util.Map.of(
            "JetBrains Mono",
            List.of(
                    "jetbrains-mono/JetBrainsMono-Regular.ttf",
                    "jetbrains-mono/JetBrainsMono-Bold.ttf",
                    "jetbrains-mono/JetBrainsMono-Italic.ttf",
                    "jetbrains-mono/JetBrainsMono-BoldItalic.ttf"),
            "Cascadia Code",
            List.of(
                    "cascadia-code/CascadiaCode-Regular.ttf",
                    "cascadia-code/CascadiaCode-Bold.ttf",
                    "cascadia-code/CascadiaCode-Italic.ttf",
                    "cascadia-code/CascadiaCode-BoldItalic.ttf"),
            // Fira Code ships no italic; JavaFX slants the regular face for it.
            "Fira Code",
            List.of("fira-code/FiraCode-Regular.ttf", "fira-code/FiraCode-Bold.ttf"),
            "IBM Plex Mono",
            List.of(
                    "ibm-plex-mono/IBMPlexMono-Regular.ttf",
                    "ibm-plex-mono/IBMPlexMono-Bold.ttf",
                    "ibm-plex-mono/IBMPlexMono-Italic.ttf",
                    "ibm-plex-mono/IBMPlexMono-BoldItalic.ttf"),
            "Source Code Pro",
            List.of(
                    "source-code-pro/SourceCodePro-Regular.ttf",
                    "source-code-pro/SourceCodePro-Bold.ttf",
                    "source-code-pro/SourceCodePro-Italic.ttf",
                    "source-code-pro/SourceCodePro-BoldItalic.ttf"));

    /** Needed by the first frame whatever the settings say: the interface font and the icons. */
    private static final List<String> ALWAYS = List.of(
            // Bold matters as much as regular here: the terminal renders a bold SGR attribute with a
            // real bold face, and the macOS system font has none JavaFX can rasterise cleanly — it
            // synthesises one and mangles the glyphs.
            "inter/Inter-Regular.ttf",
            "inter/Inter-Bold.ttf",
            "inter/Inter-Italic.ttf",
            "inter/Inter-BoldItalic.ttf",
            // One face for every style: the icons have no weights, and a bold request resolves to it.
            // Its LICENSE and README sit beside it; the README lists each icon set's own licence.
            "symbols-nerd-font/SymbolsNerdFontMono-Regular.ttf");

    /** The bundled families registered so far. */
    private static final java.util.Set<String> LOADED = new java.util.HashSet<>();

    private Fonts() {}

    /**
     * Puts the interface font on a scene.
     *
     * <p>Per scene, not once in app.css, because every dialog and popup is a scene of its own — and
     * because a scene stylesheet survives the runtime user-agent swap a theme change performs.
     */
    public static void installUiFont(javafx.scene.Scene scene) {
        var css = Fonts.class.getResource("/com/termina/styles/ui-font.css");
        // Once: the window list's listener comes by every time a window is shown.
        if (css != null && !scene.getStylesheets().contains(css.toExternalForm())) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }

    /**
     * Registers what the first window needs: the interface font, the icons, and the terminal's
     * family — {@code preferred} when it is a bundled one, and the default bundled family in any
     * case, since it is what a blank or missing preference resolves to.
     */
    public static synchronized void load(String preferred) {
        loadFiles(ALWAYS);
        ensure(BUNDLED_MONO.getFirst());
        ensure(preferred);
    }

    /**
     * Registers a bundled family if it is not yet, and answers whether that happened. Cheap when it
     * is loaded or is not a bundled family at all, so a resolve can ask on every call.
     */
    public static synchronized boolean ensure(String family) {
        List<String> files = FAMILIES.get(family);
        if (files == null || LOADED.contains(family)) return false;
        loadFiles(files);
        LOADED.add(family);
        return true;
    }

    /**
     * The bundled families registered so far. {@code Font.getFamilies()} is not enough: JavaFX
     * builds that list the first time it is asked and never adds a font registered after, though
     * {@code Font.font} finds one — so a family loaded on demand would look uninstalled.
     */
    public static synchronized java.util.Set<String> loadedFamilies() {
        return java.util.Set.copyOf(LOADED);
    }

    /** Whether every bundled family is registered, so there is nothing left to read. */
    public static synchronized boolean restLoaded() {
        return LOADED.containsAll(BUNDLED_MONO);
    }

    /** Registers every bundled family not loaded yet; answers whether any was. */
    public static synchronized boolean loadRest() {
        boolean any = false;
        for (String family : BUNDLED_MONO) any |= ensure(family);
        return any;
    }

    /**
     * Registers the rest on a thread of its own, never the FX thread (PERF-01, Pauta 6.0.1): a
     * font file read after the first frame is a frame that waits on the disk. {@code whenAny}
     * runs on that thread if a family was registered, for whoever caches the list of them.
     */
    public static void loadRestInBackground(Runnable whenAny) {
        Thread worker = new Thread(
                () -> {
                    if (loadRest()) whenAny.run();
                },
                "termina-fonts");
        worker.setDaemon(true);
        worker.start();
    }

    private static void loadFiles(List<String> files) {
        for (String file : files) {
            try (InputStream in = Fonts.class.getResourceAsStream("/com/termina/fonts/" + file)) {
                if (in != null) Font.loadFont(in, 12);
            } catch (Exception ignored) {
                // One unreadable font is not worth failing a launch over; the rest still load.
            }
        }
    }
}
