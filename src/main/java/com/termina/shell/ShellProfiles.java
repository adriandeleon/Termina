package com.termina.shell;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.termina.cli.Argv;
import com.termina.config.Settings;
import com.termina.pty.ShellLauncher;
import org.jspecify.annotations.Nullable;

/**
 * The profiles a window can open a tab as: the system shell, whatever is installed, and whatever
 * the user has written down.
 *
 * <p>Three sources with one rule between them — the system shell is always first and always
 * present, discovery fills in the middle, and user profiles come last. A window that finds nothing
 * and has nothing configured still offers exactly what it offered before this existed, which is
 * what keeps a machine with no PowerShell and no {@code /etc/shells} working.
 *
 * <p><b>Discovery does not run on the toolkit thread.</b> On Windows it starts {@code wsl.exe} and
 * waits for it, which is a subprocess in the middle of a menu opening. {@link #discoverInBackground}
 * does it once on a daemon thread and calls back when the answer is in; until then {@link #all}
 * reports the system and user profiles, which is a shorter list rather than a wrong one.
 */
public final class ShellProfiles {

    /** How many user profile blocks are read from the settings file. */
    static final int MAX_USER_PROFILES = 64;

    private static final String PREFIX = "profile.";

    private final Settings settings;
    private final String systemName;

    /** Written by the discovery thread, read by the toolkit thread. */
    private volatile List<Profile> discovered = List.of();

    private volatile boolean discoveryFinished;

    private final List<java.util.function.Consumer<@Nullable Profile>> pendingDefaults = new ArrayList<>();

    private ProfileConsumer onDiscovered = profiles -> {};

    /** Notified once discovery has an answer, so a menu built before then can be rebuilt. */
    @FunctionalInterface
    public interface ProfileConsumer {
        void accept(List<Profile> profiles);
    }

    /**
     * @param systemName what to call the system shell in the menu. Passed in rather than looked up
     *     so this package stays out of the message catalogue, and so the merge can be tested
     *     without one.
     */
    public ShellProfiles(Settings settings, String systemName) {
        this.settings = settings;
        this.systemName = systemName == null || systemName.isBlank()
                ? com.termina.i18n.Messages.tr("profile.systemShell")
                : systemName;
    }

    /** Runs discovery once, off the toolkit thread, and reports back through {@code onDone}. */
    public void discoverInBackground(ProfileConsumer onDone) {
        this.onDiscovered = onDone == null ? profiles -> {} : onDone;
        discoverInBackground(ShellDiscovery::discover, javafx.application.Platform::runLater);
    }

