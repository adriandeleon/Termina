package com.termina.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A profile's own font and theme, stored by profile id. */
class ProfileStyleTest {

    @Test
    void aStyleSurvivesAReloadAndAnEmptyPartIsNotStored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.properties");
        Settings written = new Settings(file);
        written.setProfileStyle("wsl-ubuntu", new Settings.ProfileStyle("Fira Code", 15.0, ""));
        Settings read = new Settings(file);
        read.load();
        Settings.ProfileStyle style = read.profileStyle("wsl-ubuntu");
        assertEquals("Fira Code", style.fontFamily());
        assertEquals(15.0, style.fontSize());
        assertEquals("", style.themeId());
        assertTrue(!Files.readString(file).contains("wsl-ubuntu.theme"));
    }

    @Test
    void aProfileWithNoStyleFollowsTheGlobalOnes(@TempDir Path dir) {
        Settings settings = new Settings(dir.resolve("settings.properties"));
        assertTrue(settings.profileStyle("system").isEmpty());
        assertTrue(settings.profileStyle("").isEmpty());
        assertTrue(settings.profileStyle(null).isEmpty());
    }

    @Test
    void aHandEditedSizeIsClampedAndGarbageIsIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.properties");
        Files.writeString(
                file, "profileStyle.a.fontSize=900\nprofileStyle.b.fontSize=big\nprofileStyle.c.fontSize=NaN\n");
        Settings settings = new Settings(file);
        settings.load();
        assertEquals(Settings.MAX_FONT_SIZE, settings.profileStyle("a").fontSize());
        assertNull(settings.profileStyle("b").fontSize());
        assertNull(settings.profileStyle("c").fontSize());
    }

    @Test
    void theGlobalZoomAppliesToAProfilesOwnSize(@TempDir Path dir) {
        Settings settings = new Settings(dir.resolve("settings.properties"));
        settings.setFontSize(12);
        settings.setFontZoom(1.5);
        assertEquals(18.0, settings.effectiveFontSize(Settings.ProfileStyle.NONE));
        assertEquals(24.0, settings.effectiveFontSize(new Settings.ProfileStyle("", 16.0, "")));
    }

    @Test
    void resetSparesTheStylesWithTheProfiles() {
        assertTrue(Settings.isProfileKey("profileStyle.system.theme"));
        assertTrue(!Settings.keysToReset(java.util.Set.of("profileStyle.x.font", "font.size"))
                .contains("profileStyle.x.font"));
    }

    @Test
    void writingTheSameStyleTwiceBroadcastsOnce(@TempDir Path dir) {
        Settings settings = new Settings(dir.resolve("settings.properties"));
        AtomicInteger changes = new AtomicInteger();
        settings.addOnChange(changes::incrementAndGet);
        var style = new Settings.ProfileStyle("", null, "user-dracula");
        settings.setProfileStyle("p", style);
        settings.setProfileStyle("p", style);
        assertEquals(1, changes.get());
        settings.setProfileStyle("p", Settings.ProfileStyle.NONE);
        assertEquals(2, changes.get());
        assertTrue(settings.profileStyle("p").isEmpty());
    }
}
