package com.termina.ui;

/**
 * Whether keyboard focus should be handed back to the terminal.
 *
 * <p>Focus leaving the terminal is not one bug with one cause. Anything in the chrome that calls
 * {@code requestFocus} takes it — a click on a tab header does, whether or not the selection
 * changes, and `requestFocus` ignores the focus-traversable flag that keeps traversal out. Every
 * such path presents identically: a window that looks completely ready and silently swallows
 * everything typed into it. Rather than find them one at a time, the terminal takes focus back
 * whenever nothing else has a claim on it.
 *
 * <p>The claim is the whole of the rule. The command palette has a text field, an open menu
 * navigates with the keyboard, and a context menu is a popup that dismisses when focus moves —
 * reclaiming under any of those would break them, and the last would make right-click unusable.
 * So while one is open, focus is left exactly where it is.
 */
final class FocusGuard {

    private FocusGuard() {}

    /**
     * @param hasTerminal whether there is a terminal to give focus to — a window showing only the
     *     welcome state, or one being torn down, has none
     * @param ownerIsTerminal whether the terminal itself holds focus. Deliberately the terminal and
     *     not its subtree: its children are all non-traversable, so one of them holding focus means
     *     something took it, and the scrollbar swallowing the arrow keys is the same bug in
     *     miniature
     * @param overlayWantsKeys whether the palette, a menu or the context menu is open
     */
    static boolean shouldReclaim(boolean hasTerminal, boolean ownerIsTerminal, boolean overlayWantsKeys) {
        return hasTerminal && !ownerIsTerminal && !overlayWantsKeys;
    }
}
