package com.termina.shell;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.termina.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ProfileStartupTest {
    @TempDir
    Path directory;

    @Test
    void waitsForSavedDiscoveredDefaultAndResolvesOnCompletion() throws Exception {
        Settings settings = new Settings(directory.resolve("settings.properties"));
        settings.setDefaultProfileId("wsl-ubuntu");
        ShellProfiles profiles = new ShellProfiles(settings, "Default Shell");
        AtomicReference<Profile> opened = new AtomicReference<>();
        profiles.whenDefaultReady(opened::set);
        assertNull(opened.get());
        var callbacks = new LinkedBlockingQueue<Runnable>();
        Profile ubuntu =
                Profile.of("wsl-ubuntu", "Ubuntu", List.of("wsl.exe", "-d", "Ubuntu"), Profile.Source.DISCOVERED);
        profiles.discoverInBackground(() -> List.of(ubuntu), callbacks::add);
        Runnable completion = callbacks.poll(5, TimeUnit.SECONDS);
        assertNotNull(completion);
        assertNull(opened.get());
        completion.run();
        assertEquals(ubuntu, opened.get());
        AtomicReference<Profile> later = new AtomicReference<>();
        profiles.whenDefaultReady(later::set);
        assertEquals(ubuntu, later.get());
    }

    @Test
    void missingDefaultFallsBackAfterDiscoveryFails() throws Exception {
        Settings settings = new Settings(directory.resolve("settings.properties"));
        settings.setDefaultProfileId("uninstalled");
        ShellProfiles profiles = new ShellProfiles(settings, "Default Shell");
        AtomicReference<Profile> opened = new AtomicReference<>();
        profiles.whenDefaultReady(opened::set);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        profiles.discoverInBackground(
                () -> {
                    throw new IllegalStateException("unavailable");
                },
                callbacks::add);
        Runnable completion = callbacks.poll(5, TimeUnit.SECONDS);
        assertNotNull(completion);
        completion.run();
        assertEquals("system", opened.get().id());
    }

    @Test
    void systemAndUserDefaultsDoNotWaitForDiscovery() {
        Settings settings = new Settings(directory.resolve("settings.properties"));
        ShellProfiles profiles = new ShellProfiles(settings, "Default Shell");
        AtomicReference<Profile> opened = new AtomicReference<>();
        profiles.whenDefaultReady(opened::set);
        assertEquals("system", opened.get().id());
        Profile user = Profile.of("user-custom", "Custom", List.of("custom"), Profile.Source.USER);
        profiles.setUserProfiles(List.of(user));
        settings.setDefaultProfileId(user.id());
        profiles.whenDefaultReady(opened::set);
        assertEquals(user, opened.get());
    }
}
