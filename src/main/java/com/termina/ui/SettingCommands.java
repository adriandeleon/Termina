package com.termina.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.termina.config.Settings;
import com.termina.i18n.Messages;
import com.termina.shell.Profile;
import com.termina.shell.ShellProfiles;
import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/**
 * A palette command for every setting (SET-06, Pauta 2.0.2): a switch flips, a choice lists its
 * options, a value asks for a new one. Each is named from its Settings row — the row's title and
 * its description — so the palette and the window cannot call one setting two things.
 *
 * <p>Rows that need no command of their own are in {@link #COVERED_ELSEWHERE} with the reason, and
 * {@code SettingCommandsTest} fails on a Settings row in neither place.
 */
final class SettingCommands {

    /** What the commands need from the window that lists them. */
    interface Host {
        /** Asks for a value, starting from {@code current}; null when the user cancels. */
        @Nullable
        String ask(String title, String detail, String current);

        void report(MessageLog.Severity severity, String message);

        ShellProfiles profiles();

        @Nullable
        SettingsSync sync();

        /** Whether this platform can draw the menu bar in the title bar at all. */
        boolean offersMenuBarInTitleBar();
    }

    /** One command, the Settings row it belongs to, and why it cannot run now (null when it can). */
    record Entry(String key, MenuAction action, @Nullable String unavailable) {}

    /** Settings rows with no command here, and why: a command elsewhere, an action, or no value. */
    static final Map<String, String> COVERED_ELSEWHERE = Map.ofEntries(
            Map.entry("settings.theme", "the palette lists every theme (palette.theme)"),
            Map.entry("settings.showMenuBar", "View ▸ Hide Menu Bar, view.toggleMenuBar"),
            Map.entry("settings.showStatusBar", "View ▸ Show Status Bar, view.toggleStatusBar"),
            Map.entry("settings.simpleMode", "View ▸ Simple Mode, view.toggleSimpleMode"),
            Map.entry("settings.exportConfig", "the settings.exportConfiguration command"),
            Map.entry("settings.importConfig", "the settings.importConfiguration command"),
            Map.entry("settings.debugLog", "Help ▸ Debug Log, help.debugLog"),
            Map.entry("settings.importTheme", "an action, not a setting: the settings.importTheme command"),
            Map.entry("settings.keys.resetAll.title", "an action, not a setting: the settings.resetShortcuts command"),
            Map.entry("settings.settingsFile", "shows where the file is; nothing to set"),
            Map.entry("settings.sync.status", "shows how the last sync went; nothing to set"),
            Map.entry("settings.sync.keybindings", "says what sync carries; nothing to set"));

    private SettingCommands() {}

    /** Every setting's command, in the Settings window's order. */
    static List<Entry> all(Settings settings, Host host) {
        List<Entry> out = new ArrayList<>();
        // Appearance
        for (String family : MonospaceFonts.available()) {
            out.add(choice("settings.fontFamily", family, () -> settings.setFontFamily(family)));
        }
        out.add(number("settings.fontSize", host, () -> format(settings.fontSize()), settings::setFontSize));
        out.add(toggle("settings.hideTabBar", settings::hideTabBarWhenSingle, settings::setHideTabBarWhenSingle));
        out.add(toggle("settings.showTabBar", settings::showTabBar, settings::setShowTabBar));
        Entry titleBar =
                toggle("settings.menuBarInTitleBar", settings::menuBarInTitleBar, settings::setMenuBarInTitleBar);
        out.add(
                host.offersMenuBarInTitleBar()
                        ? titleBar
                        : new Entry(titleBar.key(), titleBar.action(), tr("palette.setting.notOnThisPlatform")));
        out.add(number(
                "settings.uiZoom",
                host,
                () -> String.valueOf(settings.uiZoom()),
                value -> UiZoom.change(settings, (int) Math.round(value))));
        out.add(toggle("settings.showScrollBar", settings::showScrollBar, settings::setShowScrollBar));
        out.add(number(
                "settings.windowOpacity",
                host,
                () -> String.valueOf(Math.round(settings.windowOpacity() * 100)),
                percent -> settings.setWindowOpacity(percent / 100)));
        out.add(choice("settings.language", tr("settings.language.automatic"), () -> settings.setUiLanguage("")));
        for (String code : Messages.available().keySet()) {
            out.add(choice("settings.language", Messages.languageName(code), () -> settings.setUiLanguage(code)));
        }
        // Terminal
        for (Settings.CursorShape shape : Settings.CursorShape.values()) {
            out.add(choice(
                    "settings.cursorShape",
                    tr("cursor." + shape.name().toLowerCase(Locale.ROOT)),
                    () -> settings.setCursorShape(shape)));
        }
        out.add(number(
                "settings.padding",
                host,
                () -> String.valueOf(settings.padding()),
                value -> settings.setPadding((int) Math.round(value))));
        out.add(toggle("settings.bell", settings::bell, settings::setBell));
        out.add(toggle("settings.notifications", settings::notifications, settings::setNotifications));
        out.add(toggle("settings.confirmClose", settings::confirmClose, settings::setConfirmClose));
        out.add(toggle("settings.restoreSession", settings::restoreSession, settings::setRestoreSession));
        out.add(number(
                "settings.scrollback",
                host,
                () -> String.valueOf(settings.scrollbackLines()),
                value -> settings.setScrollbackLines((int) Math.round(value))));
        out.add(text("settings.shell", host, settings::shell, settings::setShell));
        out.add(text("settings.linkOpenCommand", host, settings::linkOpenCommand, settings::setLinkOpenCommand));
        out.add(toggle("settings.altIsMeta", settings::altIsMeta, settings::setAltIsMeta));
        out.add(toggle("settings.copyOnSelect", settings::copyOnSelect, settings::setCopyOnSelect));
        out.add(toggle("settings.clipboardWrite", settings::clipboardWrite, settings::setClipboardWrite));
        out.add(toggle("settings.typeAhead", settings::typeAhead, settings::setTypeAhead));
        // Profiles
        for (Profile profile : host.profiles().all()) {
            out.add(choice(
                    "settings.defaultProfile",
                    profile.name(),
                    () -> host.profiles().setDefaultProfileId(profile.id())));
        }
        // Advanced
        out.add(toggle("settings.updateCheck", settings::updateCheck, settings::setUpdateCheck));
        // Sync
        out.addAll(sync(settings, host));
        return out;
    }

