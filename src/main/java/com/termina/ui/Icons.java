package com.termina.ui;

import java.io.InputStream;
import java.util.List;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import org.jspecify.annotations.Nullable;

/**
 * The application icon.
 *
 * <p>Several sizes are supplied rather than one: a window manager picks the nearest and scales it,
 * and a 512px icon squeezed into a 16px title bar or task switcher looks noticeably worse than one
 * rendered at that size. The PNGs come from {@code branding/termina-icon.svg}.
 */
public final class Icons {

    /**
     * Smallest first, which is the order every window manager expects. The title bar, the task
     * switcher and the dock's small sizes come first; the large ones, which only a few window
     * managers ask for and which cost most of the decoding, follow the first frame (PERF-15,
     * Pauta 2.0.2).
     */
    private static final int[] SIZES = {16, 24, 32, 48, 64};

    private static final int[] LARGE_SIZES = {128, 256, 512};

    private static @Nullable List<Image> appIcons;

    private Icons() {}

    private static synchronized List<Image> icons() {
        if (appIcons != null) return appIcons;
        appIcons = decode(SIZES);
        return appIcons;
    }

    private static List<Image> decode(int[] sizes) {
        List<Image> loaded = new java.util.ArrayList<>();
        for (int size : sizes) {
            Image image = load(size);
            if (image != null) loaded.add(image);
        }
        return List.copyOf(loaded);
    }

    /**
     * Decodes the large sizes off the FX thread, then gives them to every window that has only the
     * small ones, and to every window opened after. Called once, after the first frame.
     */
    public static void loadLarge() {
        Thread worker = new Thread(
                () -> {
                    List<Image> large = decode(LARGE_SIZES);
                    if (large.isEmpty()) return;
                    javafx.application.Platform.runLater(() -> {
                        List<Image> small = icons();
                        List<Image> all = new java.util.ArrayList<>(small);
                        all.addAll(large);
                        synchronized (Icons.class) {
                            appIcons = List.copyOf(all);
                        }
                        for (javafx.stage.Window open : List.copyOf(javafx.stage.Window.getWindows())) {
                            if (open instanceof Stage stage && stage.getIcons().equals(small)) {
                                stage.getIcons().setAll(all);
                            }
                        }
                    });
                },
                "termina-icons");
        worker.setDaemon(true);
        worker.start();
    }

    private static @Nullable Image load(int size) {
        try (InputStream in = Icons.class.getResourceAsStream("/com/termina/icons/icon-" + size + ".png")) {
            // A missing icon is cosmetic; it must never stop a window from opening.
            return in == null ? null : new Image(in);
        } catch (Exception e) {
            return null;
        }
    }

    public static void applyTo(Stage stage) {
        stage.getIcons().setAll(icons());
    }

    /** The logo at a given size, for the About window. Null when the resource is missing. */
    public static @Nullable ImageView logo(double size) {
        Image image = load(256);
        if (image == null) return null;
        ImageView view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }
}
