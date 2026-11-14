package com.termina.config;

import java.util.Map;
import java.util.TreeMap;

/**
 * The command ids that have been renamed, old to new, so that whatever was stored or synced under
 * an old id still reaches its command.
 *
 * <p>A command's id is what a stored key binding is keyed by: {@code keys.<id>} in
 * {@code settings.json}, and a line of the synced key-bindings files. Pauta 6.0.1 names five
 * commands outright (ABOUT-01, PAL-01, DOCS-06, MLOG-02, FIND-04), and those took the names it
 * gives; then every id without an area in front of it took one (KBD-02): {@code newTab} is
 * {@code file.newTab}, {@code copy} is {@code edit.copy}. The
 * settings file is brought forward by a migration step ({@link ConfigJson#MIGRATIONS}); the sync
 * files, which another computer on an older Termina may still be writing, are read through
 * {@link #renamed(Map)} every time.
 */
public final class CommandIds {

    /** The five ids Pauta 6.0.1 names outright, renamed first (the settings file's step from 1 to 2). */
    static final Map<String, String> NAMED_BY_PAUTA = Map.of(
            "about", "help.about",
            "commandPalette", "palette.show",
            "documentation", "help.documentation",
            "messageLog", "view.messageLog",
            "find", "find.show");

    /**
     * Every other id that had no area in front of it (KBD-02, Pauta 6.0.1): each took the area of
     * the menu it is in, or of the thing it acts on (the settings file's step from 2 to 3).
     */
    static final Map<String, String> GROUPED_BY_AREA = Map.ofEntries(
            Map.entry("actualSize", "view.actualSize"),
            Map.entry("checkForUpdates", "help.checkForUpdates"),
            Map.entry("clearScrollback", "edit.clearScrollback"),
            Map.entry("closeOtherTabs", "file.closeOtherTabs"),
            Map.entry("closeTab", "file.closeTab"),
            Map.entry("closeTabsToTheLeft", "file.closeTabsToTheLeft"),
            Map.entry("closeTabsToTheRight", "file.closeTabsToTheRight"),
            Map.entry("closeWindow", "file.closeWindow"),
            Map.entry("configureProfiles", "settings.configureProfiles"),
            Map.entry("copy", "edit.copy"),
            Map.entry("copyLinkAddress", "link.copyAddress"),
            Map.entry("debugLog", "help.debugLog"),
            Map.entry("duplicateTab", "tab.duplicate"),
            Map.entry("exportConfiguration", "settings.exportConfiguration"),
            Map.entry("findNext", "find.next"),
            Map.entry("findPrevious", "find.previous"),
            Map.entry("findRegex", "find.regex"),
            Map.entry("focusMenuBar", "view.focusMenuBar"),
            Map.entry("focusPaneDown", "pane.focusDown"),
            Map.entry("focusPaneLeft", "pane.focusLeft"),
            Map.entry("focusPaneRight", "pane.focusRight"),
            Map.entry("focusPaneUp", "pane.focusUp"),
            Map.entry("fullScreen", "view.fullScreen"),
            Map.entry("goToTab1", "tab.goTo1"),
            Map.entry("goToTab2", "tab.goTo2"),
            Map.entry("goToTab3", "tab.goTo3"),
            Map.entry("goToTab4", "tab.goTo4"),
            Map.entry("goToTab5", "tab.goTo5"),
            Map.entry("goToTab6", "tab.goTo6"),
            Map.entry("goToTab7", "tab.goTo7"),
            Map.entry("goToTab8", "tab.goTo8"),
            Map.entry("goToTab9", "tab.goTo9"),
            Map.entry("hideMenuBar", "view.toggleMenuBar"),
            Map.entry("importConfiguration", "settings.importConfiguration"),
            Map.entry("keyboardShortcuts", "help.keyboardShortcuts"),
            Map.entry("maximizePane", "pane.maximize"),
            Map.entry("movePaneToNewTab", "pane.moveToNewTab"),
            Map.entry("moveTabLeft", "tab.moveLeft"),
            Map.entry("moveTabRight", "tab.moveRight"),
            Map.entry("newTab", "file.newTab"),
            Map.entry("newTabProfile1", "file.newTabProfile1"),
            Map.entry("newTabProfile2", "file.newTabProfile2"),
            Map.entry("newTabProfile3", "file.newTabProfile3"),
            Map.entry("newTabProfile4", "file.newTabProfile4"),
            Map.entry("newTabProfile5", "file.newTabProfile5"),
            Map.entry("newTabProfile6", "file.newTabProfile6"),
            Map.entry("newTabProfile7", "file.newTabProfile7"),
            Map.entry("newTabProfile8", "file.newTabProfile8"),
            Map.entry("newTabProfile9", "file.newTabProfile9"),
            Map.entry("newWindow", "file.newWindow"),
            Map.entry("nextPrompt", "view.nextPrompt"),
            Map.entry("nextTab", "tab.next"),
            Map.entry("openLink", "link.open"),
            Map.entry("paste", "edit.paste"),
            Map.entry("pinTab", "tab.pin"),
            Map.entry("previousPrompt", "view.previousPrompt"),
            Map.entry("previousTab", "tab.previous"),
            Map.entry("renameTab", "tab.rename"),
            Map.entry("reopenClosedTab", "file.reopenClosedTab"),
            Map.entry("reportProblem", "help.reportProblem"),
            Map.entry("selectAll", "edit.selectAll"),
            Map.entry("selectLastOutput", "edit.selectLastOutput"),
            Map.entry("settings", "settings.show"),
            Map.entry("showStatusBar", "view.toggleStatusBar"),
            Map.entry("showTabBar", "view.toggleTabBar"),
            Map.entry("splitDown", "pane.splitDown"),
            Map.entry("splitRight", "pane.splitRight"),
            Map.entry("syncNow", "sync.now"),
            Map.entry("tabMenu", "tab.menu"),
            Map.entry("uiZoomIn", "view.uiZoomIn"),
            Map.entry("uiZoomOut", "view.uiZoomOut"),
            Map.entry("uiZoomReset", "view.uiZoomReset"),
            Map.entry("whatsNew", "help.whatsNew"),
            Map.entry("zoomIn", "view.zoomIn"),
            Map.entry("zoomOut", "view.zoomOut"));

