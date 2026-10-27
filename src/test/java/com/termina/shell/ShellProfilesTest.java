package com.termina.shell;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules that decide what a window offers, and which of them it opens. */
class ShellProfilesTest {

    private static Profile profile(String id, Profile.Source source) {
        return Profile.of(id, id, List.of("/bin/" + id), source);
    }

    private static final Profile SYSTEM = profile("system", Profile.Source.SYSTEM);

    @Test
    @DisplayName("the system shell is first, then what was found, then what was written")
    void mergesInSourceOrder() {
        List<Profile> merged = ShellProfiles.merge(
                SYSTEM,
                List.of(profile("pwsh", Profile.Source.DISCOVERED)),
                List.of(profile("mine", Profile.Source.USER)),
                Set.of());

        assertEquals(
                List.of("system", "pwsh", "mine"),
                merged.stream().map(Profile::id).toList());
    }

    @Test
    @DisplayName("a hidden profile is dropped, and the system shell cannot be hidden")
    void hidingLeavesSomethingToRun() {
        List<Profile> merged = ShellProfiles.merge(
                SYSTEM, List.of(profile("cmd", Profile.Source.DISCOVERED)), List.of(), Set.of("cmd", "system"));

        // The system entry is the one that is guaranteed to start something. A settings file that
        // hid it would leave a window with no way to open a tab at all.
        assertEquals(List.of("system"), merged.stream().map(Profile::id).toList());
    }

    @Test
    @DisplayName("a user profile reusing a discovered id is dropped rather than shadowing it")
    void idsAreUnique() {
        Profile discovered = profile("cmd", Profile.Source.DISCOVERED);
        List<Profile> merged = ShellProfiles.merge(
                SYSTEM, List.of(discovered), List.of(profile("cmd", Profile.Source.USER)), Set.of());

        assertEquals(2, merged.size());
        // Which one wins has to be decided rather than left to list order: the id is what a stored
        // default and a chord both point at.
        assertSame(discovered, merged.get(1));
    }

    @Test
    @DisplayName("discovery finding the user's own shell does not list it twice")
    void doesNotDuplicateTheSystemShell() {
        // The ordinary case on macOS: $SHELL is zsh, and /etc/shells names the same binary. Two
        // entries running the identical command, one called "Default Shell" and one called "Zsh".
        Profile system = Profile.of("system", "Default Shell", List.of("/bin/zsh", "-l"), Profile.Source.SYSTEM);
        Profile sameShell = Profile.of("shell-zsh", "Zsh", List.of("/bin/zsh", "-l"), Profile.Source.DISCOVERED);
        Profile other = Profile.of("shell-bash", "Bash", List.of("/bin/bash", "-l"), Profile.Source.DISCOVERED);

        List<Profile> merged = ShellProfiles.merge(system, List.of(sameShell, other), List.of(), Set.of());

        assertEquals(
                List.of("system", "shell-bash"),
                merged.stream().map(Profile::id).toList());
    }

    @Test
    @DisplayName("$SHELL and /etc/shells spelling one binary two ways is still one shell")
    void doesNotDuplicateTheSystemShellUnderAnotherSpelling() {
        // Merged /usr: $SHELL says /usr/bin/zsh, /etc/shells says /bin/zsh. And nu: the system entry
        // always adds -l, discovery does not for shells that do not take it.
        Profile system = Profile.of("system", "Default Shell", List.of("/usr/bin/zsh", "-l"), Profile.Source.SYSTEM);
        Profile sameShell = Profile.of("shell-zsh", "Zsh", List.of("/bin/zsh", "-l"), Profile.Source.DISCOVERED);
        Profile other = Profile.of("shell-bash", "Bash", List.of("/bin/bash", "-l"), Profile.Source.DISCOVERED);
        java.util.function.UnaryOperator<String> real = path -> path.replace("/usr/bin/", "/bin/");

        List<Profile> merged = ShellProfiles.merge(system, List.of(sameShell, other), List.of(), Set.of(), real);
        assertEquals(
                List.of("system", "shell-bash"),
                merged.stream().map(Profile::id).toList());

        Profile systemNu = Profile.of("system", "Default Shell", List.of("/usr/bin/nu", "-l"), Profile.Source.SYSTEM);
        Profile nu = Profile.of("shell-nu", "Nushell", List.of("/usr/bin/nu"), Profile.Source.DISCOVERED);
        assertEquals(
                List.of("system"),
                ShellProfiles.merge(systemNu, List.of(nu), List.of(), Set.of()).stream()
                        .map(Profile::id)
                        .toList());
    }

