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

    /**
     * A popup window as the guard sees it.
     *
     * @param menu whether it is a menu — a context menu, a menu bar's dropdown, the profile list —
     *     rather than a tooltip or another popup that never wants keys
     * @param showing whether it is on screen
     * @param ownedHere whether it hangs off this window, directly or through a parent menu
     */
    record Popup(boolean menu, boolean showing, boolean ownedHere) {}

    /**
     * Whether one of this window's menus is open.
     *
     * <p>Every menu the window owns, rather than a list of known ones: a tab header's context menu
     * was missing from the list, and reclaiming focus under it is the "dismissed on the way to being
     * shown" family. Tooltips are popups too and are left out on purpose: hovering a tab before
     * clicking it is the ordinary way to arrive there, and a tooltip that held the guard off would
     * leave focus on the tab strip — the bug the guard exists for.
     */
    static boolean anyMenuShowing(java.util.List<Popup> popups) {
        for (Popup popup : popups) {
            if (popup.menu() && popup.showing() && popup.ownedHere()) return true;
        }
        return false;
    }
}
