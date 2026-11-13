package com.termina.ui;

import org.jspecify.annotations.Nullable;

/**
 * The window's rules, as pure decisions (QUAL-05, Pauta 2.0.2): what shows, what is offered, which
 * tab a number means. No JavaFX type in sight, so each is tested without the toolkit
 * ({@code WindowRulesPureTest}); {@code TerminalWindow} and {@code SettingsWindow} ask and apply.
 */
final class WindowRules {

    /** The first part of a profile chord's command id: {@code newTabProfile3}. */
    static final String PROFILE_CHORD_ID = "newTabProfile";

    /** The first part of a tab chord's command id: {@code goToTab3}. */
    static final String TAB_CHORD_ID = "goToTab";

    /** Tab and profile chords run 1 to 9; 9 is the last tab, however many there are. */
    static final int TAB_CHORD_COUNT = 9;

    private WindowRules() {}

    /**
     * Whether the window draws its own title bar, with the menus in it.
     *
     * <p>That is JavaFX 27's extended stage style: the platform keeps its window buttons but gives
     * up the title bar, and a {@code HeaderBar} at the top of the scene takes its place. Three
     * things have to agree. The setting, obviously. The menus have to be in the window — under a
     * screen menu bar there is nothing to merge, and an extended window there would be a bare strip
     * under the traffic lights. And the platform has to support the style at all; JavaFX downgrades
     * an unsupported {@code EXTENDED} to {@code DECORATED} silently, which would leave the header
     * bar drawn <em>under</em> a native title bar as a second row.
     */
    static boolean usesHeaderBar(boolean systemMenuBar, boolean menuBarInTitleBar, boolean extendedWindowsSupported) {
        return !systemMenuBar && menuBarInTitleBar && extendedWindowsSupported;
    }

    /**
     * Whether to offer the title-bar option in Settings. The checkbox is disabled with the reason
     * on its row where it could do nothing, which is the same rule as the menu-bar toggle's.
     */
    static boolean offersHeaderBarOption(boolean systemMenuBar, boolean extendedWindowsSupported) {
        return !systemMenuBar && extendedWindowsSupported;
    }

    /**
     * Whether the menu bar should take up a row in the window.
     *
     * <p>False on macOS whatever the setting says: the menus are in the screen menu bar, and the
     * node only remains in the scene graph because that is what JavaFX forwards from. Leaving it
     * measurable there costs a band of empty chrome above the terminal.
     */
    static boolean menuBarOccupiesSpace(boolean systemMenuBar, boolean showMenuBar) {
        return !systemMenuBar && showMenuBar;
    }

    /**
     * Whether showing and hiding the menu bar can do anything: not under a system menu bar, where
     * there is nothing in the window to hide.
     *
     * <p>The command, its View row and the right-click menu's checkbox are there on every OS all
     * the same (OS-01, Pauta 6.0.1): where this is false they are disabled, and the command says
     * why ({@link #menuBarToggleUnavailableKey}), as the Settings row for it always has. Hidden,
     * they were a command one OS's users could not find and the others' could.
     */
    static boolean offersMenuBarToggle(boolean systemMenuBar) {
        return !systemMenuBar;
    }

    /**
     * Whether close requests that arrived together are the system's Quit rather than windows
     * being closed (WIN-13, Pauta 6.0.1): the macOS application menu's Quit and the Dock's reach
     * a JavaFX app as a close request on every window in one turn. One window asked to close is
     * that window closing, however many there are; with a single window open the two are the same
     * thing and it is closed as a window, which asks the same question.
     *
     * @param requested how many windows were asked to close in one turn of the event loop
     * @param open how many terminal windows there are
     */
    static boolean closeRequestsAreAQuit(int requested, int open) {
        return open > 1 && requested >= open;
    }

