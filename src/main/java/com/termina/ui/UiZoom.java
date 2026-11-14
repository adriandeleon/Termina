package com.termina.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.scene.Scene;
import javafx.stage.Window;

import com.termina.config.Settings;

/**
 * The UI zoom (WIN-12, Pauta 6.0.1): one setting, {@link Settings#uiZoom}, scales the whole chrome
 * — the menus (MENU-16), the tab strip, the status bar and every other window — and leaves the
 * terminal's text to its own zoom. The stylesheets are written at 100%; at any other zoom every
 * window gets a copy with each length multiplied ({@link #scale}), the theme's root font size among
 * them, so every font follows one root size. The sizes the code fixes (a padding, a glyph's box, a
 * window's width) are bound to {@link #factor} instead of written as plain pixels.
 *
 * <p>The app template's class of the same name, with one difference: Termina's own sheets write
 * most lengths as bare numbers, which JavaFX reads as pixels, so those are scaled too, by property.
 */
public final class UiZoom {

    /** One step of Zoom Interface In or Out, in percent. */
    public static final int STEP = 10;

    /** The sheets the zoom scales: the control themes and the app's own. */
    private static final List<String> SHEETS =
            List.of("app.css", "settings.css", "editora-dark.css", "editora-light.css", "editora-contrast.css");

    private static final String FOLDER = "/com/termina/styles/";

    /** A declaration: a property, its colon, and its value up to the semicolon or brace. */
    private static final Pattern DECLARATION = Pattern.compile("(-fx-[a-z-]+)(\\s*:\\s*)([^;{}]+)");

    /** A length written with its unit. */
    private static final Pattern PX = Pattern.compile("(?<![\\w.#-])(-?\\d*\\.?\\d+)px\\b");

    /** A number with {@code px} or with no unit at all; one with another unit, or a percentage, is not matched. */
    private static final Pattern PX_OR_BARE = Pattern.compile("(?<![\\w.#-])(-?\\d*\\.?\\d+)(px\\b|(?![\\w.%]))");

    /**
     * The properties whose bare numbers are lengths. Not opacity, a scale, a shape's path, a count
     * or a time: those are numbers and stay as written. Nor a bare stroke width, which is a glyph's:
     * a glyph is scaled as a node, and its stroke with it.
     */
    private static final Set<String> LENGTHS = Set.of(
            "-fx-padding",
            "-fx-label-padding",
            "-fx-background-insets",
            "-fx-background-radius",
            "-fx-border-width",
            "-fx-border-insets",
            "-fx-border-radius",
            "-fx-min-width",
            "-fx-pref-width",
            "-fx-max-width",
            "-fx-min-height",
            "-fx-pref-height",
            "-fx-max-height",
            "-fx-spacing",
            "-fx-hgap",
            "-fx-vgap",
            "-fx-graphic-text-gap",
            "-fx-cell-size",
            "-fx-fixed-cell-size",
            "-fx-translate-x",
            "-fx-translate-y",
            "-fx-font-size",
            "-fx-indent",
            "-fx-arrow-button-gap");

    private static final ReadOnlyDoubleWrapper FACTOR = new ReadOnlyDoubleWrapper(1);

    /** Each scaled sheet, by name and percent, as a data: URL. */
    private static final Map<String, String> SCALED = new ConcurrentHashMap<>();

    /** Every URL handed out, bundled or scaled, to the sheet it is: how a zoom change finds what to swap. */
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

    private static volatile int percent = Settings.DEFAULT_UI_ZOOM;

    private UiZoom() {}

    /** The zoom in effect, in percent. */
    public static int percent() {
        return percent;
    }

    /** The zoom as a factor, 1 at 100%. */
    public static ReadOnlyDoubleProperty factor() {
        return FACTOR.getReadOnlyProperty();
    }

    /** One step larger, up to the largest. */
    public static int in(int percent) {
        return Settings.clampUiZoom(percent + STEP);
    }

    /** One step smaller, down to the smallest. */
    public static int out(int percent) {
        return Settings.clampUiZoom(percent - STEP);
    }

    /** {@code px} at the current zoom, now: for a size read once, such as a font measured for its width. */
    static double px(double px) {
        return px * FACTOR.get();
    }

    /** {@code px} at the current zoom, following it. */
    static DoubleBinding times(double px) {
        return FACTOR.multiply(px);
    }

    /** Binds {@code property} to {@code px} at the current zoom: a fixed size of the code, zoomed. */
    static void bind(DoubleProperty property, double px) {
        property.bind(times(px));
    }

    /**
     * Runs {@code update} now and again whenever the zoom changes, for as long as {@code owner}
     * lives: for a size no property can be bound to, such as a margin. The owner holds the
     * listener and the zoom holds it weakly, so a closed window's nodes are not kept alive here.
     */
    static void follow(javafx.scene.Node owner, Runnable update) {
        update.run();
        javafx.beans.InvalidationListener listener = observable -> update.run();
        owner.getProperties().put(new Object(), listener);
        FACTOR.addListener(new javafx.beans.WeakInvalidationListener(listener));
    }

