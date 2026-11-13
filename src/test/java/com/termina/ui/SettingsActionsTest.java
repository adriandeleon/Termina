package com.termina.ui;

import java.util.HashSet;
import java.util.Set;

import com.termina.i18n.Messages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings window's actions as commands (KBD-01, Pauta 6.0.1): which can run, and why not. */
class SettingsActionsTest {

    /** Nothing in the way: an imported theme in use, a profile selected, connected, gh there. */
    private static SettingsActions.State state(
            boolean themeBuiltIn,
            boolean profileSelected,
            boolean connected,
            boolean syncing,
            boolean waiting,
            boolean credentials,
            boolean gitHub) {
        return new SettingsActions.State(
                themeBuiltIn, profileSelected, connected, syncing, waiting, credentials, gitHub);
    }

    private static final SettingsActions.State FRESH = state(true, false, false, false, false, false, false);

    @Test
    void everyActionHasAnIdOfItsOwnAndIsFoundByIt() {
        Set<String> ids = new HashSet<>();
        for (SettingsActions.Action action : SettingsActions.Action.values()) {
            assertTrue(ids.add(action.id()), action.id() + " twice");
            assertEquals(action, SettingsActions.Action.byId(action.id()));
            // KBD-02: dotted, grouped by area.
            assertTrue(action.id().matches("(settings|sync)\\.[a-zA-Z]+"), action.id());
        }
        assertNull(SettingsActions.Action.byId("settings"));
        assertNull(SettingsActions.Action.byId(null));
    }

    @Test
    void whatNeedsNothingCanAlwaysRun() {
        for (SettingsActions.Action action : new SettingsActions.Action[] {
            SettingsActions.Action.RESET,
            SettingsActions.Action.IMPORT_THEME,
            SettingsActions.Action.RESET_SHORTCUTS,
            SettingsActions.Action.ADD_PROFILE,
            SettingsActions.Action.SYNC_CONNECT
        }) {
            assertNull(SettingsActions.unavailableKey(action, FRESH), action.id());
        }
    }

    @Test
    void onlyAnImportedThemeAndASelectedProfileCanBeRemoved() {
        assertEquals(
                "palette.disabled.themeBuiltIn",
                SettingsActions.unavailableKey(SettingsActions.Action.REMOVE_THEME, FRESH));
        assertNull(SettingsActions.unavailableKey(
                SettingsActions.Action.REMOVE_THEME, state(false, false, false, false, false, false, false)));
        assertEquals(
                "palette.disabled.noProfileSelected",
                SettingsActions.unavailableKey(SettingsActions.Action.REMOVE_PROFILE, FRESH));
        assertNull(SettingsActions.unavailableKey(
                SettingsActions.Action.REMOVE_PROFILE, state(true, true, false, false, false, false, false)));
    }

    @Test
    void theSyncActionsFollowWhereTheSyncStands() {
        SettingsActions.State withGh = state(true, false, false, false, false, false, true);
        SettingsActions.State connected = state(true, false, true, false, false, true, true);
        SettingsActions.State syncing = state(true, false, true, true, true, false, true);
        SettingsActions.State waiting = state(true, false, true, false, true, false, true);

        assertNull(SettingsActions.unavailableKey(SettingsActions.Action.SYNC_CREATE, withGh));
        assertEquals(
                "palette.disabled.cannotCreateRepository",
                SettingsActions.unavailableKey(SettingsActions.Action.SYNC_CREATE, FRESH));
        assertEquals(
                "palette.disabled.cannotCreateRepository",
                SettingsActions.unavailableKey(SettingsActions.Action.SYNC_CREATE, connected));

        assertNull(SettingsActions.unavailableKey(SettingsActions.Action.SYNC_CANCEL, syncing));
        assertEquals(
                "palette.disabled.noSyncRunning",
                SettingsActions.unavailableKey(SettingsActions.Action.SYNC_CANCEL, connected));

        assertNull(SettingsActions.unavailableKey(SettingsActions.Action.SYNC_ANYWAY, waiting));
        // Not while one is running: the go-ahead is for the run that stopped.
        assertEquals(
                "palette.disabled.noSyncWaiting",
                SettingsActions.unavailableKey(SettingsActions.Action.SYNC_ANYWAY, syncing));
        assertEquals(
                "palette.disabled.noSyncWaiting",
                SettingsActions.unavailableKey(SettingsActions.Action.SYNC_ANYWAY, connected));

        assertNull(SettingsActions.unavailableKey(SettingsActions.Action.SYNC_REMOVE_CREDENTIALS, connected));
        assertEquals(
                "palette.disabled.noCredentials",
                SettingsActions.unavailableKey(SettingsActions.Action.SYNC_REMOVE_CREDENTIALS, waiting));
        // A token typed but not connected yet is the field's to refuse, not this command's to remove.
        assertEquals(
                "palette.disabled.noCredentials",
                SettingsActions.unavailableKey(
                        SettingsActions.Action.SYNC_REMOVE_CREDENTIALS,
                        state(true, false, false, false, false, true, true)));
    }

    @Test
    void everyReasonIsInTheCatalogue() {
        SettingsActions.State nothing = state(true, false, false, false, false, false, false);
        for (SettingsActions.Action action : SettingsActions.Action.values()) {
            String key = SettingsActions.unavailableKey(action, nothing);
            if (key != null) assertFalse(Messages.tr(key).equals(key), key);
        }
    }
}