    /** The catalogue key of why the menu bar cannot be hidden here, or null when it can. */
    static @Nullable String menuBarToggleUnavailableKey(boolean systemMenuBar) {
        return offersMenuBarToggle(systemMenuBar) ? null : "settings.showMenuBar.mac";
    }

    /**
     * Why the keyboard cannot be put in the menu bar, as a catalogue key, or null when it can: under
     * a system menu bar there is none in the window, and a hidden one has nothing to walk.
     */
    static @Nullable String focusMenuBarUnavailableKey(boolean systemMenuBar, boolean showMenuBar) {
        if (systemMenuBar) return "settings.showMenuBar.mac";
        return showMenuBar ? null : "palette.disabled.menuBarHidden";
    }

    /** Whether the tab strip is worth its row of chrome. */
    static boolean shouldShowTabBar(int tabCount, boolean hideWhenSingle) {
        return !hideWhenSingle || tabCount > 1;
    }

    /**
     * Whether the reference lists a binding, as far as profiles go: the profile chords only for
     * profiles that exist. Nine "New Tab With Profile" rows on a machine with one profile is eight
     * rows of chords that do nothing.
     */
    static boolean listsProfileChord(@Nullable String id, int profileCount) {
        int number = profileNumber(id);
        return number == 0 || number <= profileCount;
    }

    /**
     * Why a UI zoom command cannot run at {@code percent}, as a catalogue key, or null when it can
     * or {@code id} is another command: there is no larger than the largest, no smaller than the
     * smallest, and nothing to reset at 100%.
     */
    static @Nullable String uiZoomUnavailableKey(String id, int percent) {
        return switch (id) {
            case "uiZoomIn" ->
                percent >= com.termina.config.Settings.MAX_UI_ZOOM ? "palette.disabled.uiZoomLargest" : null;
            case "uiZoomOut" ->
                percent <= com.termina.config.Settings.MIN_UI_ZOOM ? "palette.disabled.uiZoomSmallest" : null;
            case "uiZoomReset" ->
                percent == com.termina.config.Settings.DEFAULT_UI_ZOOM ? "palette.disabled.uiZoomDefault" : null;
            default -> null;
        };
    }

    /**
     * Whether a command discards something that does not come back (MENU-12, Pauta 6.0.1), so its
     * row is drawn in the danger color wherever a menu lists it. Clearing the scrollback is one.
     * Closing a tab is not: it is how a session ends, it asks first when something is running,
     * and a closed tab can be reopened.
     */
    static boolean isDestructive(@Nullable String id) {
        return "clearScrollback".equals(id);
    }

    /** Which profile a numbered profile command opens, 1 to 9; 0 for any other id. */
    static int profileNumber(@Nullable String id) {
        if (id == null || !id.startsWith(PROFILE_CHORD_ID)) return 0;
        try {
            int number = Integer.parseInt(id.substring(PROFILE_CHORD_ID.length()));
            return number >= 1 && number <= TAB_CHORD_COUNT ? number : 0;
        } catch (NumberFormatException notANumberedChord) {
            return 0;
        }
    }

    /**
     * Whether the size and place the window has now are its own, to be remembered for next time.
     *
     * <p>Not while maximized, and not in full screen either: both report the screen's, and storing
     * that made the next launch open a screen-sized window at the corner — full screen was the case
     * missed, so closing a full-screen window did it. {@code Stage.setFullScreen} sets the property
     * before the platform resizes the window, so the resize that follows is already excluded, and
     * the one on the way out arrives after it is cleared, at the size being restored.
     */
    static boolean isRestoredGeometry(boolean maximized, boolean fullScreen) {
        return !maximized && !fullScreen;
    }

    /**
     * The tab a number selects, or -1 when there is none.
     *
     * <p>The last digit means the <em>last</em> tab rather than the ninth, which is the convention
     * every browser and terminal follows: with four tabs open, Alt+9 goes to the fourth. A number
     * past the end selects nothing rather than the nearest, because silently landing somewhere else
     * is worse than not moving.
     */
    static int tabIndexFor(int number, int tabCount) {
        if (tabCount <= 0 || number < 1 || number > TAB_CHORD_COUNT) return -1;
        if (number == TAB_CHORD_COUNT) return tabCount - 1;
        return number <= tabCount ? number - 1 : -1;
    }