    /** A region's padding, in pixels at 100%, following the zoom. */
    static void padding(javafx.scene.layout.Region region, double top, double right, double bottom, double left) {
        follow(region, () -> region.setPadding(new javafx.geometry.Insets(px(top), px(right), px(bottom), px(left))));
    }

    /** The same on every side. */
    static void padding(javafx.scene.layout.Region region, double all) {
        padding(region, all, all, all, all);
    }

    /** A child's margin in an HBox, in pixels at 100%, following the zoom. */
    static void hboxMargin(javafx.scene.Node child, double top, double right, double bottom, double left) {
        follow(
                child,
                () -> javafx.scene.layout.HBox.setMargin(
                        child, new javafx.geometry.Insets(px(top), px(right), px(bottom), px(left))));
    }

    // ---- the sizes the code fixes ----

    private static final Object ADOPTED = new Object();
    private static final Object OWN = new Object();

    /**
     * Leaves {@code node}'s own sizes out of {@link #adopt}: for a part whose code sets sizes it
     * works out as it goes (a list as tall as its rows), which it multiplies itself. What is under
     * it is adopted as usual.
     */
    static <N extends javafx.scene.Node> N own(N node) {
        node.getProperties().put(OWN, Boolean.TRUE);
        return node;
    }

    /**
     * Puts every size the code fixed under {@code root} on the zoom: paddings, spacings, margins,
     * minimum, preferred and maximum sizes, an image's fitted size and a glyph's scale, each read
     * now as its size at 100% and multiplied from then on. Only what code set: a size a stylesheet
     * gave is the stylesheet's, which is scaled as text. Nodes added later are adopted as they
     * arrive. At 100% this changes nothing, which is what keeps the default look out of its reach.
     *
     * <p>Called for each window's root as the window opens ({@link #adoptWindows}). A popup's rows
     * are not adopted, since a menu measures its own columns: they bind their sizes themselves.
     */
    static void adopt(javafx.scene.Node node) {
        if (node.getProperties().putIfAbsent(ADOPTED, Boolean.TRUE) != null) return;
        List<java.util.function.DoubleConsumer> sizes = new java.util.ArrayList<>();
        if (!node.getProperties().containsKey(OWN)) collect(node, sizes);
        if (!sizes.isEmpty()) {
            follow(node, () -> {
                double factor = FACTOR.get();
                for (java.util.function.DoubleConsumer size : sizes) size.accept(factor);
            });
        }
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().addListener((javafx.collections.ListChangeListener<javafx.scene.Node>)
                    change -> {
                        while (change.next()) {
                            for (javafx.scene.Node added : change.getAddedSubList()) adopt(added);
                        }
                    });
            for (javafx.scene.Node child : List.copyOf(parent.getChildrenUnmodifiable())) adopt(child);
        }
    }

    /** Adopts the root of every stage there is and will be; popups are left to their own rows. Once. */
    public static void adoptWindows() {
        if (adopting) return;
        adopting = true;
        Window.getWindows().addListener((javafx.collections.ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) adoptWindow(window);
            }
        });
        for (Window window : List.copyOf(Window.getWindows())) adoptWindow(window);
    }

    private static boolean adopting;

    private static void adoptWindow(Window window) {
        if (!(window instanceof javafx.stage.Stage)) return;
        if (window.getScene() != null && window.getScene().getRoot() != null) {
            adopt(window.getScene().getRoot());
        }
        window.sceneProperty().addListener((observable, was, scene) -> {
            if (scene != null && scene.getRoot() != null) adopt(scene.getRoot());
        });
    }

    private static void collect(javafx.scene.Node node, List<java.util.function.DoubleConsumer> out) {
        if (node instanceof javafx.scene.layout.Region region) {
            javafx.geometry.Insets padding = region.getPadding();
            if (setByCode(region.paddingProperty())
                    && !region.paddingProperty().isBound()
                    && padding != null
                    && !javafx.geometry.Insets.EMPTY.equals(padding)) {
                out.add(factor -> region.setPadding(times(padding, factor)));
            }
            size(region.minWidthProperty(), out);
            size(region.prefWidthProperty(), out);
            size(region.maxWidthProperty(), out);
            size(region.minHeightProperty(), out);
            size(region.prefHeightProperty(), out);
            size(region.maxHeightProperty(), out);
        }
        if (node instanceof javafx.scene.layout.HBox box) size(box.spacingProperty(), out);
        if (node instanceof javafx.scene.layout.VBox box) size(box.spacingProperty(), out);
        if (node instanceof javafx.scene.layout.GridPane grid) {
            size(grid.hgapProperty(), out);
            size(grid.vgapProperty(), out);
        }
        if (node instanceof javafx.scene.layout.FlowPane flow) {
            size(flow.hgapProperty(), out);
            size(flow.vgapProperty(), out);
        }
        if (node instanceof javafx.scene.image.ImageView image) {
            size(image.fitWidthProperty(), out);
            size(image.fitHeightProperty(), out);
        }
        if (node instanceof javafx.scene.shape.SVGPath glyph) {
            // A glyph drawn smaller or larger than its grid: the scale is its size.
            if (glyph.getScaleX() != 1 || glyph.getScaleY() != 1) {
                size(glyph.scaleXProperty(), out);
                size(glyph.scaleYProperty(), out);
            }
        }
        margin(node, out);
    }

    /** A child's margin, whichever kind of pane it sits in. */
    private static void margin(javafx.scene.Node child, List<java.util.function.DoubleConsumer> out) {
        javafx.scene.Parent parent = child.getParent();
        java.util.function.BiConsumer<javafx.scene.Node, javafx.geometry.Insets> set;
        javafx.geometry.Insets margin;
        if (parent instanceof javafx.scene.layout.HBox) {
            margin = javafx.scene.layout.HBox.getMargin(child);
            set = javafx.scene.layout.HBox::setMargin;
        } else if (parent instanceof javafx.scene.layout.VBox) {
            margin = javafx.scene.layout.VBox.getMargin(child);
            set = javafx.scene.layout.VBox::setMargin;
        } else if (parent instanceof javafx.scene.layout.GridPane) {
            margin = javafx.scene.layout.GridPane.getMargin(child);
            set = javafx.scene.layout.GridPane::setMargin;
        } else if (parent instanceof javafx.scene.layout.BorderPane) {
            margin = javafx.scene.layout.BorderPane.getMargin(child);
            set = javafx.scene.layout.BorderPane::setMargin;
        } else if (parent instanceof javafx.scene.layout.StackPane) {
            margin = javafx.scene.layout.StackPane.getMargin(child);
            set = javafx.scene.layout.StackPane::setMargin;
        } else {
            return;
        }
        if (margin == null || javafx.geometry.Insets.EMPTY.equals(margin)) return;
        javafx.geometry.Insets base = margin;
        out.add(factor -> set.accept(child, times(base, factor)));
    }

    private static javafx.geometry.Insets times(javafx.geometry.Insets insets, double factor) {
        return new javafx.geometry.Insets(
                insets.getTop() * factor,
                insets.getRight() * factor,
                insets.getBottom() * factor,
                insets.getLeft() * factor);
    }

    /** A length the code set, read now as its size at 100%: positive and finite, and not bound. */
    private static void size(DoubleProperty property, List<java.util.function.DoubleConsumer> out) {
        if (property.isBound() || !setByCode(property)) return;
        double base = property.get();
        if (!(base > 0) || base >= Double.MAX_VALUE) return;
        out.add(factor -> property.set(base * factor));
    }

    /**
     * Whether code set this value, not a stylesheet: a styleable property says where its value came
     * from. No origin at all is code too: a region keeps a size set before anyone asked for the
     * property in a plain field, and a stylesheet always goes through the property and signs it.
     * (Every caller also asks for a value that is not the default, which is what an untouched
     * property with no origin holds.)
     */
    private static boolean setByCode(javafx.beans.value.ObservableValue<?> property) {
        if (!(property instanceof javafx.css.StyleableProperty<?> styleable)) return true;
        javafx.css.StyleOrigin origin = styleable.getStyleOrigin();
        return origin == null || origin == javafx.css.StyleOrigin.USER;
    }

    /**
     * A window's scene at {@code width} by {@code height} pixels at 100%: made at that size times
     * the zoom, and its window resized by the same ratio when the zoom changes while it is open.
     * Not for the terminal's window, whose size is the user's and the grid's.
     */
    static Scene scene(javafx.scene.Parent root, double width, double height) {
        Scene scene = new Scene(root, px(width), px(height));
        double[] at = {FACTOR.get()};
        follow(root, () -> {
            double now = FACTOR.get();
            Window window = scene.getWindow();
            if (window != null && window.isShowing() && at[0] > 0) {
                window.setWidth(window.getWidth() * now / at[0]);
                window.setHeight(window.getHeight() * now / at[0]);
            }
            at[0] = now;
        });
        return scene;
    }

    /**
     * A stylesheet at {@code percent}: every pixel length multiplied, rounded to a hundredth. A
     * length written with {@code px} is one wherever it stands; a bare number is one in the
     * properties of {@link #LENGTHS}. An em or a percentage is relative already and is left alone.
     * Pure.
     */
    static String scale(String css, int percent) {
        if (percent == Settings.DEFAULT_UI_ZOOM) return css;
        double factor = percent / 100.0;
        Matcher declaration = DECLARATION.matcher(css);
        StringBuilder out = new StringBuilder(css.length() + 512);
        while (declaration.find()) {
            Pattern lengths = LENGTHS.contains(declaration.group(1)) ? PX_OR_BARE : PX;
            Matcher number = lengths.matcher(declaration.group(3));
            StringBuilder value = new StringBuilder();
            while (number.find()) {
                number.appendReplacement(value, Matcher.quoteReplacement(scaled(number.group(1), factor)));
            }
            number.appendTail(value);
            declaration.appendReplacement(
                    out, Matcher.quoteReplacement(declaration.group(1) + declaration.group(2) + value));
        }
        declaration.appendTail(out);
        return out.toString();
    }

    /** One length times the factor, as CSS: zero stays a bare zero, anything else carries its unit. */
    private static String scaled(String number, double factor) {
        double value = Math.round(Double.parseDouble(number) * factor * 100) / 100.0;
        if (value == 0) return "0";
        String text = value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%s", value);
        return text + "px";
    }

    /** One of the app's stylesheets at the zoom in effect, as a URL a scene takes. */
    public static String sheet(String name) {
        return sheetAt(name, percent);
    }

    /** The control theme at the zoom in effect; a sheet that is not one of ours is returned as it is. */
    public static String theme(String bundledUrl) {
        String name = bundledUrl.substring(bundledUrl.lastIndexOf('/') + 1);
        return SHEETS.contains(name) && bundledUrl.equals(bundled(name)) ? sheetAt(name, percent) : bundledUrl;
    }

    /** A sheet at {@code percent}: the bundled file at 100%, a scaled copy otherwise. */
    static String sheetAt(String name, int percent) {
        String url = percent == Settings.DEFAULT_UI_ZOOM
                ? bundled(name)
                : SCALED.computeIfAbsent(name + "@" + percent, key -> dataUrl(scale(text(name), percent)));
        NAMES.put(url, name);
        return url;
    }

    /**
     * The sheet as the build left it. Where a release build precompiled it (PERF-17, Pauta 6.0.1)
     * JavaFX takes the {@code .bss} beside this by itself, without parsing; nothing here asks.
     */
    private static String bundled(String name) {
        java.net.URL url = UiZoom.class.getResource(FOLDER + name);
        if (url == null) throw new IllegalStateException("missing stylesheet " + name);
        return url.toExternalForm();
    }

    private static String text(String name) {
        try (InputStream in = UiZoom.class.getResourceAsStream(FOLDER + name)) {
            if (in == null) throw new IllegalStateException("missing stylesheet " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** A stylesheet as a {@code data:} URL, which a scene and the user-agent sheet both take. */
    static String dataUrl(String css) {
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads and scales the sheets for {@code percent}: a file read and string work over the
     * themes' few thousand lines, so never on the FX thread (PERF-01). {@link #apply} then only
     * swaps URLs.
     */
    public static void prepare(int percent) {
        int wanted = Settings.clampUiZoom(percent);
        for (String name : SHEETS) sheetAt(name, wanted);
    }

    /**
     * Applies the zoom to every open window at once (WIN-12): each scene's copy of the app's
     * sheets, and the sizes bound to {@link #factor}. The control theme is the caller's to set
     * again, through {@link #theme}. FX thread; {@link #prepare} first, off it.
     *
     * @return whether the zoom changed
     */
    public static boolean apply(int next) {
        int wanted = Settings.clampUiZoom(next);
        if (wanted == percent) return false;
        percent = wanted;
        FACTOR.set(wanted / 100.0);
        for (Window window : List.copyOf(Window.getWindows())) {
            Scene scene = window.getScene();
            if (scene == null) continue;
            swap(scene.getStylesheets());
            // A dialog's own sheets are on its pane, the scene's root.
            if (scene.getRoot() != null) swap(scene.getRoot().getStylesheets());
        }
        return true;
    }

    private static void swap(List<String> sheets) {
        for (int at = 0; at < sheets.size(); at++) {
            String name = NAMES.get(sheets.get(at));
            if (name == null) continue;
            String now = sheetAt(name, percent);
            if (!now.equals(sheets.get(at))) sheets.set(at, now);
        }
    }

    /**
     * Changes the setting: the sheets are prepared on a worker, and the setting is written on the
     * FX thread once they are, so the restyle that follows reads no file there.
     */
    public static void change(Settings settings, int next) {
        int wanted = Settings.clampUiZoom(next);
        if (wanted == settings.uiZoom()) return;
        Thread worker = new Thread(
                () -> {
                    prepare(wanted);
                    Platform.runLater(() -> settings.setUiZoom(wanted));
                },
                "termina-ui-zoom");
        worker.setDaemon(true);
        worker.start();
    }
}
