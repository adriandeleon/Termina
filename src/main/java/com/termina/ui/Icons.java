package com.termina.ui;

import java.io.InputStream;
import java.util.List;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

/**
 * The application icon.
 *
 * <p>Several sizes are supplied rather than one: a window manager picks the nearest and scales it,
 * and a 512px icon squeezed into a 16px title bar or task switcher looks noticeably worse than one
 * rendered at that size. The PNGs come from {@code branding/termina-icon.svg}.
 */
public final class Icons {

    /** Smallest first, which is the order every window manager expects. */
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

    private static List<Image> appIcons;

    private Icons() {}

    private static synchronized List<Image> icons() {
        if (appIcons != null) return appIcons;
        List<Image> loaded = new java.util.ArrayList<>();
        for (int size : SIZES) {
            Image image = load(size);
            if (image != null) loaded.add(image);
        }
        appIcons = List.copyOf(loaded);
        return appIcons;
    }

    private static Image load(int size) {
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
    public static ImageView logo(double size) {
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
