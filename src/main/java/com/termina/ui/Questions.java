package com.termina.ui;

import java.util.List;

import com.termina.process.RunningProgram;

import static com.termina.i18n.Messages.tr;

/**
 * The words of every question the app asks before it acts (CONF-12, Pauta 6.0.1): one method for
 * each kind, each a pure function of what the question is about. {@link Confirm} shows one; a test
 * reads one with no dialog, and answers it through {@link Confirm#answerWith}.
 *
 * <p>The title is the command's own name, the header names the thing and what happens to it, and
 * the confirming button is the action's verb, never OK or Yes (CONF-01).
 */
final class Questions {

    private Questions() {}

    /**
     * One question.
     *
     * @param title the dialog's title, usually the command's own name
     * @param header names the thing and what happens to it
     * @param detail what it means, or an empty string for none
     * @param action the confirming button's label: the action's verb
     * @param danger whether the action ends or destroys something: the button is styled so, and
     *     Cancel is the default whatever {@code cancelIsDefault} says (CONF-03, CONF-13)
     * @param cancelIsDefault whether Enter answers Cancel
     */
    record Question(
            String title, String header, String detail, String action, boolean danger, boolean cancelIsDefault) {

        /** Whether Cancel is the default button: asked for, or the action is dangerous. */
        boolean cancelDefault() {
            return danger || cancelIsDefault;
        }
    }

    /** A removal or a reset: dangerous, and Enter keeps what the user has. */
    private static Question removal(String title, String header, String detail, String action) {
        return new Question(title, header, detail, action, true, true);
    }

    /** Settings ▸ Reset All Settings. */
    static Question resetSettings() {
        return removal(
                tr("settings.reset"),
                tr("settings.reset.confirmTitle"),
                tr("settings.reset.confirmDetail"),
                tr("settings.reset.confirm"));
    }

    /** Removing the password or token from the sync address, which may be its only copy. */
    static Question removeSyncCredentials() {
        return removal(
                tr("settings.sync.removeCredentials"),
                tr("settings.sync.removeCredentials.confirmTitle"),
                tr("settings.sync.removeCredentials.confirmDetail"),
                tr("settings.sync.removeCredentials"));
    }

    /** Removing an imported theme: its file is deleted. */
    static Question removeTheme(String name) {
        return removal(
                tr("settings.removeTheme.button"),
                tr("settings.removeTheme.confirmTitle", name),
                tr("settings.removeTheme.confirmDetail"),
                tr("settings.removeTheme.button"));
    }

    /**
     * Resetting one key binding to a chord another command now holds.
     *
     * @param chord the default chord going back, as displayed
     * @param holder the command that loses it
     * @param command the command being reset
     */
    static Question resetKeyTaken(String chord, String holder, String command) {
        return removal(
                tr("settings.keys.reset"),
                tr("settings.keys.resetTaken.title", chord),
                tr("settings.keys.resetTaken.detail", holder, command),
                tr("settings.keys.reset"));
    }

    /** Resetting every key binding. */
    static Question resetAllKeys() {
        return removal(
                tr("settings.keys.resetAll"),
                tr("settings.keys.resetAll.confirmTitle"),
                tr("settings.keys.resetAll.confirmDetail"),
                tr("settings.keys.resetAll"));
    }

    /** Removing one of the user's own shell profiles. */
    static Question removeProfile(String name) {
        return removal(
                tr("settings.profileRemove"),
                tr("settings.profileRemove.confirmTitle", name),
                tr("settings.profileRemove.confirmDetail"),
                tr("settings.profileRemove"));
    }

    /**
     * A paste with a line break in it, or a very long one. It sends text and removes nothing, so
     * it is not dangerous, and Paste is the default: most such pastes are meant.
     *
     * @param chars the paste's length
     * @param lines how many lines it has
     * @param large whether it is asked about for its length, not its line breaks
     */
    static Question paste(int chars, int lines, boolean large) {
        return new Question(
                com.termina.AppInfo.NAME,
                large ? tr("dialog.pasteLarge", chars) : tr("dialog.pasteMultiline", lines),
                large ? tr("dialog.pasteLargeDetail") : tr("dialog.pasteMultilineDetail"),
                tr("dialog.paste"),
                false,
                false);
    }

    /** Closing a tab or a window while programs run in it: it ends them. */
    static Question closeRunning(List<String> running, int tabCount) {
        return removal(
                com.termina.AppInfo.NAME,
                tabCount == 1 ? tr("dialog.closeRunning") : tr("dialog.closeRunningTabs"),
                runningSummary(running),
                tr("dialog.closeAnyway"));
    }

    /** The same for the whole application: the button names the action, Quit. */
    static Question quitRunning(List<String> running) {
        return removal(
                com.termina.AppInfo.NAME,
                tr("dialog.quitRunning", com.termina.AppInfo.NAME),
                runningSummary(running),
                tr("dialog.quit"));
    }

    /** Names a few, then counts: a dialog listing forty processes is not telling anyone anything. */
    static String runningSummary(List<String> running) {
        if (running.size() <= RunningProgram.MAX_NAMED) {
            return tr("dialog.closeRunningDetail", String.join(", ", running));
        }
        List<String> named = running.subList(0, RunningProgram.MAX_NAMED);
        return tr("dialog.closeRunningMore", String.join(", ", named), running.size() - RunningProgram.MAX_NAMED);
    }
}
