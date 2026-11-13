package com.termina.ui;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.termina.AppInfo;

/**
 * Which page of the documentation explains each page of the Settings window (DOCS-11, Pauta
 * 6.0.1): the one map the pages' <em>Help for</em> links are made from.
 *
 * <p>A slug is the documentation page's address under {@link AppInfo#DOCUMENTATION}, which is the
 * name of its file in the website's repository. Two Settings pages have no page of their own
 * there and share the one on the Settings window itself.
 */
final class SettingsHelp {

    private SettingsHelp() {}

    /** Every Settings page, to its documentation page. */
    static final Map<SettingsWindow.Category, String> SLUGS = slugs();

    private static Map<SettingsWindow.Category, String> slugs() {
        Map<SettingsWindow.Category, String> slugs = new EnumMap<>(SettingsWindow.Category.class);
        slugs.put(SettingsWindow.Category.APPEARANCE, "appearance");
        slugs.put(SettingsWindow.Category.TERMINAL, "settings");
        slugs.put(SettingsWindow.Category.PROFILES, "shell-profiles");
        slugs.put(SettingsWindow.Category.KEYBOARD, "keyboard");
        slugs.put(SettingsWindow.Category.SYNC, "sync");
        slugs.put(SettingsWindow.Category.ADVANCED, "settings");
        return Collections.unmodifiableMap(slugs);
    }

    /** The address a page's help link opens. */
    static String url(SettingsWindow.Category page) {
        String slug = SLUGS.get(page);
        if (slug == null) throw new IllegalStateException("no documentation page for " + page);
        return AppInfo.DOCUMENTATION + slug + "/";
    }
}
