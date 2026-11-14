package com.termina.ui;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Puts "About Termina" at the top of the macOS application menu (ABOUT-01, Pauta 2.0.2), and
 * Settings under it (WIN-15, Pauta 6.0.1).
 *
 * <p>JavaFX builds that menu itself — Hide, Hide Others, Show All, Quit — in Glass's
 * {@code MacApplication.installAppleMenu}, and has no public API to add to it. Glass keeps the menu
 * and its own menu-item factory reachable, so this goes through them by reflection, which is why the
 * launchers carry {@code --add-exports javafx.graphics/com.sun.glass.ui=com.termina} and
 * {@code --add-opens javafx.graphics/com.sun.glass.ui.mac=com.termina}. Reflection rather than a
 * compile-time reference, so building needs neither flag and a launch without them — a test, an IDE
 * run — loses the item and nothing else.
 *
 * <p>The menu exists once the first window with a system menu bar is showing, so this is called
 * then, on the FX thread, and once: the Apple menu is the application's, not a window's.
 */
final class MacAppMenu {

    private static boolean installed;

    private MacAppMenu() {}

    /**
     * Adds {@code title} running {@code action}, and a separator under it, as the application
     * menu's first item. Returns whether it did; never throws.
     */
    static synchronized boolean addAbout(String title, Runnable action) {
        if (installed || !shouldInstall(com.termina.Os.name())) return false;
        installed = insert(0, title, action, "About") != null;
        return installed;
    }

    /** The Settings item once it is in, as Glass's own menu item, so its title can be written again. */
    private static @org.jspecify.annotations.Nullable Object settingsItem;

    /**
     * Adds the Settings item under About (WIN-15, Pauta 6.0.1), where every Mac app has it, with
     * a separator under it. It runs what the Settings command runs. The item has no key
     * equivalent of its own: macOS would run one before the app's key filter saw the key, and
     * would go on showing it in a keymap that binds another chord. The chord in force is part of
     * {@code title} instead ({@link #settingsTitle}), written again by {@link #retitleSettings}
     * when the bindings change. Returns whether it is in; never throws.
     */
    static synchronized boolean addSettings(String title, Runnable action) {
        if (settingsItem != null) return true;
        if (!shouldInstall(com.termina.Os.name())) return false;
        // Under About and its separator when they are in, else at the top.
        settingsItem = insert(installed ? 2 : 0, title, action, "Settings");
        return settingsItem != null;
    }

    /** Writes the Settings item's title again: the bindings changed, and its chord is in its title. */
    static synchronized void retitleSettings(String title) {
        Object item = settingsItem;
        if (item == null) return;
        try {
            Class.forName("com.sun.glass.ui.MenuItem")
                    .getMethod("setTitle", String.class)
                    .invoke(item, title);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.getLogger(MacAppMenu.class.getName())
                    .log(System.Logger.Level.INFO, "the application menu's Settings item kept its title: " + e);
        }
    }

    /**
     * The Settings item's title: the command's own, and the chord bound to it after a gap, as
     * the rows of the system menu bar show theirs (MENU-02); the title alone for no chord. Pure.
     */
    static String settingsTitle(
            String title, javafx.scene.input.@org.jspecify.annotations.Nullable KeyCombination chord) {
        return MenuRows.systemText(title, chord == null ? "" : chord.getDisplayText());
    }

    /**
     * Puts an item titled {@code title}, running {@code action} on the FX thread, at {@code index}
     * of the application menu with a separator under it. Returns Glass's item, or null when it
     * could not: no menu yet, or a launch without the flags the class comment names.
     */
    private static @org.jspecify.annotations.Nullable Object insert(
            int index, String title, Runnable action, String what) {
        try {
            Class<?> application = Class.forName("com.sun.glass.ui.Application");
            Object app = application.getMethod("GetApplication").invoke(null);
            Method getAppleMenu =
                    Class.forName("com.sun.glass.ui.mac.MacApplication").getDeclaredMethod("getAppleMenu");
            getAppleMenu.setAccessible(true);
            Object appleMenu = getAppleMenu.invoke(app);
            if (appleMenu == null) return null;

            Class<?> callbackType = Class.forName("com.sun.glass.ui.MenuItem$Callback");
            Object callback = Proxy.newProxyInstance(
                    MacAppMenu.class.getClassLoader(), new Class<?>[] {callbackType}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "action" -> javafx.application.Platform.runLater(action);
                            case "hashCode" -> {
                                return System.identityHashCode(proxy);
                            }
                            case "equals" -> {
                                return proxy == args[0];
                            }
                            case "toString" -> {
                                return what + " menu item";
                            }
                            default -> {
                                // validate(): always enabled.
                            }
                        }
                        return null;
                    });
            // The two-argument factory: no shortcut, so no key equivalent.
            Object item = application
                    .getMethod("createMenuItem", String.class, callbackType)
                    .invoke(app, title, callback);
            Class<?> menuItem = Class.forName("com.sun.glass.ui.MenuItem");
            Class<?> menu = Class.forName("com.sun.glass.ui.Menu");
            Method insert = menu.getMethod("insert", menuItem, int.class);
            insert.invoke(appleMenu, item, index);
            insert.invoke(appleMenu, menuItem.getField("Separator").get(null), index + 1);
            return item;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.getLogger(MacAppMenu.class.getName())
                    .log(System.Logger.Level.INFO, "no " + what + " item in the application menu: " + e);
            return null;
        }
    }

    /** Only macOS has an application menu. */
    static boolean shouldInstall(String osName) {
        return com.termina.Os.of(osName) == com.termina.Os.Kind.MAC;
    }
}