    private static List<Entry> sync(Settings settings, Host host) {
        List<Entry> out = new ArrayList<>();
        SettingsSync sync = host.sync();
        boolean connected = settings.syncEnabled();
        String whileConnected = connected ? tr("palette.setting.disconnectFirst") : null;
        Entry repository = text("settings.sync.repository", host, settings::syncRepository, url -> {
            String problem = SettingsWindow.syncFieldProblem(url, settings.syncBranch());
            if (problem != null && !"settings.sync.branch.invalid".equals(problem)) {
                host.report(
                        MessageLog.Severity.ERROR, tr("palette.setting.invalid", tr("settings.sync.repository"), url));
                return;
            }
            settings.setSyncRepository(url);
        });
        out.add(new Entry(repository.key(), repository.action(), whileConnected));
        Entry branch = text("settings.sync.branch", host, settings::syncBranch, name -> {
            if (!com.termina.sync.SyncEngine.isUsableBranch(name)) {
                host.report(MessageLog.Severity.ERROR, tr("palette.setting.invalid", tr("settings.sync.branch"), name));
                return;
            }
            settings.setSyncBranch(name);
        });
        out.add(new Entry(branch.key(), branch.action(), whileConnected));

        String connection = tr("settings.sync.connection");
        if (connected) {
            out.add(new Entry(
                    "settings.sync.connection",
                    command(
                            tr("palette.setting.choice", connection, tr("settings.sync.disconnect")),
                            "settings.sync.connection",
                            () -> {
                                settings.setSyncEnabled(false);
                                if (sync != null) sync.disconnected();
                            }),
                    null));
        } else {
            String url = settings.syncRepository();
            String problem = SettingsWindow.syncFieldProblem(url, settings.syncBranch());
            out.add(new Entry(
                    "settings.sync.connection",
                    command(
                            tr("palette.setting.choice", connection, tr("settings.sync.connect")),
                            "settings.sync.connection",
                            () -> {
                                settings.setSyncEnabled(true);
                                if (sync != null) sync.connected();
                            }),
                    sync == null
                            ? tr("palette.setting.noSync")
                            : problem != null ? tr("palette.setting.setRepositoryFirst") : null));
        }
        out.add(toggle("settings.sync.auto", settings::syncAuto, settings::setSyncAuto));
        out.add(number(
                "settings.sync.interval",
                host,
                () -> String.valueOf(settings.syncIntervalMinutes()),
                value -> settings.setSyncIntervalMinutes((int) Math.round(value))));
        return out;
    }

    // ---------------------------------------------------------------- shapes

    /** A switch: the row's title, and which way it will go. */
    static Entry toggle(String key, Supplier<Boolean> on, Consumer<Boolean> set) {
        boolean now = on.get();
        String label = tr(now ? "palette.setting.turnOff" : "palette.setting.turnOn", tr(key));
        return new Entry(key, command(label, key, () -> set.accept(!now)), null);
    }

    /** One option of a choice, as its own row. */
    static Entry choice(String key, String option, Runnable choose) {
        return new Entry(key, command(tr("palette.setting.choice", tr(key), option), key, choose), null);
    }

    /** A number, asked for; anything that does not read as one is reported and changes nothing. */
    static Entry number(String key, Host host, Supplier<String> current, Consumer<Double> set) {
        return text(key, host, current, input -> {
            Double value = parseNumber(input);
            if (value == null) {
                host.report(MessageLog.Severity.ERROR, tr("palette.setting.invalid", tr(key), input));
                return;
            }
            set.accept(value);
        });
    }

    /** A value, asked for, starting from the current one. */
    static Entry text(String key, Host host, Supplier<String> current, Consumer<String> set) {
        String title = tr(key);
        return new Entry(
                key,
                command(tr("palette.setting.prompt", title), key, () -> {
                    String answer = host.ask(title, description(key), current.get());
                    if (answer != null) set.accept(answer.strip());
                }),
                null);
    }

    /** A decimal in either the user's notation or the dot one; null when it is not a number. */
    static @Nullable Double parseNumber(String input) {
        String s = input.strip().replace(',', '.');
        if (s.isEmpty()) return null;
        try {
            double value = Double.parseDouble(s);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static MenuAction command(String label, String key, Runnable run) {
        return MenuAction.of(label, run).describedAs(description(key)).withKeywords(tr("palette.group.settings"));
    }

    /** The row's description, when it has one that reads without arguments. */
    private static String description(String key) {
        String desc = tr(key + ".desc");
        return desc.equals(key + ".desc") || desc.contains("{0}") ? "" : desc;
    }
}