    void discoverInBackground(
            java.util.function.Supplier<List<Profile>> discovery, java.util.function.Consumer<Runnable> dispatch) {
        Thread thread = new Thread(
                () -> {
                    List<Profile> found;
                    try {
                        found = discovery.get();
                    } catch (RuntimeException e) {
                        // A machine that cannot be probed still gets its system and user profiles.
                        found = List.of();
                    }
                    List<Profile> result = found;
                    dispatch.accept(() -> {
                        discovered = result;
                        discoveryFinished = true;
                        List<java.util.function.Consumer<@Nullable Profile>> waiting = List.copyOf(pendingDefaults);
                        pendingDefaults.clear();
                        for (var callback : waiting) callback.accept(defaultProfile());
                        onDiscovered.accept(all());
                    });
                },
                "termina-shell-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    /** Called on the toolkit thread; waits only when the requested default still needs discovery. */
    public void whenDefaultReady(java.util.function.Consumer<@Nullable Profile> callback) {
        String id = settings.defaultProfileId();
        if (!discoveryFinished && !id.isBlank() && byId(id) == null) {
            pendingDefaults.add(callback);
        } else {
            callback.accept(defaultProfile());
        }
    }

    public boolean discoveryFinished() {
        return discoveryFinished;
    }

    // ---------------------------------------------------------------- the list

    /** Every profile on offer, in menu order. */
    public List<Profile> all() {
        return merge(systemProfile(), discovered, userProfiles(), hiddenIds(), ShellProfiles::realExecutable);
    }

    /** Resolved once per spelling: {@link #all} runs whenever a menu is built. */
    private static final Map<String, String> REAL_EXECUTABLES = new java.util.concurrent.ConcurrentHashMap<>();

    /** The file an executable path finally names, or the path as written when that cannot be answered. */
    static String realExecutable(String executable) {
        return REAL_EXECUTABLES.computeIfAbsent(executable, path -> {
            try {
                return java.nio.file.Path.of(path).toRealPath().toString();
            } catch (Exception e) {
                return path;
            }
        });
    }

    /**
     * The same list with nothing filtered out.
     *
     * <p>For the settings window, which has to show a hidden profile in order to offer the checkbox
     * that unhides it — a list that dropped them would make hiding one irreversible from the only
     * screen that can hide it.
     */
    public List<Profile> allIncludingHidden() {
        return merge(systemProfile(), discovered, userProfiles(), Set.of(), ShellProfiles::realExecutable);
    }

    /**
     * The system shell as a profile.
     *
     * <p>Its command is resolved through {@link ShellLauncher#shellCommand} — the same resolution a
     * tab has always used, honouring the shell setting and falling back the same way — so this entry
     * and the setting cannot disagree about what "your shell" means.
     */
    public Profile systemProfile() {
        return Profile.of(SYSTEM_ID, systemName, ShellLauncher.shellCommand(settings.shell()), Profile.Source.SYSTEM);
    }

    public static final String SYSTEM_ID = "system";

    /**
     * Merges the three sources, dropping hidden entries and any id already taken.
     *
     * <p>Later sources cannot displace earlier ones. A user profile that reuses a discovered id is
     * dropped rather than allowed to shadow it, because the id is what a stored default and a chord
     * both point at, and two profiles answering to one id makes which of them opens a question of
     * list order.
     */
    static List<Profile> merge(
            @Nullable Profile system,
            @Nullable List<Profile> discovered,
            @Nullable List<Profile> user,
            Set<String> hidden) {
        return merge(system, discovered, user, hidden, executable -> executable);
    }

    /**
     * @param realExecutable an executable as the file it finally names, symlinks followed — so that
     *     {@code $SHELL=/usr/bin/zsh} and {@code /etc/shells}' {@code /bin/zsh} on a merged-/usr system
     *     are recognised as one shell. Injected because the test machine's {@code /bin} is not the one
     *     being described.
     */
    static List<Profile> merge(
            @Nullable Profile system,
            @Nullable List<Profile> discovered,
            @Nullable List<Profile> user,
            Set<String> hidden,
            java.util.function.UnaryOperator<String> realExecutable) {
        Map<String, Profile> byId = new LinkedHashMap<>();
        // The system profile is never hidden: it is the one entry guaranteed to start something,
        // and a settings file that hid it would leave a window with no way to open a tab.
        if (system != null && system.isRunnable()) byId.put(system.id(), system);
        String systemExecutable = system != null && system.isRunnable()
                ? realExecutable.apply(system.command().get(0))
                : null;
        for (Profile profile : nullSafe(discovered)) {
            if (!profile.isRunnable() || hidden.contains(profile.id())) continue;
            // Discovery finds the user's own shell too, so on a machine whose $SHELL is zsh the
            // menu would carry "Default Shell" and "Zsh" running the same shell. One of them is
            // noise, and it is the discovered one: the system entry is what the default falls back
            // to and what the shell setting on the Terminal page controls. Compared by the file the
            // executable is, not by the command as written: the two sources spell the path
            // differently (/usr/bin/zsh against /bin/zsh), and the system entry always adds -l where
            // discovery adds it only for the shells that take it.
            if (systemExecutable != null
                    && systemExecutable.equals(
                            realExecutable.apply(profile.command().get(0)))) {
                continue;
            }
            byId.putIfAbsent(profile.id(), profile);
        }
        for (Profile profile : nullSafe(user)) {
            if (!profile.isRunnable() || hidden.contains(profile.id())) continue;
            byId.putIfAbsent(profile.id(), profile);
        }
        return List.copyOf(byId.values());
    }

    private static List<Profile> nullSafe(@Nullable List<Profile> profiles) {
        return profiles == null ? List.of() : profiles;
    }

    /** The profile a plain New Tab opens, or null when no profile can start anything. */
    public @Nullable Profile defaultProfile() {
        return resolveDefault(all(), settings.defaultProfileId());
    }

    /**
     * The profile a stored id names, or the first one.
     *
     * <p>Falling back rather than failing: the stored id names a shell that may since have been
     * uninstalled, a WSL distribution that has been unregistered, or a profile deleted by hand. In
     * every one of those the right answer is to open a terminal anyway.
     */
    static @Nullable Profile resolveDefault(@Nullable List<Profile> all, @Nullable String defaultId) {
        if (all == null || all.isEmpty()) return null;
        if (defaultId != null && !defaultId.isBlank()) {
            for (Profile profile : all) {
                if (profile.id().equals(defaultId.trim())) return profile;
            }
        }
        return all.get(0);
    }

    public @Nullable Profile byId(String id) {
        for (Profile profile : all()) {
            if (profile.id().equals(id)) return profile;
        }
        return null;
    }

    public void setDefaultProfileId(String id) {
        settings.setDefaultProfileId(id == null ? "" : id);
    }

    public String defaultProfileId() {
        return settings.defaultProfileId();
    }

    // ---------------------------------------------------------------- hiding

    /** Ids of discovered profiles the user does not want listed. */
    public Set<String> hiddenIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : settings.hiddenProfileIds().split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) ids.add(trimmed);
        }
        return ids;
    }

    public void setHidden(String id, boolean hidden) {
        Set<String> ids = hiddenIds();
        if (hidden) ids.add(id);
        else ids.remove(id);
        settings.setHiddenProfileIds(String.join(",", ids));
    }

    // ---------------------------------------------------------------- user profiles

    /**
     * The profiles written in the settings file.
     *
     * <p>Blocks are read by index and gaps are skipped rather than treated as the end of the list:
     * this file is meant to be hand-edited, and deleting a block should remove one profile rather
     * than every profile after it. An id is stored alongside the name so that renaming a profile
     * does not move whatever is pointing at it.
     */
    public List<Profile> userProfiles() {
        Map<String, String> block = settings.profileBlock();
        List<Profile> profiles = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        for (int index = 1; index <= MAX_USER_PROFILES; index++) {
            String name = block.get(PREFIX + index + ".name");
            String command = block.get(PREFIX + index + ".command");
            if (name == null || name.isBlank() || command == null || command.isBlank()) continue;
            String id = block.get(PREFIX + index + ".id");
            if (id == null || id.isBlank()) id = "user-" + index;
            id = freeId(id, taken);
            taken.add(id);
            profiles.add(new Profile(
                    id,
                    name.trim(),
                    Argv.split(command),
                    block.getOrDefault(PREFIX + index + ".directory", ""),
                    Profile.Source.USER));
        }
        return List.copyOf(profiles);
    }

    /** Replaces the whole user list in one write, so the UI re-applies once rather than per key. */
    public void setUserProfiles(List<Profile> profiles) {
        Map<String, String> block = new LinkedHashMap<>();
        int index = 1;
        for (Profile profile : nullSafe(profiles)) {
            if (!profile.isRunnable() || index > MAX_USER_PROFILES) continue;
            block.put(PREFIX + index + ".id", profile.id());
            block.put(PREFIX + index + ".name", profile.name());
            block.put(PREFIX + index + ".command", profile.commandLine());
            if (!profile.workingDirectory().isBlank()) {
                block.put(PREFIX + index + ".directory", profile.workingDirectory());
            }
            index++;
        }
        settings.replaceProfileBlock(block);
    }

    /**
     * A new, empty user profile with an id nothing else is using — or has used and left anything
     * behind under.
     *
     * <p>Against every profile, hidden ones included: taken from the menu's list, the id of a hidden
     * user profile looked free, the new profile got it, and the settings window then selected the
     * hidden one for editing. And against every id something is still stored for — a default, a
     * hidden entry, a style — so a profile deleted before {@link #removeUserProfile} cleared up after
     * itself cannot hand its theme and its default to the next one.
     */
    public Profile newUserProfile(String name) {
        Set<String> taken = new LinkedHashSet<>();
        for (Profile profile : allIncludingHidden()) taken.add(profile.id());
        for (Profile profile : userProfiles()) taken.add(profile.id());
        taken.addAll(hiddenIds());
        taken.addAll(settings.profileStyleIds());
        if (!settings.defaultProfileId().isBlank()) taken.add(settings.defaultProfileId());
        return new Profile(newUserId(name, taken), name, List.of(), "", Profile.Source.USER);
    }

    /** The id a new profile called {@code name} gets: {@code user-<slug>}, made free of {@code taken}. */
    static String newUserId(String name, Collection<String> taken) {
        String base = ShellDiscovery.slug(name == null ? "" : name);
        if (base.isEmpty()) base = "profile";
        return freeId("user-" + base, taken);
    }

    /**
     * Deletes a user profile and everything stored against its id: its style, the default if it was
     * the default, and its hidden entry.
     *
     * <p>Ids are reused — a second "New Profile" is {@code user-new-profile} again — so anything left
     * behind is inherited by the next profile that gets the id: delete one that was the default and
     * themed, click Add, and the new one was silently the default and themed.
     */
    public void removeUserProfile(String id) {
        List<Profile> user = new ArrayList<>(userProfiles());
        user.removeIf(profile -> profile.id().equals(id));
        setUserProfiles(user);
        settings.forgetProfile(id);
    }

    /** {@code id}, or {@code id-2}, {@code id-3}… until one is free. */
    static String freeId(String id, Collection<String> taken) {
        String base = id.toLowerCase(Locale.ROOT);
        if (!taken.contains(base)) return base;
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + "-" + suffix;
            if (!taken.contains(candidate)) return candidate;
        }
        return base + "-" + System.identityHashCode(taken);
    }
}
