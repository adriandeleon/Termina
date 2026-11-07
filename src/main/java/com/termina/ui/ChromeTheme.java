package com.termina.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Theme;
import org.jspecify.annotations.Nullable;

/**
 * The control theme a window is drawn in, through AtlantaFX's {@link Theme} API (PLAT-05, Pauta
 * 2.0.2). The Editora sheets in {@code styles/} are AtlantaFX community themes, as in Editora;
 * should one be missing from a build, AtlantaFX's own Primer theme of the same lightness draws the
 * window rather than nothing.
 *
 * @param name the sheet's name in {@code styles/}, without {@code .css}
 * @param dark whether it is a dark theme, which also picks the fallback
 */
record ChromeTheme(String name, boolean dark) implements Theme {

    @Override
    public String getName() {
        return name;
    }

    /** The bundled sheet's resource path, as AtlantaFX themes give theirs. */
    @Override
    public String getUserAgentStylesheet() {
        return "/com/termina/styles/" + name + ".css";
    }

    @Override
    public @Nullable String getUserAgentStylesheetBSS() {
        return null;
    }

    @Override
    public boolean isDarkMode() {
        return dark;
    }

    /**
     * The sheet as a URL for {@code Application.setUserAgentStylesheet}. A URL, not the bare path an
     * AtlantaFX theme returns: set at run time, a path is resolved through the context class loader,
     * which is null on a UI event, and the theme switch stops half done (Editora's finding).
     */
    String stylesheetUrl() {
        var url = ChromeTheme.class.getResource(getUserAgentStylesheet());
        if (url != null) return url.toExternalForm();
        return primerUrl(dark);
    }

    /** AtlantaFX's Primer theme of the given lightness, as a URL. */
    static String primerUrl(boolean dark) {
        Theme primer = dark ? new PrimerDark() : new PrimerLight();
        String sheet = primer.getUserAgentStylesheet();
        var url = primer.getClass().getResource(sheet);
        return url != null ? url.toExternalForm() : sheet;
    }
}
