package com.termina.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javafx.css.Stylesheet;

/**
 * Precompiles the app's stylesheets to JavaFX's binary form (PERF-17, Pauta 6.0.1): every {@code
 * .css} in a folder gets a {@code .bss} beside it, which loads without being parsed. The release
 * build ({@code -Pdist}) runs it on the compiled resources, so the packaged app carries both.
 * Nothing in the app asks for a {@code .bss}: given a {@code .css}, JavaFX takes the binary beside
 * it by itself, and never looks at which is newer, so every build first deletes the ones in {@code
 * target/classes}. At any UI zoom but 100% {@link UiZoom} scales the {@code .css} text instead.
 *
 * <p>A stylesheet's address is written into its binary with every {@code url()} in it, so a
 * relative one would point into the build's folder for good; the sheets have none, and {@code
 * StyleCompilerFxTest} holds that. No toolkit is needed. The app template's class of the same name.
 */
public final class StyleCompiler {

    private StyleCompiler() {}

    /** {@code args[0]}: the folder of stylesheets. */
    public static void main(String[] args) throws IOException {
        List<Path> done = compile(Path.of(args[0]));
        System.out.println("[style-compiler] " + done.size() + " stylesheets precompiled to .bss");
    }

    /** Writes a {@code .bss} beside every {@code .css} in {@code folder}; returns the files written. */
    static List<Path> compile(Path folder) throws IOException {
        List<Path> written = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path css :
                    files.filter(f -> f.toString().endsWith(".css")).sorted().toList()) {
                Path bss = binaryOf(css);
                Stylesheet.convertToBinary(css.toFile(), new File(bss.toString()));
                written.add(bss);
            }
        }
        return written;
    }

    /** {@code app.css} to {@code app.bss}, beside it. Pure. */
    static Path binaryOf(Path css) {
        return css.resolveSibling(binaryName(css.getFileName().toString()));
    }

    /** The binary twin's name for a stylesheet's. Pure. */
    static String binaryName(String css) {
        return css.endsWith(".css") ? css.substring(0, css.length() - ".css".length()) + ".bss" : css + ".bss";
    }
}