    /** Old id to the id the command has now. */
    static final Map<String, String> RENAMED = all();

    private static Map<String, String> all() {
        Map<String, String> all = new TreeMap<>(NAMED_BY_PAUTA);
        all.putAll(GROUPED_BY_AREA);
        return Map.copyOf(all);
    }

    private CommandIds() {}

    /** The id a command has now, given one it has or used to have. */
    public static String current(String id) {
        return RENAMED.getOrDefault(id, id);
    }

    /**
     * A map keyed by command id, with every old id replaced by the current one. Where both are
     * there, the current id's value stands: it was written by a build that knew the new name.
     */
    public static Map<String, String> renamed(Map<String, String> byId) {
        return renamed(byId, RENAMED);
    }

    private static Map<String, String> renamed(Map<String, String> byId, Map<String, String> table) {
        Map<String, String> out = new TreeMap<>();
        byId.forEach((id, value) -> {
            if (!table.containsKey(id)) out.put(id, value);
        });
        byId.forEach((id, value) -> {
            String now = table.get(id);
            if (now != null) out.putIfAbsent(now, value);
        });
        return out;
    }

    /**
     * The settings file's step from schema 1 to 2: each {@code keys.<old id>} of the five Pauta
     * names becomes {@code keys.<new id>}. Every other key is left as it is.
     */
    static Map<String, String> migrateSettings(Map<String, String> values) {
        return migrateSettings(values, NAMED_BY_PAUTA);
    }

    /** The step from schema 2 to 3 (KBD-02): the same for every id that took an area. */
    static Map<String, String> groupByArea(Map<String, String> values) {
        return migrateSettings(values, GROUPED_BY_AREA);
    }

    private static Map<String, String> migrateSettings(Map<String, String> values, Map<String, String> table) {
        Map<String, String> bindings = new TreeMap<>();
        Map<String, String> out = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(Settings.KEYS_PREFIX) && key.length() > Settings.KEYS_PREFIX.length()) {
                bindings.put(key.substring(Settings.KEYS_PREFIX.length()), value);
            } else {
                out.put(key, value);
            }
        });
        renamed(bindings, table).forEach((id, value) -> out.put(Settings.KEYS_PREFIX + id, value));
        return out;
    }
}