    @Test
    @DisplayName("the real-path lookup follows symlinks and leaves what it cannot resolve alone")
    void realExecutableFollowsLinks(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path target = java.nio.file.Files.createFile(dir.resolve("zsh"));
        java.nio.file.Path link = java.nio.file.Files.createSymbolicLink(dir.resolve("zsh-link"), target);
        assertEquals(target.toRealPath().toString(), ShellProfiles.realExecutable(link.toString()));
        assertEquals("no-such-shell", ShellProfiles.realExecutable("no-such-shell"));
    }

    @Test
    @DisplayName("a new profile's id is free of hidden profiles and of anything left stored")
    void aNewIdAvoidsEverythingTaken() {
        assertEquals("user-new-profile", ShellProfiles.newUserId("New Profile", Set.of()));
        assertEquals("user-new-profile-2", ShellProfiles.newUserId("New Profile", Set.of("user-new-profile")));
        assertEquals("user-profile", ShellProfiles.newUserId("!!!", Set.of()));
    }

    @Test
    @DisplayName("deleting a profile takes its style, default and hidden entry with it")
    void removingAProfileForgetsItsState(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var settings = new com.termina.config.Settings(dir.resolve("settings.properties"));
        var profiles = new ShellProfiles(settings, "Default Shell");
        Profile created = profiles.newUserProfile("New Profile").withCommandLine("/bin/sh");
        profiles.setUserProfiles(List.of(created));
        settings.setDefaultProfileId(created.id());
        settings.setProfileStyle(created.id(), new com.termina.config.Settings.ProfileStyle("", 18.0, "editora-light"));
        profiles.setHidden(created.id(), true);
        profiles.setHidden("shell-fish", true);

        profiles.removeUserProfile(created.id());

        assertEquals(List.of(), profiles.userProfiles());
        assertEquals("", settings.defaultProfileId());
        assertTrue(settings.profileStyle(created.id()).isEmpty());
        assertEquals(Set.of("shell-fish"), profiles.hiddenIds());

        // The next one gets the same id, and none of the old one's state.
        Profile next = profiles.newUserProfile("New Profile");
        assertEquals(created.id(), next.id());
    }

    @Test
    @DisplayName("adding while a user profile is hidden gives the new one an id of its own")
    void aNewProfileDoesNotTakeAHiddenOnesId(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var settings = new com.termina.config.Settings(dir.resolve("settings.properties"));
        var profiles = new ShellProfiles(settings, "Default Shell");
        Profile first = profiles.newUserProfile("New Profile").withCommandLine("/bin/sh");
        profiles.setUserProfiles(List.of(first));
        profiles.setHidden(first.id(), true);

        Profile second = profiles.newUserProfile("New Profile");
        assertTrue(!second.id().equals(first.id()), second.id());
    }

    @Test
    @DisplayName("an id something is still stored against is not reused")
    void aNewProfileDoesNotInheritLeftovers(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        // A file written before deletion cleared up: the style and the default are still there.
        var settings = new com.termina.config.Settings(dir.resolve("settings.properties"));
        settings.setDefaultProfileId("user-new-profile");
        settings.setProfileStyle(
                "user-new-profile-2", new com.termina.config.Settings.ProfileStyle("", null, "editora-light"));
        var profiles = new ShellProfiles(settings, "Default Shell");
        assertEquals(
                "user-new-profile-3", profiles.newUserProfile("New Profile").id());
    }

    @Test
    @DisplayName("the same shell started differently is still its own entry")
    void keepsAShellWithDifferentArguments() {
        Profile system = Profile.of("system", "Default Shell", List.of("/bin/zsh", "-l"), Profile.Source.SYSTEM);
        // A Homebrew zsh at another path is a different shell as far as anyone is concerned.
        Profile elsewhere =
                Profile.of("shell-zsh", "Zsh", List.of("/opt/homebrew/bin/zsh", "-l"), Profile.Source.DISCOVERED);

        List<Profile> merged = ShellProfiles.merge(system, List.of(elsewhere), List.of(), Set.of());

        assertEquals(2, merged.size());
    }

    @Test
    @DisplayName("a profile with nothing to run is not a profile")
    void dropsEmptyCommands() {
        Profile empty = new Profile("broken", "Broken", List.of(), "", Profile.Source.USER);
        List<Profile> merged = ShellProfiles.merge(SYSTEM, List.of(), List.of(empty), Set.of());
        assertEquals(List.of("system"), merged.stream().map(Profile::id).toList());
    }

