package com.termina.ui;

import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/**
 * Glyphs for context-menu items, following Editora's convention that every right-click item carries
 * a leading icon.
 *
 * <p>Material Design Icons (Apache-2.0), inlined as single paths — see NOTICE. Colour comes from
 * the {@code .menu-icon} CSS rule rather than from here, so the glyphs follow the theme without a
 * per-theme copy.
 */
public final class MenuIcons {

    /** Material's 24dp grid. Scaled to sit comfortably beside menu text. */
    private static final double SCALE = 0.7;

    private MenuIcons() {}

    public static Node newTab() {
        return of("M4 5h16v2H4v12h16v-7h2v7c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V7c0-1.1.9-2 2-2zm14"
                + " 0V2h2v3h3v2h-3v3h-2V7h-3V5h3z");
    }

    static Node newWindow() {
        return of("M19 4H5c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0"
                + " 14H5V8h14v10zm-6-7h4v2h-4v3h-2v-3H7v-2h4V8h2v3z");
    }

    static Node close() {
        return of("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59"
                + " 19 19 17.59 13.41 12z");
    }

    static Node closeOthers() {
        return of("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm5 13.59L15.59"
                + " 17 12 13.41 8.41 17 7 15.59 10.59 12 7 8.41 8.41 7 12 10.59 15.59 7 17 8.41"
                + " 13.41 12 17 15.59z");
    }

    static Node closeRight() {
        return of("M6 6h2v12H6zm4.5 6l4.5 4.5V13H21v-2h-6V7.5z");
    }

    static Node arrowLeft() {
        return of("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z");
    }

    static Node arrowRight() {
        return of("M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z");
    }

    static Node copy() {
        return of("M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1"
                + " 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z");
    }

    static Node paste() {
        return of("M19 2h-4.18C14.4.84 13.3 0 12 0c-1.3 0-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9"
                + " 2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1"
                + " .45-1 1-1zm7 18H5V4h2v3h10V4h2v16z");
    }

    static Node selectAll() {
        return of("M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2zm10-6h-2v2h2V3zm6"
                + " 0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2zM9 3H7v2h2V3zm2"
                + " 18h2v-2h-2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0"
                + " 8h2v-2h-2v2zm-4 4h2v-2h-2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z");
    }

    static Node clear() {
        return of("M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z");
    }

    static Node zoomIn() {
        return of(
                "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3"
                        + " 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6"
                        + " 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14zM12 10h-2v2H9v-2H7V9h2V7h1v2h2v1z");
    }

    static Node zoomOut() {
        return of("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3"
                + " 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6"
                + " 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14zM7 9h5v1H7z");
    }

    /** A restore glyph rather than a magnifier: Actual Size resets the zoom, it does not change it. */
    static Node actualSize() {
        return of("M14 12c0-1.1-.9-2-2-2s-2 .9-2 2 .9 2 2 2 2-.9 2-2zm-2-9c-4.97 0-9 4.03-9 9H0l4 4"
                + " 4-4H5c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.51 0-2.91-.49-4.06-1.3l-1.42"
                + " 1.44C8.04 20.3 9.94 21 12 21c4.97 0 9-4.03 9-9s-4.03-9-9-9z");
    }

    static Node settings() {
        return of("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61"
                + "l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24"
                + "-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c"
                + "-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02"
                + ".64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38"
                + " 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24"
                + " 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01"
                + "-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6"
                + " 3.6z");
    }

    private static Node of(String path) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.setScaleX(SCALE);
        svg.setScaleY(SCALE);
        svg.getStyleClass().add("menu-icon");
        return svg;
    }
}
