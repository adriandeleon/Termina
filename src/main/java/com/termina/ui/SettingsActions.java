package com.termina.ui;

import org.jspecify.annotations.Nullable;

/**
 * The actions of the Settings window that are not settings: its buttons, each of which is a
 * command (KBD-01, Pauta 6.0.1). The button and the command run the same {@link Action} through
 * the window, so the palette and a chord reach everything the pointer does.
 *
 * <p>Whether one can run now is decided here from a few facts, not by the window: the palette
 * asks every time it lists, and building the Settings window to answer would cost every opening
 * of the palette a window most sessions never show. JavaFX-free.
 */
final class SettingsActions {

    private SettingsActions() {}

    /** One action, by its command's id. */
    enum Action {
        RESET("settings.reset"),
        IMPORT_THEME("settings.importTheme"),
        REMOVE_THEME("settings.removeTheme"),
        RESET_SHORTCUTS("settings.resetShortcuts"),
        ADD_PROFILE("settings.addProfile"),
        REMOVE_PROFILE("settings.removeProfile"),
        SYNC_CONNECT("sync.connect"),
        SYNC_CREATE("sync.createRepository"),
        SYNC_CANCEL("sync.cancel"),
        SYNC_ANYWAY("sync.anyway"),
        SYNC_REMOVE_CREDENTIALS("sync.removeCredentials");

        private final String id;

        Action(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        /** The action a command id names, or null when it names none of these. */
        static @Nullable Action byId(@Nullable String id) {
            for (Action action : values()) {
                if (action.id.equals(id)) return action;
            }
            return null;
        }
    }

    /**
     * What the answers depend on.
     *
     * @param themeBuiltIn the theme in use is one of the built-in ones, which cannot be removed
     * @param profileSelected a profile of the user's own is selected on the Profiles page
     * @param connected settings sync is connected to a repository
     * @param syncing a sync is running now
     * @param waitingForGoAhead the last sync stopped to ask before removing many bindings
     * @param credentialsInAddress the sync address holds a password or a token
     * @param gitHubAvailable the {@code gh} command is installed and signed in
     */
    record State(
            boolean themeBuiltIn,
            boolean profileSelected,
            boolean connected,
            boolean syncing,
            boolean waitingForGoAhead,
            boolean credentialsInAddress,
            boolean gitHubAvailable) {}

    /** The catalogue key of why {@code action} cannot run now, or null when it can. */
    static @Nullable String unavailableKey(Action action, State state) {
        return switch (action) {
            case REMOVE_THEME -> state.themeBuiltIn() ? "palette.disabled.themeBuiltIn" : null;
            case REMOVE_PROFILE -> state.profileSelected() ? null : "palette.disabled.noProfileSelected";
            case SYNC_CREATE ->
                state.connected() || !state.gitHubAvailable() ? "palette.disabled.cannotCreateRepository" : null;
            case SYNC_CANCEL -> state.syncing() ? null : "palette.disabled.noSyncRunning";
            case SYNC_ANYWAY -> state.waitingForGoAhead() && !state.syncing() ? null : "palette.disabled.noSyncWaiting";
            case SYNC_REMOVE_CREDENTIALS ->
                state.connected() && state.credentialsInAddress() ? null : "palette.disabled.noCredentials";
            case RESET, IMPORT_THEME, RESET_SHORTCUTS, ADD_PROFILE, SYNC_CONNECT -> null;
        };
    }
}
