package com.termina.ui;

import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

import org.jspecify.annotations.Nullable;

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

    /**
     * Material's "add", the glyph New Tab wears everywhere (ICON-20, Pauta 2.0.2): the tab strip's
     * button draws the same path from CSS ({@code .tab-strip-glyph} in app.css), and
     * {@code NewTabGlyphTest} fails when the two drift apart.
     */
    static final String ADD = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z";

    public static Node newTab() {
        return of(ADD);
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

    /** Material's "push pin": Pin Tab, and the mark on a pinned tab's header. */
    private static final String PIN =
            "M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3"
                    + " 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z";

    static Node pin() {
        return of(PIN);
    }

    /**
     * The pin at the size of a tab title's text, for a pinned tab's header. In a Group, so the
     * header lays it out at the size it is drawn rather than at the path's own 24 units.
     */
    static Node tabPin() {
        SVGPath svg = new SVGPath();
        svg.setContent(PIN);
        svg.scaleXProperty().bind(UiZoom.times(0.5));
        svg.scaleYProperty().bind(UiZoom.times(0.5));
        svg.getStyleClass().add("tab-pin");
        return new javafx.scene.Group(svg);
    }

    /** {@link #closeRight}, mirrored. */
    static Node closeLeft() {
        return of("M16 6h2v12h-2zM13.5 12L9 16.5V13H3v-2h6V7.5z");
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

    static Node arrowUp() {
        return of("M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z");
    }

    static Node arrowDown() {
        return of("M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z");
    }

    static Node search() {
        return of("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3"
                + " 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6"
                + " 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z");
    }

    /** A list with a magnifier over it: the command palette, which is a search over commands. */
    static Node commandPalette() {
        return of("M7 9H2V7h5v2zm0 3H2v2h5v-2zm13.59 7l-3.83-3.83c-.8.52-1.74.83-2.76.83-2.76 0-5-2.24"
                + "-5-5s2.24-5 5-5 5 2.24 5 5c0 1.02-.31 1.96-.83 2.75L22 17.59 20.59 19zM17 11c0-1.65-1"
                + ".35-3-3-3s-3 1.35-3 3 1.35 3 3 3 3-1.35 3-3zM2 19h10v-2H2v2z");
    }

    static Node about() {
        return of("M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2"
                + " 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z");
    }

    static Node copy() {
        return of("M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1"
                + " 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z");
    }

    /** A box with an arrow leaving it — how every browser draws a link that opens elsewhere. */
    static Node openLink() {
        return of("M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14"
                + " 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z");
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

    /** A pencil: rename. */
    static Node rename() {
        return of("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02"
                + " 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z");
    }

    /** Two tabs, one behind the other: duplicate. */
    static Node duplicate() {
        return of("M22 3H7c-1.1 0-2 .9-2 2v2h2V5h15v10h-2v2h2c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5"
                + " 6H3c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-8c0-1.1-.9-2-2-2zm0"
                + " 10H3v-8h14v8z");
    }

    static Node check() {
        return of("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z");
    }

    // ---------------------------------------------------------------- settings sidebar

    /** A painter's palette: colours, fonts, the look of the window. */
    static Node appearance() {
        return of("M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01"
                + "-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9"
                + "-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8"
                + ".67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1"
                + ".5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16"
                + ".67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z");
    }

    /** A window with a prompt in it: how the terminal itself behaves. */
    static Node terminal() {
        return of("M20 4H4c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.89-2-2-2zm0"
                + " 14H4V8h16v10zm-2-1h-6v-2h6v2zM7.5 17l-1.41-1.41L8.67 13l-2.59-2.59L7.5 9l4 4-4 4z");
    }

    /**
     * A stack of cards: the list of shells. Not a person, which is what "profile" usually draws —
     * these are shells, and the prompt glyph is already the Terminal page's.
     */
    static Node profiles() {
        return of("M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21"
                + " 9l-9-7-9 7 1.63 1.27L12 16z");
    }

    /** A keyboard: the shortcuts page. */
    static Node keyboard() {
        return of("M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3"
                + "h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2"
                + "zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z");
    }

    /** Two arrows chasing each other round: settings sync. */
    static Node sync() {
        return of("M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20"
                + " 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4"
                + ".46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z");
    }

    /** Sliders: the settings most people never touch. */
    static Node advanced() {
        return of("M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14"
                + " 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z");
    }

    // ---- one glyph per command a menu lists (MENU-14, Pauta 6.0.1) ----
    //
    // Material 24 dp paths where the set has a picture of the command, and 16-unit outline glyphs
    // in Editora's kit style where it has none. Those marked as Editora's are copied from its
    // Icons; the rest were drawn for Termina. Each constant is sliced at a fixed width, never
    // wrapped on a space: a wrap once joined two numbers and drew a quietly wrong shape.

    /** closeWindow: a window with a cross in it. */
    private static final String CLOSEWINDOW = "M2.5 3.5h11v9h-11zM6.2 6.3l3.6 3.4M9.8 6.3l-3.6 3.4";

    /** reopenClosedTab: Material's history: a clock running backwards. */
    private static final String REOPENCLOSEDTAB =
            "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2z"
                    + "M12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-"
                    + "1.23-4.5-2.67z";

    /** app.quit: the power mark. */
    private static final String APP_QUIT = "M8 1.8V7M4.6 3.9a5.6 5.6 0 1 0 6.8 0";

    /** findNext: a chevron down. */
    private static final String FINDNEXT = "M3.5 5.5 8 10l4.5-4.5";

    /** findPrevious: a chevron up. */
    private static final String FINDPREVIOUS = "M3.5 10.5 8 6l4.5 4.5";

    /** previousPrompt: two chevrons up: a jump, not a step. */
    private static final String PREVIOUSPROMPT = "M3.5 7.5 8 3l4.5 4.5M3.5 12.5 8 8l4.5 4.5";

    /** nextPrompt: two chevrons down. */
    private static final String NEXTPROMPT = "M3.5 3.5 8 8l4.5-4.5M3.5 8.5 8 13l4.5-4.5";

    /** selectLastOutput: lines of text in a frame. */
    private static final String SELECTLASTOUTPUT = "M2.5 2.5h11v11h-11zM5 6h6M5 8.5h6M5 11h3";

    /** copyLinkAddress: Material's link: two joined rings. */
    private static final String COPYLINKADDRESS =
            "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0"
                    + "-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1"
                    + " 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z";

    /** findRegex: a dot and a star, as in .*. */
    private static final String FINDREGEX = "M4 12.3v.2M9.5 3.5v6M6.9 5l5.2 3M12.1 5l-5.2 3";

    /** zoomIn: a magnifier with a plus. */
    private static final String ZOOMIN =
            "M2.6 7.0a4.4 4.4 0 1 0 8.8 0a4.4 4.4 0 1 0 -8.8 0M10.4 10.4L14 14M5 7h4M7 5v4";

    /** zoomOut: a magnifier with a minus. */
    private static final String ZOOMOUT = "M2.6 7.0a4.4 4.4 0 1 0 8.8 0a4.4 4.4 0 1 0 -8.8 0M10.4 10.4L14 14M5 7h4";

    /** actualSize: a magnifier with an equals sign. */
    private static final String ACTUALSIZE =
            "M2.6 7.0a4.4 4.4 0 1 0 8.8 0a4.4 4.4 0 1 0 -8.8 0M10.4 10.4L14 14M5.4 6.2h3.2M5.4 7." + "8h3.2";

    /** fullScreen: Material's fullscreen: four corners. */
    private static final String FULLSCREEN =
            "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z";

    /** hideMenuBar: a window with its top row marked. */
    private static final String HIDEMENUBAR = "M2.5 3h11v10h-11zM2.5 6h11";

    /** showStatusBar: a window with its bottom row marked. */
    private static final String SHOWSTATUSBAR = "M2.5 3h11v10h-11zM2.5 10h11";

    /** toggleSimpleMode: a window with two short rows of menu. */
    private static final String SIMPLEMODE = "M2.5 3h11v10h-11zM5 6.5h4M5 9.5h2.5";

    /** toggleZen: a window with nothing in it but a cursor. */
    private static final String ZENMODE = "M2.5 3h11v10h-11zM6 8h4";

    /** toggleExpert: a window with a prompt and its bottom row marked. */
    private static final String EXPERTMODE = "M2.5 3h11v10h-11zM2.5 10.5h11M5 5.5l2 1.5l-2 1.5";

    /** nextTab: Material's chevron right. */
    private static final String NEXTTAB = "M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z";

    /** previousTab: Material's chevron left. */
    private static final String PREVIOUSTAB = "M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z";

    /** splitRight: a pane divided left and right. */
    private static final String SPLITRIGHT =
            "M3.5 3.0h9.0a1.5 1.5 0 0 1 1.5 1.5v7.0a1.5 1.5 0 0 1 -1.5 1.5h-9.0a1.5 1.5 0 0 1 -1."
                    + "5 -1.5v-7.0a1.5 1.5 0 0 1 1.5 -1.5zM8 3v10";

    /** splitDown: a pane divided top and bottom. */
    private static final String SPLITDOWN =
            "M3.5 3.0h9.0a1.5 1.5 0 0 1 1.5 1.5v7.0a1.5 1.5 0 0 1 -1.5 1.5h-9.0a1.5 1.5 0 0 1 -1."
                    + "5 -1.5v-7.0a1.5 1.5 0 0 1 1.5 -1.5zM2 8h12";

    /** maximizePane: four corners pushed out. */
    private static final String MAXIMIZEPANE = "M2.5 6V2.5H6M10 2.5h3.5V6M13.5 10v3.5H10M6 13.5H2.5V10";

    /** movePaneToNewTab: an arrow leaving a frame. */
    private static final String MOVEPANETONEWTAB =
            "M9 2h5v5M14 2L8 8M12 9.5V13a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h3.5";

    /** help.documentation: an open book. */
    private static final String HELP_DOCUMENTATION =
            "M8 4.2C6.6 3.2 4.6 3 2.5 3.4v8.6c2.1-.4 4.1-.2 5.5.8 1.4-1 3.4-1.2 5.5-.8V3.4C11.4 3"
                    + " 9.4 3.2 8 4.2zM8 4.2v8.6";

    /** whatsNew: a star. */
    private static final String WHATSNEW = "M8 2.2l1.7 3.6 3.9.5-2.9 2.7.8 3.9L8 11l-3.5 1.9.8-3.9-2.9-2.7 3.9-.5z";

    /** checkForUpdates: an arrow down onto a line. */
    private static final String CHECKFORUPDATES = "M8 2.5v7.5M4.8 7l3.2 3 3.2-3M3 13.5h10";

    /** view.messageLog: a speech bubble with lines in it. */
    private static final String VIEW_MESSAGELOG = "M2.5 3h11v8H7l-3 2.5V11H2.5zM5 6h6M5 8.5h4";

    /** debugLog: a bug. */
    private static final String DEBUGLOG =
            "M5.2 8.4 C5.2 6.2 6.5 4.8 8 4.8 C9.5 4.8 10.8 6.2 10.8 8.4 L10.8 10.2 C10.8 12.1 9.5"
                    + " 13.3 8 13.3 C6.5 13.3 5.2 12.1 5.2 10.2 ZM6.5 5.2 L5.3 3.4M9.5 5.2 L10.7 3.4M5.3 7."
                    + "8 L3.1 6.6M5.2 9.9 L2.9 9.9M5.3 11.8 L3.1 13.1M10.7 7.8 L12.9 6.6M10.8 9.9 L13.1 9.9"
                    + "M10.7 11.8 L12.9 13.1M6.0 7.0 L10.0 7.0";

    /** reportProblem: a warning triangle. */
    private static final String REPORTPROBLEM = "M8 2.2L14.6 13.4H1.4zM8 6.8v3M8 11.6v.2";

    /** help.doctor: a cross in a box, the sign on a first-aid kit. */
    private static final String HELP_DOCTOR = "M2.5 2.5h11v11h-11zM8 5v6M5 8h6";

    /** help.pauta: Material's checklist. */
    private static final String HELP_PAUTA =
            "M22 7l-1.41-1.41L13 13.17l-2.59-2.58L9 12l4 4 9-9zM3 5h11v2H3V5zm0 6h7v2H3v-2zm0 6h7" + "v2H3v-2z";

    /** debugLog.refresh: Material's refresh. */
    private static final String DEBUGLOG_REFRESH =
            "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84"
                    + "-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14."
                    + "69 4.22 1.78L13 11h7V4l-2.35 2.35z";

    /** debugLog.export: a page with an arrow down. */
    private static final String DEBUGLOG_EXPORT =
            "M4.5 2.5h4.6l2.9 2.9v8.1h-7.5zM9.1 2.5v2.9h2.9M8.25 6.8v4.6M6.5 9.7l1.75 1.75L10 9.7";

    /** settings.addProfile: a figure with a plus. */
    private static final String SETTINGS_ADDPROFILE =
            "M6 7.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 1 0 0 5M1.8 13.5c0-2.4 1.9-4 4.2-4s4.2 1.6 4.2 4M" + "12 5v4M10 7h4";

    /** settings.removeProfile: Material's delete. */
    private static final String SETTINGS_REMOVEPROFILE =
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";

    /** showTabBar: a window with a row of tabs under its top edge. */
    private static final String SHOWTABBAR = "M2.5 3h11v10h-11zM2.5 6h11M6.5 3v3M10 3v3";

    /** The magnifier with a plus: the zoom row of the right-click menu, which is the zoom family's. */
    static Node zoom() {
        return line(ZOOMIN);
    }

    /**
     * Command id to glyph. A table, not a switch, so a test can walk it: an id with a typo is
     * otherwise a row with an empty icon column and nothing to say why. A supplier, because a node
     * belongs to one scene and every row needs its own.
     */
    private static final java.util.Map<String, java.util.function.Supplier<Node>> BY_COMMAND =
            new java.util.LinkedHashMap<>();

    private static void add(String id, java.util.function.Supplier<Node> glyph) {
        if (BY_COMMAND.put(id, glyph) != null) throw new IllegalStateException("two glyphs for " + id);
    }

    static {
        add("file.newTab", MenuIcons::newTab);
        add("file.newWindow", MenuIcons::newWindow);
        add("file.closeTab", MenuIcons::close);
        add("file.closeOtherTabs", MenuIcons::closeOthers);
        add("file.closeTabsToTheLeft", MenuIcons::closeLeft);
        add("file.closeTabsToTheRight", MenuIcons::closeRight);
        add("settings.show", MenuIcons::settings);
        add("sync.now", MenuIcons::sync);
        add("settings.configureProfiles", MenuIcons::profiles);
        add("edit.copy", MenuIcons::copy);
        add("edit.paste", MenuIcons::paste);
        add("edit.selectAll", MenuIcons::selectAll);
        add("edit.clearScrollback", MenuIcons::clear);
        add("find.show", MenuIcons::search);
        add("palette.show", MenuIcons::commandPalette);
        add("tab.moveLeft", MenuIcons::arrowLeft);
        add("tab.moveRight", MenuIcons::arrowRight);
        add("tab.rename", MenuIcons::rename);
        add("tab.duplicate", MenuIcons::duplicate);
        add("tab.pin", MenuIcons::pin);
        add("help.about", MenuIcons::about);
        add("link.open", MenuIcons::openLink);
        add("help.keyboardShortcuts", MenuIcons::keyboard);
        add("file.closeWindow", () -> line(CLOSEWINDOW));
        add("file.reopenClosedTab", () -> of(REOPENCLOSEDTAB));
        add("app.quit", () -> line(APP_QUIT));
        add("find.next", () -> line(FINDNEXT));
        add("find.previous", () -> line(FINDPREVIOUS));
        add("view.previousPrompt", () -> line(PREVIOUSPROMPT));
        add("view.nextPrompt", () -> line(NEXTPROMPT));
        add("edit.selectLastOutput", () -> line(SELECTLASTOUTPUT));
        add("link.copyAddress", () -> of(COPYLINKADDRESS));
        add("find.regex", () -> line(FINDREGEX));
        add("view.zoomIn", () -> line(ZOOMIN));
        add("view.zoomOut", () -> line(ZOOMOUT));
        add("view.actualSize", () -> line(ACTUALSIZE));
        add("view.fullScreen", () -> of(FULLSCREEN));
        add("view.toggleMenuBar", () -> line(HIDEMENUBAR));
        add("view.toggleStatusBar", () -> line(SHOWSTATUSBAR));
        add("view.toggleSimpleMode", () -> line(SIMPLEMODE));
        add("view.toggleZen", () -> line(ZENMODE));
        add("view.toggleExpert", () -> line(EXPERTMODE));
        add("tab.next", () -> of(NEXTTAB));
        add("tab.previous", () -> of(PREVIOUSTAB));
        add("pane.splitRight", () -> line(SPLITRIGHT));
        add("pane.splitDown", () -> line(SPLITDOWN));
        add("pane.maximize", () -> line(MAXIMIZEPANE));
        add("pane.moveToNewTab", () -> line(MOVEPANETONEWTAB));
        add("help.documentation", () -> line(HELP_DOCUMENTATION));
        add("help.whatsNew", () -> line(WHATSNEW));
        add("help.checkForUpdates", () -> line(CHECKFORUPDATES));
        add("view.messageLog", () -> line(VIEW_MESSAGELOG));
        add("help.debugLog", () -> line(DEBUGLOG));
        add("help.reportProblem", () -> line(REPORTPROBLEM));
        add("help.doctor", () -> line(HELP_DOCTOR));
        add("help.pauta", () -> of(HELP_PAUTA));
        add("debugLog.refresh", () -> of(DEBUGLOG_REFRESH));
        add("debugLog.export", () -> line(DEBUGLOG_EXPORT));
        add("settings.addProfile", () -> line(SETTINGS_ADDPROFILE));
        add("settings.removeProfile", () -> of(SETTINGS_REMOVEPROFILE));
        add("view.toggleTabBar", () -> line(SHOWTABBAR));
        // The same action in another window wears the same glyph: Copy is Copy wherever it is.
        add("debugLog.find", MenuIcons::search);
        add("debugLog.copy", MenuIcons::copy);
        add("debugLog.clear", MenuIcons::clear);
        add("messageLog.copy", MenuIcons::copy);
        add("messageLog.clear", MenuIcons::clear);
        // A numbered profile row wears its profile's own mark once it opens; this is the mark of
        // the family, for a row drawn before then.
        for (int number = 1; number <= WindowRules.TAB_CHORD_COUNT; number++) {
            add(WindowRules.PROFILE_CHORD_ID + number, MenuIcons::terminal);
        }
    }

    /**
     * The glyph a command wears wherever it is drawn (ICON-20, MENU-14, Pauta 6.0.1): one lookup, so
     * a menu bar row and a drop-down's row for the same command cannot pick different ones. Null
     * for a command no menu lists, which has no row to draw one on.
     */
    static @Nullable Node forCommand(@Nullable String id) {
        if (id == null) return null;
        java.util.function.Supplier<Node> glyph = BY_COMMAND.get(id);
        return glyph == null ? null : glyph.get();
    }

    /** The glyph of a command a menu lists: it has one, or this is a bug in the table above. */
    static Node command(String id) {
        Node glyph = forCommand(id);
        if (glyph == null) throw new IllegalStateException("no glyph for " + id);
        return glyph;
    }

    /** Every command with a glyph, for the test that every menu row's command is one of them. */
    static java.util.Set<String> commandsWithGlyphs() {
        return java.util.Collections.unmodifiableSet(BY_COMMAND.keySet());
    }

    /**
     * A 16-unit outline glyph, stroked: coloured by {@code .menu-icon-line}, which sets a stroke
     * where {@code .menu-icon} sets a fill. Scaled to the size the 24 dp glyphs are drawn at.
     */
    private static Node line(String path) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.scaleXProperty().bind(UiZoom.times(SCALE * 24 / 16));
        svg.scaleYProperty().bind(UiZoom.times(SCALE * 24 / 16));
        svg.getStyleClass().add("menu-icon-line");
        return svg;
    }

    private static Node of(String path) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        // With the UI zoom (WIN-12, Pauta 6.0.1): a glyph is chrome, and grows with the text beside it.
        svg.scaleXProperty().bind(UiZoom.times(SCALE));
        svg.scaleYProperty().bind(UiZoom.times(SCALE));
        svg.getStyleClass().add("menu-icon");
        return svg;
    }
}