    @Test
    @DisplayName("the stored default is honoured")
    void resolvesTheStoredDefault() {
        List<Profile> all = List.of(SYSTEM, profile("pwsh", Profile.Source.DISCOVERED));
        assertEquals("pwsh", ShellProfiles.resolveDefault(all, "pwsh").id());
    }

    @Test
    @DisplayName("a default naming a shell that has since been uninstalled still opens a terminal")
    void fallsBackWhenTheDefaultIsGone() {
        List<Profile> all = List.of(SYSTEM);
        // The realistic case: the setting names a WSL distribution that has been unregistered, or a
        // profile deleted by hand in the settings file. Refusing to open a tab is never the answer.
        assertEquals("system", ShellProfiles.resolveDefault(all, "wsl-ubuntu").id());
        assertEquals("system", ShellProfiles.resolveDefault(all, "").id());
        assertEquals("system", ShellProfiles.resolveDefault(all, null).id());
    }

    @Test
    @DisplayName("nothing at all is null rather than an exception")
    void resolvesNothingCleanly() {
        assertEquals(null, ShellProfiles.resolveDefault(List.of(), "anything"));
        assertEquals(null, ShellProfiles.resolveDefault(null, "anything"));
    }

    @Test
    @DisplayName("an id already taken gets a suffix rather than colliding")
    void idsAreMadeFree() {
        assertEquals("mine", ShellProfiles.freeId("mine", Set.of()));
        assertEquals("mine-2", ShellProfiles.freeId("mine", Set.of("mine")));
        assertEquals("mine-3", ShellProfiles.freeId("mine", Set.of("mine", "mine-2")));
    }

    @Test
    @DisplayName("a profile's own directory wins over the tab it was opened from")
    void profileDirectoryWins() {
        Profile pinned = profile("logs", Profile.Source.USER).withWorkingDirectory("/var/log");
        assertEquals("/var/log", pinned.toLaunchOptions("/home/me/src").workingDirectory());
    }

    @Test
    @DisplayName("a profile with no directory of its own inherits the current tab's")
    void inheritsTheCurrentDirectory() {
        assertEquals("/home/me/src", SYSTEM.toLaunchOptions("/home/me/src").workingDirectory());
    }

    @Test
    @DisplayName("a profile's command replaces the shell rather than running inside one")
    void commandIsTheShell() {
        var options = profile("pwsh", Profile.Source.DISCOVERED).toLaunchOptions("");
        assertTrue(options.hasCommand());
        assertEquals(List.of("/bin/pwsh"), options.command());
        // Blank, so the launcher runs the profile's command and not the configured shell path.
        assertEquals("", options.shell());
    }

    @Test
    @DisplayName("only user profiles can be edited")
    void editabilityFollowsSource() {
        assertTrue(profile("mine", Profile.Source.USER).isEditable());
        assertTrue(!profile("cmd", Profile.Source.DISCOVERED).isEditable());
        assertTrue(!SYSTEM.isEditable());
    }

    @Test
    @DisplayName("a command survives the round trip through the settings file")
    void commandLineRoundTrips() {
        Profile written = new Profile(
                "wsl",
                "Ubuntu",
                List.of("C:\\Program Files\\WSL\\wsl.exe", "-d", "Ubuntu 22.04"),
                "",
                Profile.Source.USER);
        Profile read = written.withCommandLine(written.commandLine());
        assertEquals(written.command(), read.command(), "a path with a space must stay one argument");
        assertNotNull(read.commandLine());
    }

    @Test
    void aProfilesLaunchOptionsCarryItsIdThroughADirectoryChange() {
        var profile = new Profile(
                "wsl-ubuntu", "Ubuntu", java.util.List.of("wsl.exe", "-d", "Ubuntu"), "", Profile.Source.DISCOVERED);
        var options = profile.toLaunchOptions("/home");
        org.junit.jupiter.api.Assertions.assertEquals("wsl-ubuntu", options.profileId());
        org.junit.jupiter.api.Assertions.assertEquals(
                "wsl-ubuntu", options.withWorkingDirectory("/tmp").profileId());
        org.junit.jupiter.api.Assertions.assertEquals(
                "", com.termina.pty.LaunchOptions.ofShell("/bin/sh").profileId());
    }
}
