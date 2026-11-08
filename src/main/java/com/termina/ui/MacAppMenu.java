package com.termina.ui;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Puts "About Termina" at the top of the macOS application menu (ABOUT-01, Pauta 2.0.2).
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
        if (installed || !shouldInstall(System.getProperty("os.name", ""))) return false;
        try {
            Class<?> application = Class.forName("com.sun.glass.ui.Application");
            Object app = application.getMethod("GetApplication").invoke(null);
            Method getAppleMenu =
                    Class.forName("com.sun.glass.ui.mac.MacApplication").getDeclaredMethod("getAppleMenu");
            getAppleMenu.setAccessible(true);
            Object appleMenu = getAppleMenu.invoke(app);
            if (appleMenu == null) return false;

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
                                return "About menu item";
                            }
                            default -> {
                                // validate(): always enabled.
                            }
                        }
                        return null;
                    });
            Object item = application
                    .getMethod("createMenuItem", String.class, callbackType)
                    .invoke(app, title, callback);
            Class<?> menuItem = Class.forName("com.sun.glass.ui.MenuItem");
            Class<?> menu = Class.forName("com.sun.glass.ui.Menu");
            Method insert = menu.getMethod("insert", menuItem, int.class);
            insert.invoke(appleMenu, item, 0);
            insert.invoke(appleMenu, menuItem.getField("Separator").get(null), 1);
            installed = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.getLogger(MacAppMenu.class.getName())
                    .log(System.Logger.Level.INFO, "no About item in the application menu: " + e);
            return false;
        }
    }

    /** Only macOS has an application menu. */
    static boolean shouldInstall(String osName) {
        return osName.toLowerCase(java.util.Locale.ROOT).startsWith("mac");
    }
}
