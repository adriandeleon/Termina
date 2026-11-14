package com.termina.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The precompiled stylesheets (PERF-17, Pauta 6.0.1): what {@link StyleCompiler} writes is a sheet
 * JavaFX loads and styles from, and JavaFX takes the {@code .bss} beside a {@code .css} by itself,
 * which is why every build removes the ones a release build left.
 */
@Tag("fx")
class StyleCompilerFxTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void aCompiledSheetStylesAsItsSourceDoes() throws Exception {
        Files.writeString(dir.resolve("one.css"), ".marked { -fx-text-fill: #ff0000; -fx-padding: 3 5 7 9; }");
        assertEquals(List.of(dir.resolve("one.bss")), StyleCompiler.compile(dir));

        Label label = styled(dir.resolve("one.bss").toUri().toString());
        assertEquals(Color.web("#ff0000"), label.getTextFill());
        assertEquals(9, label.getPadding().getLeft());
    }

    /**
     * The trap the build's clean-up is for: asked for the {@code .css}, JavaFX loads the {@code
     * .bss} beside it without looking at which is newer. A release build's leftovers would hide
     * every later edit to a stylesheet.
     */
    @Test
    void javaFxTakesTheBinaryBesideASheetByItself() throws Exception {
        Path css = dir.resolve("two.css");
        Files.writeString(css, ".marked { -fx-text-fill: #ff0000; }");
        StyleCompiler.compile(dir);
        Files.writeString(css, ".marked { -fx-text-fill: #0000ff; }");

        Label label = styled(css.toUri().toString());
        assertEquals(Color.web("#ff0000"), label.getTextFill(), "the stale .bss, not the edited .css");
    }

    @Test
    void theAppsOwnSheetsCompile() throws Exception {
        for (String name : List.of("app.css", "settings.css", "editora-dark.css", "editora-light.css", "ui-font.css")) {
            try (var in = StyleCompiler.class.getResourceAsStream("/com/termina/styles/" + name)) {
                Files.write(
                        dir.resolve(name),
                        java.util.Objects.requireNonNull(in, name).readAllBytes());
            }
        }
        // A relative url() is resolved against the sheet's address, and the binary keeps the
        // address it was compiled at: the build's folder, which no installed app has.
        for (String name : List.of("app.css", "settings.css", "editora-dark.css", "editora-light.css", "ui-font.css")) {
            var url = java.util.regex.Pattern.compile("url\\(\\s*[\"']?([^\"')]{0,12})")
                    .matcher(Files.readString(dir.resolve(name)));
            while (url.find()) {
                String start = url.group(1);
                assertTrue(start.startsWith("data:") || start.startsWith("/"), name + " has a relative url(" + start);
            }
            assertTrue(
                    !Files.readString(dir.resolve(name)).contains("@font-face"), name + " declares a font by address");
        }
        List<Path> written = StyleCompiler.compile(dir);
        assertEquals(5, written.size());
        for (Path bss : written) assertTrue(Files.size(bss) > 100, bss + " is empty");

        // And the app's own sheet, compiled, still says what it said: the status bar's class.
        Label plain = styled(dir.resolve("app.css").toUri().toString().replace("app.css", "app.bss"), "status-bar");
        Label source = styledFromSource("app.css", "status-bar");
        assertEquals(source.getPadding(), plain.getPadding());
        assertEquals(source.getTextFill(), plain.getTextFill());
    }

    private Label styled(String sheet) throws Exception {
        return styled(sheet, "marked");
    }

    private static Label styled(String sheet, String styleClass) throws Exception {
        CompletableFuture<Label> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Label label = new Label("text");
                label.getStyleClass().add(styleClass);
                Scene scene = new Scene(new StackPane(label), 200, 100);
                scene.getStylesheets().add(sheet);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                done.complete(label);
            } catch (Throwable e) {
                done.completeExceptionally(e);
            }
        });
        return done.get(30, TimeUnit.SECONDS);
    }

    /** The same label styled from the sheet as written, copied where no {@code .bss} lies beside it. */
    private Label styledFromSource(String name, String styleClass) throws Exception {
        Path alone = Files.createDirectory(dir.resolve("source")).resolve(name);
        Files.copy(dir.resolve(name), alone);
        return styled(alone.toUri().toString(), styleClass);
    }
}
