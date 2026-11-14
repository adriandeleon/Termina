package com.termina.ui;

import java.util.Map;

import com.termina.AppInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DOCS-11 (Pauta 6.0.1): one map says which page of the documentation each Settings page opens. */
class SettingsHelpTest {

    @Test
    void everySettingsPageHasItsDocumentationPage() {
        // The names of the pages' files in the website's repository; a page added to Settings
        // fails here until it is given one.
        assertEquals(
                Map.of(
                        SettingsWindow.Category.APPEARANCE, "appearance",
                        SettingsWindow.Category.TERMINAL, "settings",
                        SettingsWindow.Category.PROFILES, "shell-profiles",
                        SettingsWindow.Category.KEYBOARD, "keyboard",
                        SettingsWindow.Category.SYNC, "sync",
                        SettingsWindow.Category.ADVANCED, "settings"),
                SettingsHelp.SLUGS);
        for (SettingsWindow.Category page : SettingsWindow.Category.values()) {
            String url = SettingsHelp.url(page);
            assertTrue(url.startsWith(AppInfo.DOCUMENTATION) && url.endsWith("/"), url);
            assertTrue(url.substring(AppInfo.DOCUMENTATION.length()).matches("[a-z][a-z-]*/"), url);
        }
        assertEquals(AppInfo.DOCUMENTATION + "shell-profiles/", SettingsHelp.url(SettingsWindow.Category.PROFILES));
    }
}