    /**
     * The number whose chord selects a tab, or 0 when none does — the inverse of {@link
     * #tabIndexFor}. The last tab answers to 9 whatever its index; a tab past the eighth that is
     * not last has no chord.
     */
    static int tabNumberFor(int index, int tabCount) {
        if (index < 0 || index >= tabCount) return 0;
        if (index == tabCount - 1) return TAB_CHORD_COUNT;
        return index < TAB_CHORD_COUNT - 1 ? index + 1 : 0;
    }

    /**
     * Whether a command steps aside while a text field in the window has the keyboard.
     *
     * <p>The commands whose default chords are a text field's own editing keys on macOS — Cmd+C,
     * Cmd+V, Cmd+A, and Cmd+Z for Reopen Closed Tab — and the same commands elsewhere, where
     * Ctrl+Shift+Z is a field's redo. In the find bar they act on the query; acting on the terminal
     * behind it instead would copy a selection nobody can see and paste into the shell.
     */
    static boolean yieldsToTextField(@Nullable String id) {
        return switch (id == null ? "" : id) {
            case "copy", "paste", "selectAll", "reopenClosedTab" -> true;
            default -> false;
        };
    }

    /**
     * Whether a tab that failed to start should be replaced by one running the default shell.
     *
     * <p>Only when the window would otherwise be left empty — an empty window closes itself, and
     * the last one closing quits — and only when what failed was not the default shell already,
     * since retrying the same command would fail the same way and loop.
     *
     * @param tabCount tabs in the window, the failed one still among them
     * @param failedWasDefaultShell whether the failed launch was already the plain shell
     */
    static boolean shouldFallBackToDefaultShell(int tabCount, boolean failedWasDefaultShell) {
        return tabCount <= 1 && !failedWasDefaultShell;
    }

    /**
     * Whether a window whose tab failed to start stays open with no tabs, rather than closing.
     *
     * <p>When nothing else is left in it and nothing is being opened in its place. An empty window
     * otherwise closes itself, and the last one closing quits — which for a default shell that will
     * not start was the application vanishing, its error dialog with it. Kept open, it shows what
     * failed, and the menus and chords still reach Settings and New Tab.
     *
     * @param remainingTabs tabs that will be left once the failed one is gone
     * @param fallingBack whether the default shell is being opened instead
     */
    static boolean keepsWindowOpenEmpty(int remainingTabs, boolean fallingBack) {
        return remainingTabs <= 0 && !fallingBack;
    }

    /** Lines a paste holds, as the dialog counts them: a trailing break still ends a line. */
    static int lineCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) count++;
        }
        return count;
    }

    /**
     * Whether a settings change should refresh the controls: only while the window can be seen,
     * never from inside the refresh itself, and never for a change this window is making. The second
     * half is the re-entrancy guard — a reload sets every control under {@code loading}, so none of
     * them writes back, but a reload started by a reload would still be a loop waiting for one that
     * does. The third is the cost: a reload re-sets some thirty controls, resolves every key binding
     * and rebuilds the profile list and the preview, which a spinner tick is not worth.
     */
    static boolean shouldReload(boolean showing, boolean reloading, boolean writing) {
        return showing && !reloading && !writing;
    }

    /**
     * The index the arrow keys should land on from {@code from}, stepping by {@code step} past
     * anything that is not selectable, or -1 when there is nothing further that way.
     */
    static int nextSelectable(int from, int step, int size, java.util.function.IntPredicate selectable) {
        for (int i = from + step; i >= 0 && i < size; i += step) {
            if (selectable.test(i)) return i;
        }
        return -1;
    }
}
